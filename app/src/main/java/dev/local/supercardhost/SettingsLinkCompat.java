package dev.local.supercardhost;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/** Reuses the original preference's colored, non-underlined clickable summary span. */
final class SettingsLinkCompat {
    private static final String TAG = "SuperCardSettings";
    private static final String KEY = "screen_slide_card_switch";
    private static final String LABEL = "智能侧边栏-超级卡包浮标设置";
    private static final AtomicBoolean OPENING = new AtomicBoolean();

    private SettingsLinkCompat() { }

    static void install(XposedModule module, ClassLoader target) {
        try {
            Class<?> preference = target.loadClass("androidx.preference.Preference");
            Class<?> function = target.loadClass("kotlin.jvm.functions.Function0");
            Method key = preference.getMethod("getKey");
            Method context = preference.getMethod("getContext");
            Object unit = target.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
            Method span = target.loadClass("com.vivo.card.utils.ExtensionsKt").getDeclaredMethod(
                    "setSpanSummaryExText", preference, String.class, String.class, int.class, function);
            span.setAccessible(true);
            module.hook(span).intercept(chain -> {
                Object[] args = null;
                try {
                    Object nativePreference = chain.getArg(0);
                    if (KEY.equals(key.invoke(nativePreference))) {
                        String original = (String) chain.getArg(1);
                        String keyword = (String) chain.getArg(2);
                        if (original != null && keyword != null && !keyword.isEmpty()
                                && original.contains(keyword)) {
                            args = chain.getArgs().toArray();
                            args[1] = original.replace(keyword, LABEL);
                            args[2] = LABEL;
                            args[4] = Proxy.newProxyInstance(target, new Class<?>[]{function}, (proxy, method, values) -> {
                                if (method.getName().equals("invoke") && method.getParameterCount() == 0) {
                                    try { open((Context) context.invoke(nativePreference)); }
                                    catch (Throwable failure) {
                                        Log.w(TAG, "Unable to resolve settings-link context", failure);
                                    }
                                    return unit;
                                }
                                if (method.getDeclaringClass() == Object.class) {
                                    switch (method.getName()) {
                                        case "toString": return "SuperCardFloatBarSettingsLink";
                                        case "hashCode": return System.identityHashCode(proxy);
                                        case "equals": return proxy == (values == null ? null : values[0]);
                                        default: break;
                                    }
                                }
                                throw new UnsupportedOperationException(method.toString());
                            });
                        }
                    }
                } catch (Throwable failure) {
                    // Keep original span construction if adaptation itself failed.
                    args = null;
                    Log.w(TAG, "Unable to adapt original float-bar settings link", failure);
                }
                return args == null ? chain.proceed() : chain.proceed(args);
            });
        } catch (Throwable failure) {
            Log.w(TAG, "Unable to attach original settings-link adapter", failure);
        }
    }

    private static void open(Context context) {
        if (context == null || !OPENING.compareAndSet(false, true)) return;
        try {
            CardConfiguration.request(context, "openFloatSettings", null, (state, error) -> {
                OPENING.set(false);
                if (error != null) fallback(context, error);
            });
        }
        catch (Throwable failure) {
            OPENING.set(false);
            Log.w(TAG, "Unable to request native float-bar settings", failure);
            fallback(context, "卡包浮标设置服务不可用");
        }
    }

    private static void fallback(Context context, String error) {
        Log.w(TAG, "Native float-bar settings unavailable: " + error);
        Intent fallback = new Intent().setClassName("dev.local.supercardhost",
                "dev.local.supercardhost.GestureAreaActivity");
        if (!(context instanceof Activity)) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(fallback);
            Toast.makeText(context, "已打开备用浮标设置", Toast.LENGTH_SHORT).show();
        } catch (Throwable failure) {
            Log.w(TAG, "Unable to open fallback float-bar settings", failure);
            Toast.makeText(context, "无法打开浮标设置", Toast.LENGTH_SHORT).show();
        }
    }
}
