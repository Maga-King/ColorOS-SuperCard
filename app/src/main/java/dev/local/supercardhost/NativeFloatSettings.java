package dev.local.supercardhost;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Reuses the installed sidebar's float-bar page with an isolated card configuration session. */
public final class NativeFloatSettings {
    public static final String CARD_FLOATBAR_MODE = "CARD_FLOATBAR_MODE";
    private static final String TAG = "SuperCardNativeFloat";
    private static final String SETTINGS = "com.oplus.smartsidebar.settings.";
    private static final String WIDGETS = SETTINGS + "widgets.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Activity, Page> PAGES = new WeakHashMap<>();
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final float MIN_LENGTH = .0401f;
    private static final float[] ALPHAS = {.15f, .41f, .67f};
    private static volatile SidebarDexResolver.Plan PLAN;
    private static volatile boolean READY;

    private NativeFloatSettings() { }

    public static boolean isReady() { return READY; }

    public static boolean isCardPage(Object object) {
        Activity activity = activity(object);
        if (activity == null) return false;
        try {
            Intent intent = activity.getIntent();
            return intent != null && (intent.getBooleanExtra(CARD_FLOATBAR_MODE, false)
                    || intent.getBooleanExtra("dev.local.supercardhost.CARD_FLOATBAR_MODE", false));
        } catch (Throwable ignored) { return false; }
    }

    public static synchronized void install(XposedModule module, ClassLoader loader) {
        if (module == null || loader == null || !INSTALLED.add(loader)) return;
        READY = false;
        // Install the entry guard before resolving any obfuscated preference/native write boundary.
        // Static readiness is evaluated in the sidebar process, including external Activity entries.
        try {
            installEntryGuard(module, loader);
            PLAN = SidebarDexResolver.resolveNative(loader);
        // The installed APK keeps its Application, resources, manifest and native preference UI.
        hook(module, loader, SETTINGS + "FloatBarSettingsActivity", "P", chain -> {
            Page page = page(chain.getThisObject());
            if (page.fragment.get() != null) return null;
            Object fragment = loader.loadClass(SETTINGS + "FloatBarSettingsWithoutGestureFragment")
                    .getConstructor().newInstance();
            invoke(fragment, "setArguments", new Bundle());
            page.fragment = weak(fragment);
            Activity owner = page.owner.get();
            int rootId = owner.getResources().getIdentifier("rootView", "id", owner.getPackageName());
            if (rootId == 0) throw new IllegalStateException("Native rootView missing");
            Object transaction = invoke(invoke(owner, "o"), "k");
            invoke(transaction, "p", rootId, fragment);
            invoke(transaction, "i");
            return null;
        });
        installFragment(module, loader, "FloatBarSettingsWithoutGestureFragment", false);
        installFragment(module, loader, "FloatBarSettingsFragment", true);
        installSlider(module, loader, "PositionPromiseSeekbarPreference", 0);
        installSlider(module, loader, "SizePromiseSeekbarPreference", 1);
        installAlpha(module, loader);
        installPermanent(module, loader);
        installNewIntent(module);
        READY = true;
        // A native fragment title reaches the original COUI toolbar.
        Log.i(TAG, "Installed native card float-bar mode");
        } catch (Throwable error) {
            READY = false;
            failure("Native card page preflight failed; using independent settings", error);
        }
    }

    private static void installEntryGuard(XposedModule module, ClassLoader loader) throws Exception {
        Method ancestor = SidebarDexResolver.exact(loader.loadClass("androidx.appcompat.app.AppCompatActivity"),
                "onCreate", Bundle.class);
        Set<Method> installed = new java.util.HashSet<>();
        for (String simple : new String[]{"FloatBarSettingsActivity", "FloatBarSettingsPhoneActivity"}) {
            Method create = SidebarDexResolver.exact(loader.loadClass(SETTINGS + simple), "onCreate", Bundle.class);
            if (!installed.add(create)) continue;
            module.hook(create).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Activity owner = (Activity) chain.getThisObject();
                    if (!isCardPage(owner) || READY) return chain.proceed();
                    // Complete the Android lifecycle without entering OEM fragment initialization.
                    module.getInvoker(ancestor).setType(XposedInterface.Invoker.Type.ORIGIN)
                            .invokeSpecial(owner, chain.getArg(0));
                    try {
                        owner.startActivity(new Intent().setClassName("dev.local.supercardhost",
                                "dev.local.supercardhost.GestureAreaActivity"));
                    } catch (Throwable error) { failure("open independent card settings", error); }
                    owner.finish();
                    return null;
                }
            });
        }
    }

    private static void installFragment(XposedModule module, ClassLoader loader, String simple, boolean gesture) {
        String name = SETTINGS + simple;
        hook(module, loader, name, "onCreate", chain -> {
            Page page = page(chain.getThisObject());
            page.fragment = weak(chain.getThisObject());
            Object result = chain.proceed();
            decorate(page);
            query(page);
            return result;
        });
        hook(module, loader, name, "onResume", chain -> {
            Page page = page(chain.getThisObject());
            page.active = true;
            Object result = chain.proceed();
            decorate(page);
            if (!page.loaded || (!page.saving && page.pending.isEmpty())) query(page);
            lease(page);
            return result;
        });
        hook(module, loader, name, "onPause", chain -> {
            Page page = page(chain.getThisObject());
            page.active = false;
            page.tracking = -1;
            MAIN.removeCallbacks(page.previewTask);
            MAIN.removeCallbacks(page.leaseTask);
            MAIN.removeCallbacks(page.saveTask);
            MAIN.removeCallbacks(page.reconnectTask);
            drain(page);
            endPreview(page);
            return chain.proceed();
        });
        hook(module, loader, name, "onDestroy", chain -> {
            Page page = page(chain.getThisObject());
            page.active = false;
            MAIN.removeCallbacks(page.previewTask);
            MAIN.removeCallbacks(page.leaseTask);
            MAIN.removeCallbacks(page.saveTask);
            MAIN.removeCallbacks(page.reconnectTask);
            drain(page);
            endPreview(page);
            page.closed = true;
            synchronized (PAGES) {
                Activity owner = page.owner.get();
                if (owner != null) PAGES.remove(owner);
            }
            return chain.proceed();
        });
        hook(module, loader, name, "y", chain -> "超级卡包浮标设置");
        // Auto hide must not remove the size, opacity or position controls from this page.
        for (String method : gesture ? new String[]{"g0", "h0", "e0"} : new String[]{"b0", "Z"}) {
            hook(module, loader, name, method, chain -> { decorate(page(chain.getThisObject())); return null; });
        }
        // Replace the full reset routine: original code also changes pc.a's shared preferences.
        hook(module, loader, name, gesture ? "U" : "R", chain -> {
            Page page = page(chain.getThisObject());
            if (!page.loaded) return null;
            stopSliders(page);
            page.version++;
            page.top = .76f; page.bottom = .98f; page.opacity = .4f;
            queueRange(page);
            queueOpacity(page);
            render(page);
            schedulePreview(page);
            drain(page);
            return null;
        });
        // Original fragment refreshes the radio choice via this method. It reads our narrow getter.
        hook(module, loader, name, gesture ? "V" : "S", chain -> {
            Page page = page(chain.getThisObject());
            page.rendering = true;
            try { return chain.proceed(); }
            finally { page.rendering = false; decorate(page); }
        });
    }

    private static void installNewIntent(XposedModule module) {
        try {
            Method target = Activity.class.getDeclaredMethod("onNewIntent", Intent.class);
            target.setAccessible(true);
            module.hook(target).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Activity owner = (Activity) chain.getThisObject();
                        String name = owner.getClass().getName();
                        if (!name.equals(SETTINGS + "FloatBarSettingsActivity")
                                && !name.equals(SETTINGS + "FloatBarSettingsPhoneActivity")) return result;
                        Intent incoming = (Intent) chain.getArg(0);
                        boolean previous = isCardPage(owner);
                        owner.setIntent(incoming);
                        boolean next = isCardPage(owner);
                        if (previous != next) {
                            Page page;
                            synchronized (PAGES) { page = PAGES.remove(owner); }
                            if (page != null) {
                                page.active = false;
                                MAIN.removeCallbacks(page.previewTask);
                                MAIN.removeCallbacks(page.leaseTask);
                                MAIN.removeCallbacks(page.saveTask);
                                MAIN.removeCallbacks(page.reconnectTask);
                                drain(page); endPreview(page); page.closed = true;
                            }
                            MAIN.post(owner::recreate);
                        } else if (next) {
                            Page page = page(owner);
                            MAIN.post(() -> query(page));
                        }
                    } catch (Throwable error) { failure("native settings onNewIntent", error); }
                    return result;
                }
            });
        } catch (Throwable error) { throw new IllegalStateException("hook native settings onNewIntent", error); }
    }

    private static void stopSliders(Page page) {
        boolean previous = page.rendering;
        page.rendering = true;
        try {
            for (Object preference : new Object[]{page.position.get(), page.size.get()}) {
                if (preference == null) continue;
                Object bar = invoke(preference, "g1");
                if (bar != null) invoke(bar, "w1");
            }
        } catch (Throwable error) { failure("stop native sliders", error); }
        finally { page.rendering = previous; page.tracking = -1; }
    }

    private static void installSlider(XposedModule module, ClassLoader loader, String simple, int kind) {
        String name = WIDGETS + simple;
        hook(module, loader, name, "o1", chain -> {
            Page page = page(chain.getThisObject());
            updateSlider(page, chain.getThisObject(), kind);
            return null;
        });
        hook(module, loader, name, "l1", chain -> {
            Page page = page(chain.getThisObject());
            if (!page.loaded || page.rendering || page.closed || !page.active) return null;
            int progress = ((Number) chain.getArg(1)).intValue();
            float fraction = clamp(progress / 100f, 0, 1);
            float length = page.bottom - page.top;
            if (kind == 0) {
                page.top = fraction * (1 - length);
                page.bottom = page.top + length;
            } else {
                float center = (page.top + page.bottom) / 2;
                length = MIN_LENGTH + fraction * (1 - MIN_LENGTH);
                page.top = clamp(center - length / 2, 0, 1 - length);
                page.bottom = page.top + length;
            }
            page.version++;
            queueRange(page);
            schedulePreview(page);
            MAIN.removeCallbacks(page.saveTask);
            MAIN.postDelayed(page.saveTask, 600);
            // Do not call native setProgress on the thumb currently owned by the gesture.
            render(page);
            return null;
        });
        hook(module, loader, name, "k1", chain -> {
            Page page = page(chain.getThisObject());
            page.tracking = -1;
            drain(page);
            return null;
        });
        hook(module, loader, name, "j1", chain -> {
            page(chain.getThisObject()).tracking = kind;
            return null;
        });
    }

    private static void installAlpha(XposedModule module, ClassLoader loader) {
        String name = WIDGETS + "SeekBarPreference";
        hook(module, loader, name, "g1", chain -> nearestAlpha(page(chain.getThisObject()).opacity) * 40);
        hook(module, loader, name, "h1", chain -> nearestAlpha(page(chain.getThisObject()).opacity));
        hook(module, loader, name, "j1", chain -> {
            updateSlider(page(chain.getThisObject()), chain.getThisObject(), 2);
            return null;
        });
        hook(module, loader, name, "k", chain -> {
            Page page = page(chain.getThisObject());
            if (!page.loaded || page.rendering || page.closed || !page.active
                    || !Boolean.TRUE.equals(chain.getArg(2))) return null;
            int selected = Math.max(0, Math.min(2, ((Number) chain.getArg(1)).intValue()));
            page.opacity = ALPHAS[selected];
            page.version++;
            queueOpacity(page);
            schedulePreview(page);
            MAIN.removeCallbacks(page.saveTask);
            MAIN.postDelayed(page.saveTask, 350);
            return null;
        });
        hook(module, loader, name, "l", chain -> { page(chain.getThisObject()).tracking = 2; return null; });
        hook(module, loader, name, "j", chain -> {
            Page page = page(chain.getThisObject());
            page.tracking = -1;
            drain(page);
            return null;
        });
    }

    private static void installPermanent(XposedModule module, ClassLoader loader) {
        String name = "com.coloros.edgepanel.utils.EdgePanelSettingsValueProxy";
        hookContext(module, loader, name, "getFloatBarPermanentEnable", chain ->
                page(chain.getArg(0)).autoHide ? 0 : 1);
        hookContext(module, loader, name, "setFloatBarPermanentEnable", chain -> {
            Page page = page(chain.getArg(0));
            if (!page.loaded || page.rendering || page.closed || !page.active) return null;
            page.autoHide = ((Number) chain.getArg(1)).intValue() == 0;
            page.version++;
            Bundle value = new Bundle(); value.putBoolean("value", page.autoHide);
            page.pending.put("handleAutoHide", value);
            schedulePreview(page);
            render(page);
            drain(page);
            return null;
        });
        // Prevent any native reset/menu fallback from writing sidebar settings in card mode.
        for (String setter : new String[]{"setFloatBarSize", "setFloatBarAlpha", "setFloatBarPosition",
                "setFloatBarLeftRight", "resetFloatBarPosition", "resetFloatBarLeftRight",
                "setSystemUiSlideGestureEnable"}) {
            hookContext(module, loader, name, setter, chain -> null);
        }
        hookContext(module, loader, name, "getFloatBarIsLeft", chain -> false);
        hookContext(module, loader, name, "isSystemUiSlideGestureEnable", chain -> false);
        if (PLAN.optional(loaderUnchecked(loader, name), "isSystemUiSlideGestureEnableAndResetSetting") != null) {
            hookContext(module, loader, name, "isSystemUiSlideGestureEnableAndResetSetting", chain -> false);
        }
    }

    private static void decorate(Page page) {
        Object fragment = page.fragment.get();
        if (fragment == null || page.decorating) return;
        page.decorating = true;
        try {
            Object root = invoke(fragment, "a", "root_pfs");
            if (root == null) return;
            page.root = weak(root);
            for (String key : new String[]{"key_left_and_right_position", "key_gesture_study_category",
                    "key_float_bottom", "key_permanent_category"}) {
                Object preference = invoke(root, "X0", key);
                if (preference != null) invoke(preference, "P0", false);
            }
            Object handles = invoke(root, "X0", "key_float_handle");
            if (handles != null) invoke(handles, "P0", true);
            for (String key : new String[]{"key_size", "key_alpha", "key_up_and_down_position"}) {
                Object preference = invoke(root, "X0", key);
                // b0 may have removed alpha before the hook was installed; fields still hold it.
                if (preference == null && key.equals("key_alpha")) {
                    try { preference = PLAN.field(fragment.getClass(), "alpha").get(fragment); }
                    catch (Throwable ignored) { }
                    if (preference != null && handles != null) invoke(handles, "W0", preference);
                }
                if (preference != null) {
                    invoke(preference, "P0", true);
                    invoke(preference, "z0", page.loaded);
                    if (key.equals("key_size")) page.size = weak(preference);
                    else if (key.equals("key_alpha")) page.alpha = weak(preference);
                    else page.position = weak(preference);
                }
            }
            Object radio = invoke(root, "X0", "key_float_category");
            if (radio != null) { invoke(radio, "P0", true); invoke(radio, "z0", page.loaded); }
            Object restore = invoke(root, "X0", "key_floatBar_reduction");
            if (restore != null) invoke(restore, "z0", page.loaded);
            Activity owner = page.owner.get();
            if (owner != null) {
                owner.setTitle("超级卡包浮标设置");
                addExtraControls(page, owner, root);
            }
        } catch (Throwable error) { failure("decorate", error); }
        finally { page.decorating = false; }
    }

    private static void addExtraControls(Page page, Activity owner, Object root) throws Exception {
        ClassLoader loader = owner.getClassLoader();
        Object visibleCategory = extraCategory(loader, owner, root, "supercard_native_visible_category", 8);
        Object otherCategory = extraCategory(loader, owner, root, "supercard_native_other_category", 9);
        Object visible = invoke(root, "X0", "supercard_native_float_visible");
        if (visible == null) {
            visible = loader.loadClass("com.coui.appcompat.preference.COUISwitchPreference")
                    .getConstructor(Context.class).newInstance(owner);
            configurePreference(loader, visible, "supercard_native_float_visible", "显示浮标", 0);
            Class<?> listenerType = PLAN.changeListener;
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, called, args) -> {
                if (called.getDeclaringClass() == Object.class) {
                    if (called.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (called.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    return "SuperCardNativeVisibility";
                }
                if (called.getReturnType() == boolean.class && called.getParameterCount() == 2 && args != null && args.length == 2
                        && args[1] instanceof Boolean && page.loaded && !page.rendering && page.active && !page.closed) {
                    page.visible = (Boolean) args[1]; page.version++;
                    Bundle value = new Bundle(); value.putBoolean("value", page.visible);
                    page.pending.put("handleVisible", value);
                    schedulePreview(page); render(page); drain(page);
                }
                return false;
            });
            invoke(visible, "G0", listener);
            invoke(visibleCategory, "W0", visible);
        }
        invoke(visible, "W0", page.visible);
        invoke(visible, "z0", page.loaded);
        if (invoke(root, "X0", "supercard_native_other_settings") == null) {
            Object details = loader.loadClass("com.coui.appcompat.preference.COUIJumpPreference")
                    .getConstructor(Context.class).newInstance(owner);
            configurePreference(loader, details, "supercard_native_other_settings", "其他呼出设置", 0);
            Intent intent = new Intent().setClassName("dev.local.supercardhost",
                    "dev.local.supercardhost.GestureAreaActivity");
            invoke(details, "D0", intent);
            invoke(otherCategory, "W0", details);
        }
    }

    private static Object extraCategory(ClassLoader loader, Activity owner, Object root,
            String key, int order) throws Exception {
        Object category = invoke(root, "X0", key);
        if (category == null) {
            category = loader.loadClass("com.coui.appcompat.preference.COUIPreferenceCategory")
                    .getConstructor(Context.class, android.util.AttributeSet.class).newInstance(owner, null);
            configurePreference(loader, category, key, "", order);
            // Attach the group to its PreferenceManager before attaching any children.
            invoke(root, "W0", category);
        }
        return category;
    }

    private static void configurePreference(ClassLoader loader, Object preference, String key,
            String title, int order) throws Exception {
        Class<?> type = loader.loadClass("androidx.preference.Preference");
        PLAN.field(type, "key").set(preference, key);
        PLAN.field(type, "persistent").setBoolean(preference, false);
        invoke(preference, "O0", title); invoke(preference, "I0", order);
    }

    private static void render(Page page) {
        if (page.rendering || page.closed) return;
        page.rendering = true;
        try {
            updateSlider(page, page.position.get(), 0);
            updateSlider(page, page.size.get(), 1);
            updateSlider(page, page.alpha.get(), 2);
            Object fragment = page.fragment.get();
            if (fragment != null) {
                // S updates the two original animated phone radio cards through our getter.
                invoke(fragment, fragment.getClass().getSimpleName().contains("Without") ? "S" : "V");
            }
        } catch (Throwable error) { failure("render", error); }
        finally { page.rendering = false; decorate(page); }
    }

    private static void updateSlider(Page page, Object preference, int kind) {
        if (preference == null || page.tracking == kind) return;
        boolean previous = page.rendering;
        page.rendering = true;
        try {
            Object bar = kind == 2 ? PLAN.field(preference.getClass(), "bar").get(preference) : invoke(preference, "g1");
            if (bar == null) return;
            invoke(bar, "setMax", kind == 2 ? 2 : 100);
            float length = page.bottom - page.top;
            int progress = kind == 2 ? nearestAlpha(page.opacity) : Math.round(100 *
                    (kind == 0 ? (length >= 1 ? 0 : page.top / (1 - length))
                            : (length - MIN_LENGTH) / (1 - MIN_LENGTH)));
            invoke(bar, "setProgress", Math.max(0, Math.min(kind == 2 ? 2 : 100, progress)));
        } catch (Throwable error) { failure("slider", error); }
        finally { page.rendering = previous; }
    }

    private static void query(Page page) {
        if (page.querying || page.closed || page.saving || !page.pending.isEmpty()) return;
        Context context = page.requester;
        if (context == null) return;
        page.querying = true;
        long version = page.version;
        try {
            CardConfiguration.request(context, "query", null, (state, error) -> MAIN.post(() -> {
                page.querying = false;
                if (page.closed) return;
                if (error != null || !valid(state)) {
                    readFailure(page, "暂时无法读取卡包浮标设置", error);
                    reconnect(page);
                    return;
                }
                page.connected = true;
                page.connectionFailureSince = 0;
                page.connectionWarningShown = false;
                MAIN.removeCallbacks(page.reconnectTask);
                page.confirmed = new Bundle(state);
                if (page.version == version && !page.saving && page.pending.isEmpty()) apply(page, state);
                page.loaded = true;
                decorate(page); render(page);
                schedulePreview(page);
            }));
        } catch (Throwable error) {
            page.querying = false;
            readFailure(page, "暂时无法读取卡包浮标设置", error.toString());
            reconnect(page);
        }
    }

    private static void apply(Page page, Bundle state) {
        page.top = state.getFloat("top", .76f); page.bottom = state.getFloat("bottom", .98f);
        page.opacity = clamp(state.getFloat("handleOpacity", .4f), 0, 1);
        page.visible = state.getBoolean("handleVisible", true);
        page.autoHide = state.getBoolean("handleAutoHide", true);
    }

    private static boolean valid(Bundle state) {
        if (state == null || !state.containsKey("top") || !state.containsKey("bottom")) return false;
        float top = state.getFloat("top"), bottom = state.getFloat("bottom");
        return Float.isFinite(top) && Float.isFinite(bottom) && top >= 0 && bottom <= 1
                && bottom - top >= .039f;
    }

    private static void queueRange(Page page) {
        float[] range = normalizedRange(page.top, page.bottom);
        page.top = range[0]; page.bottom = range[1];
        Bundle value = new Bundle(); value.putFloat("top", page.top); value.putFloat("bottom", page.bottom);
        page.pending.put("range", value);
    }

    private static void queueOpacity(Page page) {
        Bundle value = new Bundle(); value.putFloat("value", page.opacity);
        page.pending.put("handleOpacity", value);
    }

    private static void drain(Page page) {
        MAIN.removeCallbacks(page.saveTask);
        if (!page.loaded || page.saving || page.pending.isEmpty()) return;
        Context context = page.requester;
        if (context == null) return;
        Map.Entry<String, Bundle> entry = page.pending.entrySet().iterator().next();
        String operation = entry.getKey(); Bundle values = new Bundle(entry.getValue());
        page.pending.remove(operation);
        page.saving = true;
        long version = page.version;
        try {
            CardConfiguration.request(context, operation, values, (state, error) -> MAIN.post(() -> {
                page.saving = false;
                if (error != null || !valid(state)) {
                    page.pending.clear();
                    if (page.confirmed != null) apply(page, page.confirmed);
                    render(page); schedulePreview(page);
                    report(page, "保存失败，已恢复已读取的设置", error);
                    reconnect(page);
                    return;
                }
                page.confirmed = new Bundle(state);
                if (version == page.version && page.pending.isEmpty() && page.tracking < 0) {
                    apply(page, state); render(page); schedulePreview(page);
                }
                // Keep draining even after onPause/onDestroy so the last queued edit is not dropped.
                drain(page);
            }));
        } catch (Throwable error) {
            page.saving = false; page.pending.clear();
            if (page.confirmed != null) apply(page, page.confirmed);
            render(page); schedulePreview(page);
            report(page, "保存失败，已恢复已读取的设置", error.toString());
            reconnect(page);
        }
    }

    private static void schedulePreview(Page page) {
        if (!page.active || !page.loaded || page.closed) return;
        MAIN.removeCallbacks(page.previewTask);
        long delay = Math.max(0, 50 - (SystemClock.uptimeMillis() - page.lastPreview));
        MAIN.postDelayed(page.previewTask, delay);
    }

    private static void preview(Page page) {
        if (!page.active || !page.loaded || page.closed) return;
        if (page.previewSending) { page.previewAgain = true; return; }
        Context context = page.requester;
        if (context == null) return;
        Bundle values = new Bundle();
        // Pixel-rounded saved ranges can be slightly shorter than the backend's
        // normalized minimum. Expand presentation only; never save on a query.
        float[] range = normalizedRange(page.top, page.bottom);
        values.putFloat("top", range[0]); values.putFloat("bottom", range[1]);
        values.putFloat("handleOpacity", page.opacity); values.putBoolean("handleVisible", page.visible);
        page.previewSending = true; page.lastPreview = SystemClock.uptimeMillis();
        try {
            CardConfiguration.request(context, "preview", values, (state, error) -> MAIN.post(() -> {
                page.previewSending = false;
                if (error != null) {
                    readFailure(page, "浮标实时预览暂不可用", error);
                    reconnect(page);
                } else {
                    page.connected = true;
                    page.connectionFailureSince = 0;
                    page.connectionWarningShown = false;
                }
                if (page.previewAgain) { page.previewAgain = false; schedulePreview(page); }
                // A pause while a preview was in flight always wins over that preview.
                if (!page.active) endPreview(page);
            }));
        } catch (Throwable error) {
            page.previewSending = false;
            readFailure(page, "浮标实时预览暂不可用", error.toString());
            reconnect(page);
        }
    }

    private static float[] normalizedRange(float top, float bottom) {
        top = clamp(top, 0, 1);
        bottom = clamp(bottom, top, 1);
        if (bottom - top < MIN_LENGTH) {
            top = Math.min(top, 1 - MIN_LENGTH);
            bottom = Math.min(1, top + MIN_LENGTH);
        }
        return new float[]{top, bottom};
    }

    private static void reconnect(Page page) {
        if (!page.active || page.closed) return;
        MAIN.removeCallbacks(page.reconnectTask);
        MAIN.postDelayed(page.reconnectTask, 1000);
    }

    private static void readFailure(Page page, String message, String error) {
        page.connected = false;
        long now = SystemClock.uptimeMillis();
        if (page.connectionFailureSince == 0) page.connectionFailureSince = now;
        // Short SystemUI restarts recover through read-only query/preview retry.
        // Surface a persistent problem, rather than a toast on every lease tick.
        if (now - page.connectionFailureSince >= 5000 && !page.connectionWarningShown
                && page.active && !page.closed) {
            report(page, message, error);
            page.connectionWarningShown = true;
        } else Log.w(TAG, message + "; reconnecting: " + error);
    }

    private static void lease(Page page) {
        MAIN.removeCallbacks(page.leaseTask);
        if (page.active && !page.closed) MAIN.postDelayed(page.leaseTask, 3000);
    }

    private static void endPreview(Page page) {
        Context context = page.requester;
        if (context == null) return;
        try { CardConfiguration.request(context, "previewEnd", null, (state, error) -> { }); }
        catch (Throwable error) { failure("previewEnd", error); }
    }

    private static int nearestAlpha(float value) {
        int best = 0;
        for (int i = 1; i < ALPHAS.length; i++) {
            if (Math.abs(value - ALPHAS[i]) < Math.abs(value - ALPHAS[best])) best = i;
        }
        return best;
    }

    private static Page page(Object object) {
        Activity owner = activity(object);
        if (owner == null) throw new IllegalStateException("No card settings Activity");
        synchronized (PAGES) { return PAGES.computeIfAbsent(owner, Page::new); }
    }

    private static Activity activity(Object object) {
        if (object instanceof Context) {
            Context current = (Context) object;
            for (int i = 0; current != null && i < 20; i++) {
                if (current instanceof Activity) return (Activity) current;
                if (!(current instanceof ContextWrapper)) break;
                Context base = ((ContextWrapper) current).getBaseContext();
                if (base == current) break;
                current = base;
            }
            return null;
        }
        if (object == null) return null;
        for (String name : new String[]{"getActivity", "getContext", "u"}) try {
            Object context = invoke(object, name);
            if (context instanceof Context) {
                Activity result = activity(context);
                if (result != null) return result;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private interface CardHook { Object run(XposedInterface.Chain chain) throws Throwable; }

    private static void hook(XposedModule module, ClassLoader loader, String name, String method, CardHook action) {
        installHook(module, loader, name, method, false, action);
    }

    private static void hookContext(XposedModule module, ClassLoader loader, String name, String method, CardHook action) {
        installHook(module, loader, name, method, true, action);
    }

    private static void installHook(XposedModule module, ClassLoader loader, String name, String method,
            boolean contextArg, CardHook action) {
        try {
            Class<?> type = loader.loadClass(name);
            Method target = PLAN.method(type, method);
                if (contextArg && (target.getParameterCount() == 0
                        || target.getParameterTypes()[0] != Context.class)) throw new NoSuchMethodException("Context boundary");
                target.setAccessible(true);
                module.hook(target).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object owner = contextArg ? chain.getArg(0) : chain.getThisObject();
                        if (!isCardPage(owner)) return chain.proceed();
                        if (!READY) return defaultResult(target.getReturnType());
                        try { return action.run(chain); }
                        catch (Throwable error) {
                            // Never fall through to OEM writes after a card adapter failure.
                            failure(name + "." + method, error);
                            Page page = page(owner);
                            report(page, "卡包浮标设置暂不可用", error.toString());
                            return defaultResult(target.getReturnType());
                        }
                    }
                });
        } catch (Throwable error) { throw new IllegalStateException("hook " + name + "." + method, error); }
    }

    private static Class<?> loaderUnchecked(ClassLoader loader, String name) {
        try { return loader.loadClass(name); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
    }

    private static Object defaultResult(Class<?> result) {
        if (result == boolean.class) return false;
        if (result == int.class) return 0;
        if (result == float.class) return 0f;
        if (result == long.class) return 0L;
        return null;
    }

    private static Object invoke(Object object, String name, Object... arguments) throws Exception {
        if (object == null) return null;
        SidebarDexResolver.Plan plan = PLAN;
        Method resolved = plan == null ? null : plan.optional(object.getClass(), name);
        if (resolved != null) return resolved.invoke(object, arguments);
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            Method candidate = null;
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                Class<?>[] types = method.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < types.length; i++) {
                    if (arguments[i] == null) { if (types[i].isPrimitive()) matches = false; continue; }
                    Class<?> expected = types[i];
                    if (expected == int.class) expected = Integer.class;
                    else if (expected == boolean.class) expected = Boolean.class;
                    else if (expected == float.class) expected = Float.class;
                    if (!expected.isInstance(arguments[i])) matches = false;
                }
                if (!matches) continue;
                if (candidate != null) throw new NoSuchMethodException(type.getName() + "." + name + " ambiguous overloads");
                candidate = method;
            }
            if (candidate != null) { candidate.setAccessible(true); return candidate.invoke(object, arguments); }
        }
        throw new NoSuchMethodException(object.getClass().getName() + "." + name);
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) try {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
        } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private static WeakReference<Object> weak(Object value) { return new WeakReference<>(value); }

    private static void report(Page page, String message, String error) {
        if (error != null) Log.w(TAG, message + ": " + error);
        if (!page.active || page.closed || SystemClock.uptimeMillis() - page.lastToast < 3000) return;
        page.lastToast = SystemClock.uptimeMillis();
        Activity owner = page.owner.get();
        if (owner != null) MAIN.post(() -> Toast.makeText(owner, message, Toast.LENGTH_SHORT).show());
    }

    private static void failure(String operation, Throwable error) { Log.e(TAG, operation, error); }

    private static final class Page {
        final WeakReference<Activity> owner;
        final Context requester;
        WeakReference<Object> fragment = weak(null), root = weak(null), size = weak(null), position = weak(null), alpha = weak(null);
        final LinkedHashMap<String, Bundle> pending = new LinkedHashMap<>();
        Bundle confirmed;
        float top = .76f, bottom = .98f, opacity = .4f;
        boolean visible = true, autoHide = true;
        boolean loaded, connected, connectionWarningShown, active, closed, rendering, decorating, querying, saving, previewSending, previewAgain;
        int tracking = -1;
        long version, lastPreview, lastToast, connectionFailureSince;
        final Runnable saveTask = () -> drain(this);
        final Runnable previewTask = () -> preview(this);
        final Runnable reconnectTask = () -> { if (active && !closed) query(this); };
        final Runnable leaseTask = () -> {
            if (!loaded || !connected) query(this);
            else preview(this);
            lease(this);
        };
        Page(Activity owner) {
            this.owner = new WeakReference<>(owner);
            this.requester = owner.getApplicationContext();
        }
    }
}
