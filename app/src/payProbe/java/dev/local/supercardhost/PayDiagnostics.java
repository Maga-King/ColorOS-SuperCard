package dev.local.supercardhost;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedModule;

/** Included only with -PpayProbe=true. Never writes test entries to saved settings. */
final class PayDiagnostics {
    private static final String TYPE = "supercard_probe";
    private static final String PACKAGE = "dev.local.supercardprobe";
    private static final String LABEL = "supercard_probe_label";
    private static final String TAG = "SuperCardPayProbe";

    static boolean isTestPackage(String name) { return PACKAGE.equals(name); }

    static void install(XposedModule module, Application host, ClassLoader loader) throws Exception {
        Class<?> view = loader.loadClass("com.vivo.card.cards.cardpay.PayCardView");
        Class<?> bean = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean$FunctionBean");
        Method getType = bean.getMethod("getType");
        // Feed both the view and its click handler through the same StateFlow.
        // Appending only in PayCardView.setData would leave the handler's list shorter.
        Class<?> collector = loader.loadClass(
                "com.vivo.card.cards.cardpay.viewmodel.PayCardViewModel$observeData$2$1");
        Method emit = method(collector, "emit", List.class,
                loader.loadClass("kotlin.coroutines.Continuation"));
        module.hook(emit).intercept(chain -> {
            List<?> original = (List<?>) chain.getArg(0);
            for (Object entry : original) if (TYPE.equals(getType.invoke(entry))) return chain.proceed();
            Object probe = bean.getConstructor().newInstance();
            // A fresh test device may have zero supported payment functions.
            // Supply a complete native icon definition even for an empty input list.
            String[][] icons = {{"IconResId", "ic_wx_scan_normal"},
                    {"IconResId2", "ic_wx_scan_sel"}, {"IconResId3", "ic_wx_scan_sel_small"},
                    {"Icon_bg", "bg_pay_wxscan"}, {"Icon_set", "ic_wx_scan_set"},
                    {"Icon_mini", "ic_wx_scan_small"}, {"Info", ""}};
            for (String[] icon : icons)
                bean.getMethod("set" + icon[0], String.class).invoke(probe, icon[1]);
            bean.getMethod("setType", String.class).invoke(probe, TYPE);
            bean.getMethod("setType_name", String.class).invoke(probe, "虚拟屏测试");
            bean.getMethod("setNameResId", String.class).invoke(probe, LABEL);
            bean.getMethod("setNameResId2", String.class).invoke(probe, LABEL);
            bean.getMethod("setEnable", boolean.class).invoke(probe, true);
            bean.getMethod("setSupport", boolean.class).invoke(probe, true);
            ArrayList<Object> entries = new ArrayList<>(original);
            entries.add(probe);
            Object[] args = chain.getArgs().toArray();
            args[0] = entries;
            return chain.proceed(args);
        });
        Class<?> util = loader.loadClass("com.vivo.card.utils.CardUtil");
        module.hook(method(util, "getStringByResName", Context.class, String.class))
                .intercept(chain -> LABEL.equals(chain.getArg(1)) ? "虚拟屏测试" : chain.proceed());
        module.hook(method(util, "getOpenTypePackageName", String.class))
                .intercept(chain -> TYPE.equals(chain.getArg(0)) ? PACKAGE : chain.proceed());
        module.hook(method(util, "isAppUnInstalled", Context.class, String.class)).intercept(chain -> {
            if (!TYPE.equals(chain.getArg(1))) return chain.proceed();
            try { host.getPackageManager().getApplicationInfo(PACKAGE, 0); return false; }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) { return true; }
        });
        Class<?> builder = loader.loadClass("com.vivo.card.cards.cardpay.virtual.PayCardIntentBuilder");
        module.hook(method(builder, "buildPaymentIntent", Context.class, String.class))
                .intercept(chain -> TYPE.equals(chain.getArg(1)) ? intent() : chain.proceed());
        // Preserve the original fullscreen fallback and make it observable with the same target.
        Class<?> switcher = loader.loadClass("com.vivo.card.cards.cardpay.switcher.SwitcherUtil");
        module.hook(method(switcher, "openPayCardScene", Context.class, String.class,
                boolean.class, int.class)).intercept(chain -> {
            if (!TYPE.equals(chain.getArg(1))) return chain.proceed();
            Log.w(TAG, "Original fullscreen path invoked; launching probe on display=0");
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(0);
            host.startActivity(intent(), options.toBundle());
            return null;
        });
        Class<?> constants = loader.loadClass("com.vivo.card.utils.CardTypeConstant");
        // Define the probe's known destination pages as the original does for payment pages.
        for (String name : new String[]{"isNoInterceptActivity", "isReachObjectActivity"}) {
            module.hook(method(constants, name, String.class)).intercept(chain -> {
                String activity = (String) chain.getArg(0);
                return (PACKAGE + ".ProbeActivity").equals(activity)
                        || (PACKAGE + ".NextActivity").equals(activity) ? true : chain.proceed();
            });
        }
        module.hook(method(view, "launchVirtualDisplayPayment", String.class, Intent.class))
                .intercept(chain -> {
                    Log.i(TAG, "PAY request type=" + chain.getArg(0)
                            + " backendReady=" + RootDisplayClient.isReady());
                    return chain.proceed();
                });
        module.hook(method(view, "bottomUpRunnable$lambda$0", view)).intercept(chain -> {
            Log.w(TAG, "Original 4s fallback timer fired; backendReady=" + RootDisplayClient.isReady()
                    + " activeDisplay=" + PayRuntimeBridge.getActiveDisplayId());
            return chain.proceed();
        });
        Log.i(TAG, "PAY probe entry installed in " + host.getPackageName());
    }

    private static Intent intent() {
        return new Intent(Intent.ACTION_MAIN).setClassName(PACKAGE, PACKAGE + ".ProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method result = type.getDeclaredMethod(name, args);
        result.setAccessible(true);
        return result;
    }
}
