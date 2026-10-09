package dev.local.supercardhost;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

/** Loads the wallet's actual HTTPS QR image, without interpreting its URL as a QR payload. */
public final class CouponImageLoader {
    private static final String TAG = "SuperCardCouponImage";
    private static final int MAX_BYTES = 1_048_576;
    private static final long TIMEOUT_MS = 10_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "SuperCardCouponImage");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService GUARD = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "SuperCardCouponImageTimeout");
        thread.setDaemon(true); return thread;
    });
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    // Accessed only on MAIN. The binding does not strongly retain its ImageView.
    private static final Map<ImageView, Binding> BINDINGS = new WeakHashMap<>();
    private static long lastFailureToast;

    private CouponImageLoader() { }

    /** A hidden/locked/account-invalid session must not finish rendering an old image. */
    public static void invalidateAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(CouponImageLoader::invalidateAll); return;
        }
        for (Map.Entry<ImageView, Binding> entry : BINDINGS.entrySet()) {
            Binding binding = entry.getValue();
            binding.cancel();
            ImageView image = entry.getKey();
            if (image != null) {
                image.removeOnAttachStateChangeListener(binding);
                image.setImageDrawable(null);
            }
        }
        BINDINGS.clear();
        CACHE.evictAll();
    }

    /** Inputs must come from authenticated wallet metadata, never recognized screenshot text. */
    public static void bind(ImageView image, String couponKey, String imageUrl) {
        bind(image, couponKey, imageUrl, null);
    }

    public static void bind(ImageView image, String couponKey, String imageUrl, TextView hint) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> bind(image, couponKey, imageUrl, hint)); return;
        }
        Binding old = BINDINGS.remove(image);
        if (old != null) { old.cancel(); image.removeOnAttachStateChangeListener(old); }
        Binding binding = new Binding(image, couponKey, imageUrl, hint);
        BINDINGS.put(image, binding);
        image.addOnAttachStateChangeListener(binding);
        image.setVisibility(View.VISIBLE);
        image.setContentDescription("正在加载钱包二维码");
        if (hint != null) hint.setText("正在加载钱包原始二维码");
        binding.start();
    }

    private static final class Binding implements View.OnAttachStateChangeListener {
        final WeakReference<ImageView> image;
        final WeakReference<TextView> hint;
        final String url, cacheKey;
        final AtomicInteger generation = new AtomicInteger();
        volatile HttpURLConnection connection;
        volatile Future<?> work, timeout;
        boolean loaded;

        Binding(ImageView image, String couponKey, String url, TextView hint) {
            this.image = new WeakReference<>(image);
            this.hint = new WeakReference<>(hint);
            this.url = url;
            cacheKey = couponKey + '\u0000' + url;
        }

        void start() {
            if (loaded || work != null) return;
            ImageView target = image.get();
            if (target == null || BINDINGS.get(target) != this) return;
            Bitmap cached = CACHE.get(cacheKey);
            if (cached != null && !cached.isRecycled()) {
                loaded = true; target.setImageBitmap(cached);
                target.setContentDescription("钱包原始二维码");
                setHint("二维码来自钱包，请向商家出示"); return;
            }
            int token = generation.incrementAndGet();
            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            try {
                timeout = GUARD.schedule(() -> {
                    if (generation.compareAndSet(token, token + 1)) {
                        Future<?> running = work;
                        if (running != null) running.cancel(true);
                        HttpURLConnection active = connection;
                        if (active != null) active.disconnect();
                        MAIN.post(() -> failed(token + 1));
                    }
                }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
                work = WORK.submit(() -> {
                    try {
                        Bitmap bitmap = download(this, token, deadline);
                        if (generation.get() != token) return;
                        synchronized (CACHE) {
                            if (generation.get() != token) return;
                            CACHE.put(cacheKey, bitmap);
                        }
                        MAIN.post(() -> {
                            ImageView current = image.get();
                            if (generation.get() == token && current != null && BINDINGS.get(current) == this) {
                                loaded = true;
                                current.setImageBitmap(bitmap);
                                current.setVisibility(View.VISIBLE);
                                current.setContentDescription("钱包原始二维码");
                                setHint("二维码来自钱包，请向商家出示");
                            }
                        });
                    } catch (Throwable error) {
                        if (generation.get() == token) {
                            // Do not log signed image URLs, query tokens or image bytes.
                            Log.w(TAG, "Actual wallet image load failed: " + error.getClass().getSimpleName());
                            MAIN.post(() -> failed(token));
                        }
                    } finally {
                        if (generation.get() == token) {
                            Future<?> guard = timeout;
                            if (guard != null) guard.cancel(false);
                        }
                    }
                });
            } catch (RuntimeException queueFull) {
                Future<?> guard = timeout;
                if (guard != null) guard.cancel(false);
                failed(token);
            }
        }

        void failed(int token) {
            ImageView target = image.get();
            if (generation.get() != token || loaded || target == null || BINDINGS.get(target) != this) return;
            target.setContentDescription("二维码图片暂不可用，请在钱包查看");
            setHint("二维码图片暂不可用，可打开钱包查看");
            long now = SystemClock.elapsedRealtime();
            if (target.isAttachedToWindow() && (lastFailureToast == 0 || now - lastFailureToast > 20_000)) {
                lastFailureToast = now;
                Toast.makeText(target.getContext(), "二维码图片暂不可用，可打开钱包查看", Toast.LENGTH_SHORT).show();
            }
        }

        void setHint(String text) {
            TextView target = hint.get();
            if (target != null) target.setText(text);
        }

        void cancel() {
            generation.incrementAndGet();
            Future<?> running = work; work = null;
            if (running != null) running.cancel(true);
            Future<?> guard = timeout; timeout = null;
            if (guard != null) guard.cancel(false);
            HttpURLConnection active = connection;
            if (active != null) GUARD.execute(active::disconnect);
            WORK.purge();
        }

        @Override public void onViewAttachedToWindow(View view) { start(); }
        @Override public void onViewDetachedFromWindow(View view) { cancel(); }
    }

    private static Bitmap download(Binding binding, int token, long deadline) throws Exception {
        URL url = allowed(binding.url);
        for (int redirects = 0; redirects <= 2; redirects++) {
            check(binding, token, deadline);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            binding.connection = connection;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(3_000);
                connection.setReadTimeout(5_000);
                connection.setUseCaches(false);
                connection.setAllowUserInteraction(false);
                connection.setRequestProperty("Accept", "image/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                check(binding, token, deadline);
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirects == 2 || location == null) throw new java.io.IOException("Redirect limit");
                    url = allowed(new URL(url, location).toString());
                    continue;
                }
                if (code != 200) throw new java.io.IOException("Image response unavailable");
                long length = connection.getContentLengthLong();
                if (length > MAX_BYTES) throw new java.io.IOException("Image response too large");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(length > 0 ? (int) length : 16_384);
                try (InputStream input = connection.getInputStream()) {
                    byte[] chunk = new byte[16_384];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        check(binding, token, deadline);
                        if (bytes.size() + count > MAX_BYTES) throw new java.io.IOException("Image response too large");
                        bytes.write(chunk, 0, count);
                    }
                }
                check(binding, token, deadline);
                byte[] data = bytes.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096
                        || bounds.outHeight > 4096 || (long) bounds.outWidth * bounds.outHeight > 4_194_304)
                    throw new java.io.IOException("Image dimensions unsupported");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1; options.inScaled = false;
                while (bounds.outWidth / options.inSampleSize > 1024 || bounds.outHeight / options.inSampleSize > 1024)
                    options.inSampleSize *= 2;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap == null || bitmap.getAllocationByteCount() > 4_194_304)
                    throw new java.io.IOException("Image decode unavailable");
                check(binding, token, deadline);
                return bitmap;
            } finally {
                connection.disconnect();
                if (binding.connection == connection) binding.connection = null;
            }
        }
        throw new java.io.IOException("Image unavailable");
    }

    private static URL allowed(String raw) throws Exception {
        if (raw == null || raw.length() > 4096) throw new java.io.IOException("Invalid image URL");
        URL url = new URL(raw);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                || url.getUserInfo() != null || url.getPort() != -1 && url.getPort() != 443)
            throw new java.io.IOException("Unsupported image URL");
        return url;
    }

    private static void check(Binding binding, int token, long deadline) throws java.io.IOException {
        if (binding.generation.get() != token || Thread.currentThread().isInterrupted()
                || SystemClock.elapsedRealtime() >= deadline) throw new java.io.IOException("Image load expired");
    }
}
