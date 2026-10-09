package dev.local.supercardhost;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Adds card controls to the installed ColorOS sidebar's own COUI preference screens. */
public final class SidebarIntegration {
    private static final String TAG = "SuperCardSidebar";
    private static final String SIDEBAR = "com.coloros.smartsidebar";
    private static final String CATEGORY_KEY = "supercard_host_category";
    private static final String SWITCH_KEY = "supercard_host_enabled";
    private static final String AREA_KEY = "supercard_host_gesture_area";
    private static final String DETAILS_KEY = "supercard_host_details";
    private static final String DETAILS_CATEGORY_KEY = "supercard_host_details_category";
    private static final String FLOAT_SETTINGS_KEY = "supercard_host_float_settings";
    private static final String DESCRIPTION = "右侧滑出微信/支付宝卡片";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> HOOKED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Object, Page> PAGES = new WeakHashMap<>();
    private static final Map<String, Long> LAST_ERRORS = new HashMap<>();

    private SidebarIntegration() {}

    public static synchronized void install(XposedModule module, ClassLoader loader) {
        if (module == null || loader == null) return;
        final UiApi api;
        try {
            api = new UiApi(loader);
        } catch (Throwable error) {
            failure("load native preferences", error);
            return;
        }
        installFragment(module, loader, api, api.plan.mainFragment.getName(), true);
        installFragment(module, loader, api,
                "com.oplus.smartsidebar.settings.FloatBarSettingsFragment", false);
        installFragment(module, loader, api,
                "com.oplus.smartsidebar.settings.FloatBarSettingsWithoutGestureFragment", false);
    }

    private static void installFragment(XposedModule module, ClassLoader loader, UiApi api,
            String name, boolean mainPage) {
        try {
            Class<?> type = loader.loadClass(name);
            if (HOOKED.contains(type)) return;
            Method create = type.getDeclaredMethod("onCreate", Bundle.class);
            Method resume = type.getDeclaredMethod("onResume");
            Method destroy = type.getDeclaredMethod("onDestroy");
            module.hook(create).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object fragment = chain.getThisObject();
                        Page page = addPage(fragment, api, mainPage);
                        if (page != null) {
                            synchronized (PAGES) { PAGES.put(fragment, page); }
                            query(page);
                        }
                    } catch (Throwable error) {
                        failure("add " + name, error);
                    }
                    return result;
                }
            });
            module.hook(resume).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Page page;
                        synchronized (PAGES) { page = PAGES.get(chain.getThisObject()); }
                        if (page != null) query(page);
                    } catch (Throwable error) {
                        failure("refresh " + name, error);
                    }
                    return result;
                }
            });
            module.hook(destroy).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Page page;
                        synchronized (PAGES) { page = PAGES.remove(chain.getThisObject()); }
                        if (page != null) { page.closed = true; page.generation++; }
                    } catch (Throwable error) {
                        failure("release " + name, error);
                    }
                    return chain.proceed();
                }
            });
            if (mainPage) {
                Method updateSidebarGroups = api.plan.method(type, "X");
                module.hook(updateSidebarGroups).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            Page page;
                            synchronized (PAGES) { page = PAGES.get(chain.getThisObject()); }
                            if (page != null) refreshFallback(page);
                        } catch (Throwable error) {
                            failure("refresh float settings entry", error);
                        }
                        return result;
                    }
                });
            }
            HOOKED.add(type);
            Log.i(TAG, "Installed card preferences in " + name);
        } catch (Throwable error) {
            failure("hook " + name, error);
        }
    }

    private static Page addPage(Object fragment, UiApi api, boolean mainPage) throws Exception {
        if (NativeFloatSettings.isCardPage(fragment)) return null;
        Object root = api.plan.method(fragment.getClass(), "a")
                .invoke(fragment, mainPage ? "key_root" : "root_pfs");
        if (root == null) return null;
        Context context = null;
        try {
            context = (Context) method(fragment.getClass(), "getContext").invoke(fragment);
        } catch (ReflectiveOperationException ignored) {
            // Fragment.getContext() exists in this APK; use the root preference as fallback.
        }
        if (context == null) context = (Context) api.context.invoke(root);
        if (context == null || !SIDEBAR.equals(context.getPackageName())) return null;
        if (api.find.invoke(root, CATEGORY_KEY) != null) return null;

        Object category = api.categoryType.getConstructor(Context.class, AttributeSet.class)
                .newInstance(context, null);
        // Keep the new group above the native bottom spacer, beside the related controls.
        api.configure(category, CATEGORY_KEY, mainPage ? null : "超级卡包", mainPage ? 2 : 5);
        // Native PreferenceGroup expects its manager before children are attached.
        api.add.invoke(root, category);
        Object toggle = null;
        Object area = null;
        Object details = null;
        Object detailsCategory = null;
        Object fallback = null;
        if (mainPage) {
            toggle = api.switchType.getConstructor(Context.class).newInstance(context);
            api.configure(toggle, SWITCH_KEY, "超级卡包", 0);
            api.summary.invoke(toggle, DESCRIPTION);
            api.checked.invoke(toggle, false);
            api.add.invoke(category, toggle);
            fallback = api.preferenceType.getConstructor(Context.class).newInstance(context);
            api.configure(fallback, FLOAT_SETTINGS_KEY, "浮标设置", 1);
            api.visible.invoke(fallback, false);
            api.add.invoke(category, fallback);
        } else {
            area = api.jumpType.getConstructor(Context.class).newInstance(context);
            api.configure(area, AREA_KEY, "呼出位置和范围", 0);
            api.summary.invoke(area, "屏幕右侧范围可调");
            api.add.invoke(category, area);
            // Match native single-entry groups: the empty category supplies COUI's
            // resource-defined spacing and each JumpPreference gets its own rounded card.
            detailsCategory = api.categoryType.getConstructor(Context.class, AttributeSet.class)
                    .newInstance(context, null);
            api.configure(detailsCategory, DETAILS_CATEGORY_KEY, "", 5);
            api.visible.invoke(detailsCategory, false);
            api.add.invoke(root, detailsCategory);
            details = api.jumpType.getConstructor(Context.class).newInstance(context);
            api.configure(details, DETAILS_KEY, "卡包详细设置", 1);
            api.add.invoke(detailsCategory, details);
            // Wait for the configuration snapshot before exposing card controls.
            api.visible.invoke(category, false);
        }

        Page page = new Page(api, context, root, category, toggle, area, fallback, detailsCategory);
        if (toggle != null) {
            Object listener = Proxy.newProxyInstance(api.loader,
                    new Class<?>[]{api.changeListenerType}, (proxy, called, arguments) -> {
                        Object objectResult = objectMethod(proxy, called, arguments);
                        if (called.getDeclaringClass() == Object.class) return objectResult;
                        try {
                            if (called.getReturnType() == boolean.class && called.getParameterCount() == 2 && arguments != null
                                    && arguments.length == 2 && arguments[1] instanceof Boolean) {
                                saveEnabled(page, (Boolean) arguments[1]);
                            }
                        } catch (Throwable error) {
                            failure("toggle callback", error);
                            toast(page, "超级卡包设置失败");
                        }
                        // Apply only the authoritative success snapshot, asynchronously.
                        return false;
                    });
            api.change.invoke(toggle, listener);
        }
        if (area != null) setClick(api, area, page, SIDEBAR,
                "com.oplus.smartsidebar.settings.FloatBarSettingsPhoneActivity");
        if (details != null) setClick(api, details, page, "dev.local.supercardhost",
                "com.vivo.card.setting.CardSettingActivity");
        if (fallback != null) setClick(api, fallback, page, SIDEBAR,
                "com.oplus.smartsidebar.settings.FloatBarSettingsActivity");
        return page;
    }

    private static void setClick(UiApi api, Object preference, Page page,
            String packageName, String className) throws Exception {
        Object listener = Proxy.newProxyInstance(api.loader,
                new Class<?>[]{api.clickListenerType}, (proxy, called, arguments) -> {
                    Object objectResult = objectMethod(proxy, called, arguments);
                    if (called.getDeclaringClass() == Object.class) return objectResult;
                    try {
                        if (called.getReturnType() == boolean.class && called.getParameterCount() == 1 && !page.closed) {
                            Context context = page.context.get();
                            if (context != null) {
                                Intent intent = new Intent().setClassName(packageName, className);
                                if (className.equals("com.oplus.smartsidebar.settings.FloatBarSettingsPhoneActivity")
                                        || className.equals("com.oplus.smartsidebar.settings.FloatBarSettingsActivity")) {
                                    intent.putExtra("dev.local.supercardhost.CARD_FLOATBAR_MODE", true);
                                    if (!NativeFloatSettings.isReady()) intent.setClassName("dev.local.supercardhost",
                                            "dev.local.supercardhost.GestureAreaActivity");
                                }
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(intent);
                            }
                        }
                    } catch (Throwable error) {
                        failure("open " + className, error);
                        toast(page, "暂时无法打开此设置");
                    }
                    return true;
                });
        api.click.invoke(preference, listener);
    }

    private static void query(Page page) {
        if (page.closed || page.saving) return;
        Context context = page.context.get();
        if (context == null) return;
        int generation = ++page.generation;
        try {
            CardConfiguration.request(context, "query", null, (state, error) -> onMain(() -> {
                if (page.closed || generation != page.generation) return;
                if (error == null && state != null) {
                    apply(page, state);
                } else {
                    failure("query configuration", new IllegalStateException(error));
                    if (!page.queryErrorShown) {
                        page.queryErrorShown = true;
                        toast(page, error == null ? "未能读取超级卡包设置" : error);
                    }
                }
            }));
        } catch (Throwable error) {
            failure("send query", error);
            toast(page, "未能读取超级卡包设置");
        }
    }

    private static void saveEnabled(Page page, boolean enabled) {
        if (page.closed || page.saving) return;
        Context context = page.context.get();
        if (context == null) return;
        page.saving = true;
        int generation = ++page.generation;
        Bundle values = new Bundle();
        values.putBoolean("value", enabled);
        try {
            CardConfiguration.request(context, "enabled", values, (state, error) -> onMain(() -> {
                if (page.closed || generation != page.generation) return;
                page.saving = false;
                if (error == null && state != null) {
                    apply(page, state);
                } else {
                    toast(page, error == null ? "超级卡包设置未保存" : error);
                    query(page);
                }
            }));
        } catch (Throwable error) {
            page.saving = false;
            failure("save enabled", error);
            toast(page, "超级卡包设置未保存");
        }
    }

    private static void apply(Page page, Bundle state) {
        if (page.closed) return;
        try {
            boolean enabled = state.getBoolean("enabled", false);
            page.lastEnabled = enabled;
            Object toggle = page.toggle.get();
            if (toggle != null) page.api.checked.invoke(toggle, enabled);
            Object category = page.category.get();
            if (category != null && !page.mainPage) page.api.visible.invoke(category, enabled);
            Object detailsCategory = page.detailsCategory.get();
            if (detailsCategory != null) page.api.visible.invoke(detailsCategory, enabled);
            Object area = page.area.get();
            if (area != null) {
                float top = state.getFloat("top", .76f);
                float bottom = state.getFloat("bottom", .98f);
                if (!Float.isFinite(top) || !Float.isFinite(bottom)
                        || top < 0 || bottom > 1 || bottom <= top) {
                    top = .76f; bottom = .98f;
                }
                page.api.summary.invoke(area, "屏幕右侧 " + Math.round(top * 100)
                        + "%–" + Math.round(bottom * 100) + "% 可调");
            }
            page.queryErrorShown = false;
            refreshFallback(page);
        } catch (Throwable error) {
            failure("render configuration", error);
        }
    }

    private static void refreshFallback(Page page) {
        if (page.closed || !page.mainPage) return;
        try {
            Object root = page.root.get();
            Object fallback = page.fallback.get();
            if (root == null || fallback == null) return;
            boolean nativeEntryPresent = page.api.find.invoke(root, "key_style_category") != null;
            page.api.visible.invoke(fallback, page.lastEnabled && !nativeEntryPresent);
        } catch (Throwable error) {
            failure("refresh float settings entry", error);
        }
    }

    private static Object objectMethod(Object proxy, Method called, Object[] arguments) {
        if (called.getDeclaringClass() != Object.class) return null;
        if ("hashCode".equals(called.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(called.getName())) {
            return arguments != null && arguments.length == 1 && proxy == arguments[0];
        }
        return "SuperCardSidebarListener";
    }

    private static void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else MAIN.post(action);
    }

    private static void toast(Page page, String message) {
        onMain(() -> {
            try {
                Context context = page.context.get();
                if (!page.closed && context != null) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable error) { failure("toast", error); }
        });
    }

    private static void failure(String operation, Throwable error) {
        synchronized (LAST_ERRORS) {
            long now = SystemClock.elapsedRealtime();
            Long previous = LAST_ERRORS.get(operation);
            if (previous != null && now - previous < 30_000) return;
            LAST_ERRORS.put(operation, now);
        }
        Log.e(TAG, operation, error);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method result = current.getDeclaredMethod(name, parameters);
                result.setAccessible(true);
                return result;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static final class UiApi {
        final SidebarDexResolver.Plan plan;
        final ClassLoader loader;
        final Class<?> preferenceType, categoryType, switchType, jumpType;
        final Class<?> changeListenerType, clickListenerType;
        final Field key, persistent;
        final Method context, add, find, title, summary, order, visible, checked, change, click;

        UiApi(ClassLoader loader) throws Exception {
            this.loader = loader;
            plan = SidebarDexResolver.resolveUi(loader);
            preferenceType = loader.loadClass("androidx.preference.Preference");
            categoryType = loader.loadClass("com.coui.appcompat.preference.COUIPreferenceCategory");
            switchType = loader.loadClass("com.coui.appcompat.preference.COUISwitchPreference");
            jumpType = loader.loadClass("com.coui.appcompat.preference.COUIJumpPreference");
            changeListenerType = plan.changeListener;
            clickListenerType = plan.clickListener;
            Class<?> group = loader.loadClass("androidx.preference.PreferenceGroup");
            key = plan.field(preferenceType, "key");
            persistent = plan.field(preferenceType, "persistent");
            context = plan.method(preferenceType, "u");
            add = plan.method(group, "W0");
            find = plan.method(group, "X0");
            title = plan.method(preferenceType, "O0");
            summary = plan.method(preferenceType, "L0");
            order = plan.method(preferenceType, "I0");
            visible = plan.method(preferenceType, "P0");
            checked = plan.method(switchType, "W0");
            change = plan.method(preferenceType, "G0");
            click = plan.method(preferenceType, "H0");
        }

        void configure(Object preference, String uniqueKey, String caption, int position)
                throws Exception {
            key.set(preference, uniqueKey);
            persistent.setBoolean(preference, false);
            if (caption != null) title.invoke(preference, caption);
            order.invoke(preference, position);
        }
    }

    private static final class Page {
        final UiApi api;
        final WeakReference<Context> context;
        final WeakReference<Object> root, category, toggle, area, fallback, detailsCategory;
        final boolean mainPage;
        boolean closed, saving, queryErrorShown, lastEnabled;
        int generation;

        Page(UiApi api, Context context, Object root, Object category, Object toggle, Object area,
                Object fallback, Object detailsCategory) {
            this.api = api;
            this.context = new WeakReference<>(context);
            this.root = new WeakReference<>(root);
            this.category = new WeakReference<>(category);
            this.toggle = new WeakReference<>(toggle);
            this.area = new WeakReference<>(area);
            this.fallback = new WeakReference<>(fallback);
            this.detailsCategory = new WeakReference<>(detailsCategory);
            mainPage = toggle != null;
        }
    }
}
