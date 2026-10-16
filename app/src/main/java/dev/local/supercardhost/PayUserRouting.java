package dev.local.supercardhost;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/** Explicit PAY user + display, including the WeChat SDK's provider calls. */
final class PayUserRouting {
    private static final String TAG = "SuperCardPayUser";
    private static final Object BUS_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastFailure;
    static boolean ready;

    private PayUserRouting() { }

    static void install(XposedModule module, Application host, ClassLoader loader) throws Exception {
        Class<?> sdk = loader.loadClass("com.vivo.card.cards.cardpay.virtual.PayCardSDKLauncher");
        Method launch = method(sdk, "launchWeChatPay", Context.class, String.class);
        Method background = method(sdk, "launchWeChatBusCode$lambda$6", Context.class);
        Method foreground = method(sdk, "launchWeChatBusCodeOnMain", Context.class);
        Field busApi = sdk.getDeclaredField("wxBusApi");
        busApi.setAccessible(true);
        Object sdkInstance = sdk.getField("INSTANCE").get(null);
        Class<?> builder = loader.loadClass("com.vivo.card.cards.cardpay.virtual.PayCardIntentBuilder");
        Object intentBuilder = builder.getField("INSTANCE").get(null);
        Method buildIntent = method(builder, "buildPaymentIntent", Context.class, String.class);
        Method oldScene = method(loader.loadClass("com.vivo.card.cards.cardpay.switcher.SwitcherUtil"),
                "openPayCardScene", Context.class, String.class, boolean.class, int.class);
        // Both leaf methods are synchronous: serialize creation + sendReq as one operation.
        // A new API for each request prevents reuse of the previous user's resolver/display.
        for (Method bus : new Method[]{background, foreground}) module.hook(bus).intercept(chain -> {
            synchronized (BUS_LOCK) {
                busApi.set(null, null);
                try { return chain.proceed(); }
                finally { busApi.set(null, null); }
            }
        });
        module.hook(launch).intercept(chain -> {
            String function = (String) chain.getArg(1);
            if (!PayUserSettings.WECHAT.equals(PayUserSettings.appForFunction(function)))
                return chain.proceed();
            UserContext context;
            try {
                Context original = (Context) chain.getArg(0);
                context = original instanceof UserContext ? (UserContext) original
                        : new UserContext(original, host, PayRuntimeBridge.userForFunction(host, function),
                                PayRuntimeBridge.getActiveDisplayId());
            } catch (Exception error) {
                failed(host, error);
                return false;
            }
            // The explicit clone tiles keep their chosen clone in UserContext. Bypass only
            // vivo's unavailable getDoubleAppUserId/startActivityAsUser path, not their intent.
            String routedFunction = function.startsWith("wx2_") ? "wx_" + function.substring(4) : function;
            return chain.proceed(new Object[]{context, routedFunction});
        });
        // The OEM timeout/fullscreen path uses separate old switchers (and a separate
        // cached WeChat API). Reuse its current SDK/intent builder with explicit display 0.
        module.hook(oldScene).intercept(chain -> {
            String function = (String) chain.getArg(1);
            String app = PayUserSettings.appForFunction(function);
            if (app == null) return chain.proceed();
            try {
                Context original = (Context) chain.getArg(0);
                int user = PayRuntimeBridge.userForFunction(host, function);
                if (PayUserSettings.WECHAT.equals(app)) {
                    launch.invoke(sdkInstance, new UserContext(original, host, user, 0), function);
                } else {
                    Intent intent = (Intent) buildIntent.invoke(intentBuilder, original, function);
                    ActivityOptions full = ActivityOptions.makeBasic();
                    full.setLaunchDisplayId(0);
                    start(host, intent, full.toBundle(), app, user, 0);
                }
            } catch (Exception error) { failed(host, error); }
            return null;
        });
        ready = true;
        Log.i(TAG, "PAY user routing installed");
    }

    static boolean tryStart(Context host, Intent intent, Bundle options) {
        String app = appForIntent(intent, PayRuntimeBridge.getWeChatRideDisplayId() > 0);
        if (app == null) return false;
        try {
            int user = PayRuntimeBridge.userForApp(host, app);
            start(host, intent, options, app, user, PayRuntimeBridge.getActiveDisplayId());
        } catch (Exception error) { failed(host, error); }
        // A failed selected user must never fall through to the system's default account.
        return true;
    }

    static String appForIntent(Intent intent, boolean rideLauncher) {
        String pkg = intent.getComponent() == null ? intent.getPackage()
                : intent.getComponent().getPackageName();
        if ("com.tencent.mm".equals(pkg)
                && ("com.tencent.mm.ui.ShortCutDispatchAction".equals(intent.getAction())
                || "com.tencent.mm.action.BIZSHORTCUT".equals(intent.getAction())
                || (rideLauncher && Intent.ACTION_MAIN.equals(intent.getAction()))))
            return PayUserSettings.WECHAT;
        if (pkg != null && !"com.eg.android.AlipayGphone".equals(pkg)) return null;
        android.net.Uri uri = intent.getData();
        if (uri == null || !"alipays".equals(uri.getScheme())
                || !"platformapi".equals(uri.getHost()) || !"/startapp".equals(uri.getPath())) return null;
        String appId = uri.getQueryParameter("appId");
        return "20000056".equals(appId) || "10000007".equals(appId)
                || "20002047".equals(appId) || "77700284".equals(appId) ? PayUserSettings.ALIPAY : null;
    }

    static Bundle displayOptions(Bundle options, int display) {
        // Explicit display 0 is used by the original fullscreen/authentication flow.
        if (options != null) return new Bundle(options);
        if (display < 0) return null;
        ActivityOptions routed = ActivityOptions.makeBasic();
        routed.setLaunchDisplayId(display);
        return routed.toBundle();
    }

    static void start(Context host, Intent source, Bundle options, String app, int user, int display)
            throws ReflectiveOperationException {
        String pkg = PayUserSettings.packageName(app);
        if (!PayUserSettings.installed(host, pkg, user)) throw PayUserSettings.unavailable(app);
        Intent intent = new Intent(source);
        intent.setPackage(pkg);
        skipChooser(intent);
        Bundle launchOptions = displayOptions(options, display);
        Context.class.getMethod("startActivityAsUser", Intent.class, Bundle.class, UserHandle.class)
                .invoke(host, intent, launchOptions, UserHandle.getUserHandleForUid(user * 100000));
        Log.i(TAG, "PAY launch app=" + app + " user=" + user);
    }

    private static void skipChooser(Intent intent) throws ReflectiveOperationException {
        Class<?> type;
        try { type = Class.forName("com.oplus.content.OplusIntent"); }
        catch (ClassNotFoundException nonOplusRom) { return; }
        try {
            int flags = (Integer) type.getMethod("getOplusFlags", Intent.class).invoke(null, intent);
            int skip = type.getField("OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER").getInt(null);
            type.getMethod("setOplusFlags", Intent.class, int.class).invoke(null, intent, flags | skip);
        } catch (NoSuchMethodException | NoSuchFieldException olderRom) {
            type.getMethod("setSkipMultiAppChooser", Intent.class, int.class).invoke(null, intent, 1);
        }
    }

    static void failed(Context host, Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        synchronized (BUS_LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (lastFailure != 0 && now - lastFailure < 5000) return;
            lastFailure = now;
        }
        Log.w(TAG, "Selected PAY user launch failed", error);
        String message = error instanceof IllegalStateException ? error.getMessage()
                : "所选用户启动失败，请检查卡包 PAY 设置";
        MAIN.post(() -> Toast.makeText(host, message, Toast.LENGTH_LONG).show());
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    /** Only passed to the payment SDK. Card settings retain the original main-user resolver. */
    private static final class UserContext extends ContextWrapper {
        private final Context host, userContext;
        private final int user, display;
        private final long generation;

        UserContext(Context base, Context host, int user, int display) throws ReflectiveOperationException {
            super(base);
            this.host = host; this.user = user; this.display = display;
            generation = PayRuntimeBridge.getLaunchGeneration();
            userContext = PayUserSettings.forUser(host, user);
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public ContentResolver getContentResolver() {
            checkActive();
            return userContext.getContentResolver();
        }
        @Override public PackageManager getPackageManager() { return userContext.getPackageManager(); }
        @Override public void startActivity(Intent intent) { startActivity(intent, null); }
        @Override public void startActivity(Intent intent, Bundle options) {
            if (!PayUserSettings.WECHAT.equals(appForIntent(intent, true))) {
                super.startActivity(intent, options);
                return;
            }
            try {
                // An asynchronous SDK job must not reopen a released/replaced virtual display.
                checkActive();
                start(host, intent, options, PayUserSettings.WECHAT, user, display);
            } catch (ReflectiveOperationException error) {
                failed(host, error);
                throw new IllegalStateException("微信所选用户启动失败", error);
            }
        }

        private void checkActive() {
            if (generation != PayRuntimeBridge.getLaunchGeneration()
                    || (display > 0 && display != PayRuntimeBridge.getActiveDisplayId()))
                throw new IllegalStateException("卡片已关闭，请重新打开");
        }
    }
}
