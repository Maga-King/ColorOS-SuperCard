package dev.local.supercardhost;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/** Wait before the original launch and its four-second fullscreen fallback begin. */
final class PayDisplayReadiness {
    private static final String TAG = "SuperCardPayReady";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Pending pending;

    static void install(XposedModule module, Application host, ClassLoader loader) throws Exception {
        Class<?> type = loader.loadClass("com.vivo.card.cards.cardpay.PayCardView");
        Method launch = method(type, "launchVirtualDisplayPayment", String.class, Intent.class);
        Method fallback = method(type, "onBottomUpSchemeMultiLaunch", String.class);
        Method reset = method(type, "resetCard");
        var fallbackType = type.getDeclaredField("bottomUpSchemeMultiLaunchOpenType");
        fallbackType.setAccessible(true);
        module.hook(launch).intercept(chain -> {
            cancel();
            if (RootDisplayClient.isReady()) return chain.proceed();
            View view = (View) chain.getThisObject();
            String function = (String) chain.getArg(0);
            Intent intent = (Intent) chain.getArg(1);
            pending = new Pending(host, view, function, intent == null ? null : new Intent(intent),
                    launch, fallback, reset);
            RootDisplayClient.requestBackend();
            Toast.makeText(host, "正在准备卡片，请稍候", Toast.LENGTH_SHORT).show();
            MAIN.postDelayed(pending, 100);
            return null;
        });
        module.hook(fallback).intercept(chain -> {
            if (pending != null && pending.view == chain.getThisObject()) return null;
            return chain.proceed();
        });
        // No delayed launch can survive dismissing, detaching or resetting its card.
        for (String name : new String[]{"onCardPackHiding", "onDetachedFromWindow", "resetCard"}) {
            module.hook(method(type, name)).intercept(chain -> {
                if (pending != null && pending.view == chain.getThisObject()) cancel();
                return chain.proceed();
            });
        }
        // A backend death during launch must not trigger the original fullscreen fallback.
        module.hook(method(type, "bottomUpRunnable$lambda$0", type)).intercept(chain -> {
            String function = (String) fallbackType.get(chain.getArg(0));
            if (function != null && function.startsWith("wx"))
                PayRuntimeBridge.reportWeChatFallback(function);
            if (RootDisplayClient.isReady()) return chain.proceed();
            reset.invoke(chain.getArg(0));
            RootDisplayClient.requestBackend();
            Toast.makeText(host, "卡片暂未就绪，请重试", Toast.LENGTH_SHORT).show();
            return null;
        });
    }

    private static void cancel() {
        if (pending != null) MAIN.removeCallbacks(pending);
        pending = null;
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method result = type.getDeclaredMethod(name, args);
        result.setAccessible(true);
        return result;
    }

    private static final class Pending implements Runnable {
        final Application host;
        final View view;
        final String function;
        final Intent intent;
        final Method launch, fallback, reset;
        final long deadline = SystemClock.elapsedRealtime() + 10_000;

        Pending(Application host, View view, String function, Intent intent,
                Method launch, Method fallback, Method reset) {
            this.host = host; this.view = view; this.function = function; this.intent = intent;
            this.launch = launch; this.fallback = fallback; this.reset = reset;
        }

        @Override public void run() {
            if (pending != this) return;
            try {
                PowerManager power = host.getSystemService(PowerManager.class);
                KeyguardManager keyguard = host.getSystemService(KeyguardManager.class);
                if (!view.isShown() || !view.isAttachedToWindow() || !power.isInteractive()
                        || keyguard.isKeyguardLocked()) {
                    cancel();
                    reset.invoke(view);
                    return;
                }
                if (RootDisplayClient.isReady()) {
                    cancel();
                    Log.i(TAG, "Backend ready; resuming requested card launch");
                    launch.invoke(view, function, intent);
                    fallback.invoke(view, function);
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    cancel();
                    reset.invoke(view);
                    Toast.makeText(host, "卡片暂未就绪，请重试", Toast.LENGTH_SHORT).show();
                    Log.w(TAG, "Backend readiness timed out; card reset without fullscreen launch");
                } else {
                    RootDisplayClient.requestBackend();
                    MAIN.postDelayed(this, 100);
                }
            } catch (Throwable error) {
                cancel();
                try { reset.invoke(view); } catch (Exception ignored) { }
                Log.e(TAG, "Cannot resume payment card", error);
            }
        }
    }
}
