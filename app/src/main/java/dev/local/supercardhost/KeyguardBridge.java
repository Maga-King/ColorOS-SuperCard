package dev.local.supercardhost;

import android.app.Application;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Connects the original card dismiss callback to ColorOS's real authentication flow. */
final class KeyguardBridge {
    private static final String TAG = "SuperCardKeyguard";
    private static final long UNLOCK_SETTLE_TIMEOUT_MS = 2_000;
    private static final long UNLOCK_RECHECK_MS = 100;
    private static final AtomicBoolean installed = new AtomicBoolean();

    private KeyguardBridge() {}

    static void install(XposedModule module, Application host, ClassLoader pluginLoader,
                        Runnable suspendOverlay, Runnable restoreOverlay) {
        if (!installed.compareAndSet(false, true)) return;
        try {
            Class<?> managerType = pluginLoader.loadClass(
                    "com.vivo.card.communication.KeyguardBinderConnectManager");
            Method request = managerType.getDeclaredMethod("requestDismissKeyguardApi", Bundle.class);
            Method createCallback = managerType.getDeclaredMethod("createDismissCallback");
            request.setAccessible(true);
            createCallback.setAccessible(true);
            Handler mainHandler = new Handler(Looper.getMainLooper());
            AtomicBoolean pending = new AtomicBoolean();

            module.hook(request).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    // One original callback fans the result out to the registered card listeners.
                    if (!pending.compareAndSet(false, true)) {
                        Log.i(TAG, "Keyguard dismissal already pending");
                        return null;
                    }
                    Attempt attempt = null;
                    try {
                        Object callback = createCallback.invoke(chain.getThisObject());
                        if (callback == null) throw new IllegalStateException("Missing card dismiss callback");
                        attempt = new Attempt(host, mainHandler, pending, callback,
                                suspendOverlay, restoreOverlay);
                        attempt.start();
                    } catch (Throwable error) {
                        Log.e(TAG, "Unable to begin keyguard dismissal", error);
                        if (attempt != null) {
                            attempt.finish("onDismissError");
                        } else {
                            pending.set(false);
                        }
                    }
                    // The OEM Binder has a different protocol and must not receive this call.
                    return null;
                }
            });
            Log.i(TAG, "Installed real system keyguard dismissal bridge");
        } catch (Throwable error) {
            installed.set(false);
            Log.e(TAG, "Unable to install keyguard bridge", error);
        }
    }

    private static final class Attempt {
        private final Application host;
        private final Handler handler;
        private final AtomicBoolean pending;
        private final Object callback;
        private final Runnable suspendOverlay;
        private final Runnable restoreOverlay;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean verificationStarted = new AtomicBoolean();
        private long unlockDeadline;

        Attempt(Application host, Handler handler, AtomicBoolean pending, Object callback,
                Runnable suspendOverlay, Runnable restoreOverlay) {
            this.host = host;
            this.handler = handler;
            this.pending = pending;
            this.callback = callback;
            this.suspendOverlay = suspendOverlay;
            this.restoreOverlay = restoreOverlay;
        }

        void start() {
            onMain(() -> {
                if (finished.get()) return;
                try {
                    suspendOverlay.run();
                    Object starter = SystemUiBridge.dependency(host,
                            "com.android.systemui.plugins.ActivityStarter");
                    if (starter == null) throw new IllegalStateException("ActivityStarter unavailable");
                    Class<?> starterType = host.getClassLoader().loadClass(
                            "com.android.systemui.plugins.ActivityStarter");
                    Method execute = starterType.getMethod("executeRunnableDismissingKeyguard",
                            Runnable.class, Runnable.class, boolean.class, boolean.class, boolean.class);
                    execute.setAccessible(true);
                    Runnable success = () -> onMain(() -> {
                        if (finished.get() || !verificationStarted.compareAndSet(false, true)) return;
                        unlockDeadline = SystemClock.uptimeMillis() + UNLOCK_SETTLE_TIMEOUT_MS;
                        checkUnlocked();
                    });
                    Runnable cancel = () -> finish("onDismissCancelled");
                    execute.invoke(starter, success, cancel, false, true, false);
                    Log.i(TAG, "Requested system keyguard authentication");
                } catch (Throwable error) {
                    Log.e(TAG, "System keyguard dismissal failed", error);
                    finish("onDismissError");
                }
            });
        }

        private boolean isActuallyUnlocked() {
            KeyguardManager keyguard = host.getSystemService(KeyguardManager.class);
            if (keyguard == null) throw new IllegalStateException("KeyguardManager unavailable");
            return !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
        }

        private void checkUnlocked() {
            if (finished.get()) return;
            try {
                if (isActuallyUnlocked()) {
                    finish("onDismissSucceeded");
                } else if (SystemClock.uptimeMillis() < unlockDeadline) {
                    if (!handler.postDelayed(this::checkUnlocked, UNLOCK_RECHECK_MS)) {
                        throw new IllegalStateException("Unlock verification could not be scheduled");
                    }
                } else {
                    Log.w(TAG, "System callback arrived while device remained locked");
                    finish("onDismissError");
                }
            } catch (Throwable error) {
                Log.e(TAG, "Unable to verify actual unlock state", error);
                finish("onDismissError");
            }
        }

        void finish(String result) {
            onMain(() -> {
                if (!finished.compareAndSet(false, true)) return;
                String finalResult = result;
                try {
                    restoreOverlay.run();
                } catch (Throwable error) {
                    Log.e(TAG, "Unable to restore card overlay", error);
                    finalResult = "onDismissError";
                }
                // Recheck after restoring the overlay; success is never inferred from a callback alone.
                if ("onDismissSucceeded".equals(finalResult)) {
                    try {
                        if (!isActuallyUnlocked()) finalResult = "onDismissError";
                    } catch (Throwable error) {
                        Log.e(TAG, "Final unlock verification failed", error);
                        finalResult = "onDismissError";
                    }
                }
                pending.set(false);
                try {
                    Method notify = callback.getClass().getMethod(finalResult);
                    notify.setAccessible(true);
                    notify.invoke(callback);
                    Log.i(TAG, "Forwarded original card callback: " + finalResult);
                } catch (Throwable error) {
                    Log.e(TAG, "Original card dismiss callback failed", error);
                }
            });
        }

        private void onMain(Runnable action) {
            Runnable guarded = () -> {
                try {
                    action.run();
                } catch (Throwable error) {
                    Log.e(TAG, "Keyguard bridge main-thread action failed", error);
                    finish("onDismissError");
                }
            };
            try {
                if (Looper.myLooper() == handler.getLooper()) {
                    guarded.run();
                } else if (!handler.post(guarded)) {
                    // A shutting-down looper cannot service a request. Release the gate without success.
                    pending.set(false);
                    Log.e(TAG, "SystemUI main thread rejected keyguard action");
                }
            } catch (Throwable error) {
                pending.set(false);
                Log.e(TAG, "Unable to dispatch keyguard action", error);
            }
        }
    }
}
