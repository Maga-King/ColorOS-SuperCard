package dev.local.supercardhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Launches the APK's root endpoint from the app UID, never from SystemUI. */
public final class AutoVirtualDisplayManager {
    static final String PACKAGE = "dev.local.supercardhost";
    static final String START = PACKAGE + ".START_ROOT_DISPLAY";
    static final String ANNOUNCE = PACKAGE + ".ROOT_DISPLAY_SERVICE";
    static final String PAYMENT_DEMAND = "paymentDemand";
    private static final String TAG = "SuperCardAutoDisplay";
    private static final long QUICK_RETRY_MS = 3_000;
    private static final long RETRY_MS = 30_000;
    private static Handler worker;
    private static Context app;
    private static volatile IBinder backend;
    private static long lastAttempt = -RETRY_MS;
    private static int attempts;
    private static boolean paymentRequested;

    private AutoVirtualDisplayManager() {}

    public static void start(Context context, Runnable finished) {
        start(context, false, finished);
    }

    static synchronized void start(Context context, boolean paymentDemand, Runnable finished) {
        if (!PACKAGE.equals(context.getPackageName())) {
            if (finished != null) finished.run();
            return;
        }
        if (worker == null) {
            app = context.getApplicationContext();
            if (app == null) app = context;
            HandlerThread thread = new HandlerThread("SuperCardRootStarter");
            thread.start();
            worker = new Handler(thread.getLooper());
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (getSentFromUid() != 0) return;
                    Bundle extras = intent.getExtras();
                    IBinder candidate = extras == null ? null : extras.getBinder("service");
                    if (candidate == null || !candidate.isBinderAlive() || candidate.equals(backend)) return;
                    try {
                        candidate.linkToDeath(() -> {
                            if (backend == candidate) {
                                backend = null;
                                worker.post(() -> { attempts = 0; lastAttempt = -RETRY_MS; launchIfNeeded(); });
                            }
                        }, 0);
                        backend = candidate;
                        Log.i(TAG, "APK root display endpoint is alive");
                    } catch (Exception error) { Log.w(TAG, "Root endpoint disappeared", error); }
                }
            }, new IntentFilter(ANNOUNCE), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        }
        worker.post(() -> {
            if (paymentDemand) paymentRequested = true;
            try { launchIfNeeded(); }
            finally { if (finished != null) finished.run(); }
        });
    }

    private static final Runnable RETRY = () -> launchIfNeeded();

    private static void launchIfNeeded() {
        long remainingDelay = -1;
        try {
            IBinder current = backend;
            if (current != null && current.isBinderAlive()) {
                paymentRequested = false;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long delay = paymentRequested || attempts < 5 ? QUICK_RETRY_MS : RETRY_MS;
            if (now - lastAttempt < delay) {
                remainingDelay = delay - (now - lastAttempt);
                return;
            }
            // An explicit card request bypasses background backoff once, while still
            // sharing the three-second debounce with every other startup trigger.
            paymentRequested = false;
            lastAttempt = now;
            attempts++;
            String apk = app.getApplicationInfo().sourceDir;
            if (apk == null || !new File(apk).isFile()) throw new IllegalStateException("Installed APK is unavailable");
            File log = new File(app.getFilesDir(), "root-display.log");
            // Create it as the app before root redirects output, retaining app ownership.
            if (!log.exists()) try (FileOutputStream output = new FileOutputStream(log)) { }
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String command = "CLASSPATH=" + quote(apk)
                    + " /system/bin/app_process /system/bin --nice-name=supercard_root_display "
                    + "dev.local.supercardhost.RootDisplayDaemon " + nonce
                    + " > " + quote(log.getAbsolutePath()) + " 2>&1 < /dev/null &";
            java.lang.Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.destroy();
                    throw new IllegalStateException("Root launch timed out");
                }
                if (process.exitValue() != 0) throw new IllegalStateException("Root launch denied: " + process.exitValue());
                Log.i(TAG, "Requested secure display endpoint from installed APK");
            } finally {
                process.getInputStream().close();
                process.getOutputStream().close();
                process.getErrorStream().close();
            }
        } catch (Throwable error) { Log.w(TAG, "Cannot start APK root display endpoint", error); }
        finally {
            worker.removeCallbacks(RETRY);
            if (backend == null || !backend.isBinderAlive()) {
                worker.postDelayed(RETRY, remainingDelay >= 0 ? remainingDelay
                        : attempts < 5 ? QUICK_RETRY_MS : RETRY_MS);
            }
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
