package dev.local.supercardhost;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/** Owns one original vivo plugin instance inside the selected host process. */
public final class PluginRuntime {
    private static final String TAG = "SuperCardRuntime";
    private static Object plugin;
    private static boolean attempted;
    public static boolean systemHost;
    private static Boolean lastLocked;
    private static Boolean lastShowing;
    private static Boolean lastBouncer;
    private static android.view.View authWindow;
    private static int authFlags;
    private static float authAlpha;
    private static android.view.View securePaymentWindow;
    private static boolean paymentWindowWasSecure;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static synchronized void start(Application host, Context module) {
        if (attempted) return;
        attempted = true;
        systemHost = "com.android.systemui".equals(host.getPackageName());
        try {
            HostSettingsDefaults.ensure(host);
            Log.i(TAG, "Starting original vivo plugin in " + host.getPackageName());
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/Context;", "Landroid/view/", "Landroid/hardware/input/",
                "Landroid/app/", "Landroid/os/", "Landroid/content/res/", "Landroid/graphics/",
                "Landroid/window/", "Landroid/hardware/display/");
            if (systemHost) RootDisplayClient.install(host);
            if (systemHost) MemoryTransferConnection.install(host);
            Context resources = SuperCardArchiveResources.open(host, module);
            File directory = new File(host.getCodeCacheDir(), "vivo_supercard");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create code directory");
            File code = new File(directory, "supercard-code.zip");
            if (code.exists() && !code.delete()) throw new java.io.IOException("Cannot replace code");
            try (var in = module.getAssets().open("supercard-code.zip"); var out = new java.io.FileOutputStream(code)) {
                if (!code.setReadOnly()) throw new java.io.IOException("Cannot protect code");
                in.transferTo(out);
            }
            ClassLoader loader = new dalvik.system.PathClassLoader(code.getAbsolutePath(),
                resources.getApplicationInfo().nativeLibraryDir, PluginRuntime.class.getClassLoader());
            Context card = new PluginContext(resources, host, loader);
            HostSettingsDefaults.install(VectorEntry.current, loader);
            MemoryPluginLoader.install(VectorEntry.current, host, loader, memoryLoader -> {
                MemoryCommitLifecycle.install(VectorEntry.current, host, memoryLoader);
                MemoryVoiceLimit.install(VectorEntry.current, memoryLoader);
                if (!MemoryRuntimeBridge.install(VectorEntry.current, host, memoryLoader)) {
                    throw new IllegalStateException("Unable to attach original memory backend");
                }
            });
            LensRuntimeBridge.install(VectorEntry.current, loader);
            try { installCoupon(VectorEntry.current, host, loader); }
            catch (Throwable error) { Log.e("SuperCardCoupon", "Coupon adapter unavailable", error); }
            if (systemHost) LensRuntimeBridge.prepareConfiguration(host);
            Class<?> type = loader.loadClass("com.vivo.card.service.CardService");
            Object instance = type.getConstructor().newInstance();
            plugin = instance;
            PayRuntimeBridge.install(VectorEntry.current, host, loader, instance);
            GestureConfigBridge.install(VectorEntry.current, host, loader, instance);
            KeyguardBridge.install(VectorEntry.current, host, loader,
                PluginRuntime::suspendOverlayForAuthentication,
                () -> { updateState(host); restoreOverlayAfterAuthentication(); });
            type.getMethod("onCreate", Context.class, Context.class).invoke(instance, host, card);
            CardConfiguration.install(host);
            GestureHandle.install(host, loader, instance);
            updateState(host);
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    updateState(host);
                    MAIN.postDelayed(this, 750);
                }
            }, 750);
            IntentFilter filter = new IntentFilter("dev.local.supercardhost.DEBUG");
            // Shell diagnostics only; ordinary apps cannot send this receiver commands.
            host.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    String command = intent.getStringExtra("command");
                    if ("show".equals(command)) show();
                    else if ("hide".equals(command)) hide();
                    else if ("memoryCheck".equals(command)) new Thread(() -> {
                        android.os.Bundle result = MemorySaveClient.checkReady(host, new android.os.Bundle());
                        Log.i("SuperCardMemorySave", "Backend readiness=" + (result == null ? "ready" : result));
                    }, "SuperCardMemoryCheck").start();
                    else dumpState();
                }
            }, filter, "android.permission.DUMP", MAIN, Context.RECEIVER_EXPORTED);
            IntentFilter state = new IntentFilter();
            state.addAction(Intent.ACTION_SCREEN_OFF); state.addAction(Intent.ACTION_USER_PRESENT);
            state.addAction(Intent.ACTION_SCREEN_ON);
            host.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) hide();
                    updateState(host);
                    if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                            || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) GestureHandle.reveal();
                }
            }, state, Context.RECEIVER_EXPORTED);
            MAIN.postDelayed(PluginRuntime::dumpState, 2000);
            Log.i(TAG, "Original CardService initialized; diagnostic receiver registered");
        } catch (Throwable error) { failure("initialize", error); }
    }

    static void installCoupon(io.github.libxposed.api.XposedModule module,
            Application host, ClassLoader loader) throws Exception {
        CouponPluginLoader.install(module, host, loader, couponLoader -> {
            try { CouponRuntimeBridge.install(module, host, couponLoader); }
            catch (Exception error) { throw new IllegalStateException("Coupon data adapter initialization failed", error); }
        });
        CouponCardConfiguration.install(module, loader);
        CouponRefreshCoordinator.install(module, host, loader);
    }

    private static void updateState(Application host) {
        try {
            GestureConfigBridge.refresh();
            GestureHandle.refresh();
            boolean locked = host.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
            boolean[] nativeState = systemHost ? SystemUiBridge.keyguardState(host, locked) : new boolean[]{locked, false};
            boolean showing = nativeState[0], bouncer = nativeState[1];
            if (plugin == null || (lastLocked != null && lastLocked == locked && lastShowing != null && lastShowing == showing
                    && lastBouncer != null && lastBouncer == bouncer)) return;
            Class<?> managerType = plugin.getClass().getClassLoader().loadClass("com.vivo.card.framework.keyguard.CardKeyguardManager");
            Object manager = managerType.getMethod("get").invoke(null);
            Object state = managerType.getMethod("getKeyguardState").invoke(manager);
            state.getClass().getMethod("setKeyguardLocked", boolean.class).invoke(state, locked);
            state.getClass().getMethod("setKeyguardShowing", boolean.class).invoke(state, showing);
            plugin.getClass().getMethod("onGlobalStateChange", boolean.class,
                boolean.class, boolean.class, boolean.class).invoke(plugin,
                locked, false, false, false);
            Class<?> globalType = plugin.getClass().getClassLoader().loadClass("com.vivo.card.common.global.GlobalStateMonitor");
            Object global = globalType.getMethod("getInstance", Context.class).invoke(null, host);
            Object current = globalType.getMethod("getCurrentGlobalState").invoke(global);
            Object copy = current.getClass().getMethod("copy").invoke(current);
            copy.getClass().getField("mIsKeyguardLocked").setBoolean(copy, locked);
            copy.getClass().getField("mIsKeyguardShowing").setBoolean(copy, showing);
            copy.getClass().getField("mIsPasswordInputShowing").setBoolean(copy, bouncer);
            var apply = globalType.getDeclaredMethod("applyGlobalState", copy.getClass(), boolean.class);
            apply.setAccessible(true); apply.invoke(global, copy, false);
            Class<?> regionType = plugin.getClass().getClassLoader().loadClass("com.vivo.card.framework.gesture.CardGestureRegionHelper");
            Field instance = regionType.getDeclaredField("instance"); instance.setAccessible(true);
            Object region = instance.get(null);
            if (region != null) regionType.getMethod("updateKeyguardShowing", boolean.class).invoke(region, showing);
            lastLocked = locked;
            lastShowing = showing;
            lastBouncer = bouncer;
            Log.i(TAG, "ColorOS keyguard state synchronized: locked=" + locked + ", showing=" + showing + ", bouncer=" + bouncer);
        } catch (Throwable error) { failure("state", error); }
    }

    public static void show() {
        try {
            Object impl = field(plugin, "cardServiceImpl");
            impl.getClass().getMethod("onCardTriggerIntent", String.class, String.class, int.class, String.class)
                .invoke(impl, "intent_trigger", "paycard", 0, null);
            Log.i(TAG, "Original show requested");
            dumpState();
        } catch (Throwable error) { failure("show", error); }
    }

    public static void hide() {
        try { if (plugin != null) plugin.getClass().getMethod("removeCard").invoke(plugin); }
        catch (Throwable error) { failure("hide", error); }
    }

    /** Retain secure app content protection after composition into the card's TextureView. */
    public static void setPaymentSecure(boolean secure) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> setPaymentSecure(secure)); return;
        }
        try {
            android.view.View window = securePaymentWindow;
            if (secure) {
                Object coordinator = field(field(plugin, "cardServiceImpl"), "cardCoordinator");
                Object manager = field(coordinator, "windowManager");
                window = (android.view.View) manager.getClass().getMethod("getWindowView").invoke(manager);
                if (window == null || !window.isAttachedToWindow()) throw new IllegalStateException("Payment card window missing");
                if (securePaymentWindow == window) return;
                android.view.WindowManager.LayoutParams params = (android.view.WindowManager.LayoutParams) window.getLayoutParams();
                paymentWindowWasSecure = (params.flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0;
                params.flags |= android.view.WindowManager.LayoutParams.FLAG_SECURE;
                securePaymentWindow = window;
            } else {
                securePaymentWindow = null;
                if (window == null || !window.isAttachedToWindow() || paymentWindowWasSecure) return;
                ((android.view.WindowManager.LayoutParams) window.getLayoutParams()).flags &= ~android.view.WindowManager.LayoutParams.FLAG_SECURE;
            }
            ((android.view.WindowManager) window.getContext().getSystemService(Context.WINDOW_SERVICE))
                    .updateViewLayout(window, window.getLayoutParams());
        } catch (Throwable error) { failure("payment secure window", error); }
    }

    private static void suspendOverlayForAuthentication() {
        try {
            if (authWindow != null) return;
            Object coordinator = field(field(plugin, "cardServiceImpl"), "cardCoordinator");
            Object windowManager = field(coordinator, "windowManager");
            android.view.View window = (android.view.View) windowManager.getClass().getMethod("getWindowView").invoke(windowManager);
            if (window == null || !window.isAttachedToWindow()) return;
            android.view.WindowManager.LayoutParams params = (android.view.WindowManager.LayoutParams) window.getLayoutParams();
            authFlags = params.flags; authAlpha = window.getAlpha(); authWindow = window;
            params.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            window.setAlpha(0);
            ((android.view.WindowManager) window.getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(window, params);
        } catch (Throwable error) { restoreOverlayAfterAuthentication(); failure("suspend for authentication", error); }
    }

    private static void restoreOverlayAfterAuthentication() {
        android.view.View window = authWindow; authWindow = null;
        if (window == null) return;
        try {
            if (window.isAttachedToWindow()) {
                android.view.WindowManager.LayoutParams params = (android.view.WindowManager.LayoutParams) window.getLayoutParams();
                params.flags = authFlags; window.setAlpha(authAlpha);
                ((android.view.WindowManager) window.getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(window, params);
            }
        } catch (Throwable error) { failure("restore after authentication", error); }
    }

    private static Object field(Object target, String name) throws Exception {
        if (target == null) throw new IllegalStateException("Plugin not initialized");
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }

    public static void dumpState() {
        try {
            Object impl = field(plugin, "cardServiceImpl");
            Object manager = field(impl, "cardManager");
            Log.i(TAG, "CardManager registered=" + field(manager, "registeredCardTypes"));
        } catch (Throwable error) { failure("diagnostic", error); }
    }

    private static void failure(String stage, Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        Log.e(TAG, stage + " failed", error);
    }
}
