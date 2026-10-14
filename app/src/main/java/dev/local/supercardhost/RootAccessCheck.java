package dev.local.supercardhost;

import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Checks this application's actual root authorization without blocking the UI. */
final class RootAccessCheck {
    enum Result { AUTHORIZED, NOT_AUTHORIZED, UNAVAILABLE, TIMED_OUT, FAILED }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern ROOT_UID = Pattern.compile("(?:^|\\s)uid=0(?:\\D|$)");
    // Accessed on the main thread. Recreated pages join a check or use its result.
    private static final ArrayList<Consumer<Result>> CALLBACKS = new ArrayList<>();
    private static boolean checking;
    private static Result cached;

    static void check(boolean refresh, Consumer<Result> callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> check(refresh, callback));
            return;
        }
        if (!checking && !refresh && cached != null) {
            callback.accept(cached);
            return;
        }
        CALLBACKS.add(callback);
        if (checking) return;
        checking = true;
        Thread worker = new Thread(() -> {
            Result result = probe();
            MAIN.post(() -> {
                cached = result;
                checking = false;
                ArrayList<Consumer<Result>> callbacks = new ArrayList<>(CALLBACKS);
                CALLBACKS.clear();
                for (Consumer<Result> listener : callbacks) listener.accept(result);
            });
        }, "SuperCardRootCheck");
        worker.setDaemon(true);
        worker.start();
    }

    private static Result probe() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            if (!process.waitFor(12, TimeUnit.SECONDS)) return Result.TIMED_OUT;
            // id is tiny. Never wait indefinitely for an inherited output pipe.
            InputStream output = process.getInputStream();
            byte[] bytes = new byte[Math.min(4096, output.available())];
            int size = bytes.length == 0 ? 0 : output.read(bytes);
            String text = new String(bytes, 0, Math.max(0, size), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && ROOT_UID.matcher(text).find()
                    ? Result.AUTHORIZED : Result.NOT_AUTHORIZED;
        } catch (IOException unavailable) {
            return process == null ? Result.UNAVAILABLE : Result.FAILED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (RuntimeException failed) {
            return Result.FAILED;
        } finally {
            if (process != null) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
        }
    }
}
