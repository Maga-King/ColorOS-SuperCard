package dev.local.supercardhost;

import android.Manifest;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Keeps the original memory UI and replaces only its unavailable vivo service boundary. */
final class MemoryRuntimeBridge {
    private static final String TAG = "SuperCardMemory";
    private static final String INFO = "com.vivo.memory.card.ipc.bean.MemoryCardInfo";
    private static final String TRACE = "com.vivo.memory.card.ipc.bean.TraceEvent";
    private static final Set<ClassLoader> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SuperCardMemoryIO");
        thread.setDaemon(true);
        return thread;
    });
    // One microphone session for the process, even if a plugin is reloaded.
    private static final AtomicBoolean RECORD_PENDING = new AtomicBoolean();
    private static volatile Recording recording;
    private static volatile Application application;

    private MemoryRuntimeBridge() {}

    static synchronized boolean install(XposedModule module, Application host,
                                     ClassLoader memoryLoader) {
        if (INSTALLED.contains(memoryLoader)) return true;
        application = host;
        try {
            Class<?> boundary = memoryLoader.loadClass("a6.d");
            Method operation = null;
            Method save = null;
            Method bind = null;
            for (Method method : boundary.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) continue;
                // This APK has six parameters; count and every type are verified together.
                if (p.length == 6
                        && p[0] == Context.class && p[1].getName().equals(TRACE)
                        && p[2] == String.class && p[3] == Bundle.class
                        && operationCallback(p[4])
                        && p[5].getName().equals("com.vivo.systemlighteffect.edgelight.f")) {
                    if (operation != null) throw new NoSuchMethodException("Ambiguous memory operation boundary");
                    operation = method;
                }
                if (p.length == 6
                        && p[0] == Context.class && p[1] == String.class
                        && p[2] == List.class && p[3] == ArrayList.class
                        && p[4] == Bundle.class && saveCallback(p[5])) {
                    if (save != null) throw new NoSuchMethodException("Ambiguous memory save boundary");
                    save = method;
                }
                if (p.length == 3 && p[0] == Context.class
                        && p[1] == Runnable.class && Runnable.class.isAssignableFrom(p[2])) {
                    if (bind != null) throw new NoSuchMethodException("Ambiguous memory bind boundary");
                    bind = method;
                }
            }
            if (operation == null || save == null || bind == null) {
                throw new NoSuchMethodException("Memory service boundary signature changed");
            }
            operation.setAccessible(true);
            save.setAccessible(true);
            bind.setAccessible(true);
            module.hook(operation).intercept(chain -> {
                Object callback = chain.getArg(4);
                try {
                    dispatch(host, memoryLoader, (String) chain.getArg(2),
                            (Bundle) chain.getArg(3), callback, chain.getArg(5));
                } catch (Throwable error) {
                    fail(callback, error);
                }
                return null;
            });
            module.hook(save).intercept(chain -> {
                Object callback = chain.getArg(5);
                try {
                    String op = (String) chain.getArg(1);
                    if (!"auto_save_memory".equals(op) && !"save_memory".equals(op)
                            && !"add_memory".equals(op) && !"delete_memory".equals(op)) {
                        throw new UnsupportedOperationException("未支持的记忆保存操作：" + op);
                    }
                    @SuppressWarnings("unchecked") List<?> attachments = (List<?>) chain.getArg(2);
                    // SaveClient owns draft vs commit, deduplication and actual import acknowledgement.
                    MemorySaveClient.save(host, memoryLoader, op, attachments,
                            (Bundle) chain.getArg(4), callback);
                } catch (Throwable error) {
                    notifySave(callback, 2002, null, message(error));
                }
                return null;
            });
            module.hook(bind).intercept(chain -> {
                // m/i above never need a Binder. The remaining callers only send vivo trace events.
                // A future unhandled caller with a failure continuation must receive that failure.
                try {
                    Object failure = chain.getArg(2);
                    if (failure instanceof Runnable) MAIN.post(() -> guarded((Runnable) failure));
                } catch (Throwable error) {
                    Log.w(TAG, "Dropped unavailable vivo bind", error);
                }
                return null;
            });
            installRelease(module, boundary);
            installPhoto(module, host, memoryLoader);
            MemoryHeaderCompat.install(module, memoryLoader);
            INSTALLED.add(memoryLoader);
            Log.i(TAG, "Memory operation/save boundary installed");
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to install memory runtime bridge", error);
            return false;
        }
    }

    private static boolean operationCallback(Class<?> type) {
        for (Method m : type.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 4 && p[0] == int.class
                    && p[1].getName().equals(INFO) && p[2] == String.class && p[3] == Bundle.class) return true;
        }
        return false;
    }

    private static boolean saveCallback(Class<?> type) {
        for (Method m : type.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 3 && p[0] == int.class
                    && p[1] == List.class && p[2] == String.class) return true;
        }
        return false;
    }

    private static void dispatch(Application host, ClassLoader loader, String operation,
                                 Bundle options, Object callback, Object wave) throws Exception {
        Log.i(TAG, "Dispatch op=" + operation + ", callback="
                + (callback == null ? "null" : callback.getClass().getName())
                + ", wave=" + (wave == null ? "null" : wave.getClass().getName()));
        if ("only_check_auth".equals(operation)) {
            Bundle request = options == null ? new Bundle() : new Bundle(options);
            WORK.execute(() -> {
                Bundle missing = MemorySaveClient.checkReady(host, request);
                if (missing == null) {
                    notifyOperation(callback, 0, null, null, null);
                } else {
                    // This original callback treats unknown nonempty vivo auth keys as
                    // authorization granted. An empty result aborts its continuation.
                    notifyOperation(callback, 5, null, null, null);
                    toast(host, missing.getString("error", "请先在小布记忆中完成服务设置"));
                    if (missing.getBoolean("requiresSetup")) MAIN.post(() -> {
                        try { launchMemory(host); }
                        catch (Throwable error) { toast(host, message(error)); }
                    });
                }
            });
            return;
        }
        if ("stop_record".equals(operation)) {
            stopRecord(host, callback);
            return;
        }
        requireUnlocked(host);
        if ("start_record".equals(operation)) {
            startRecord(host, loader, callback, wave);
        } else if ("screen_capture".equals(operation)
                || "go_favorite_screen_capture".equals(operation)) {
            WORK.execute(() -> {
                try {
                    requireUnlocked(host);
                    // CaptureSupport must exclude secure/protected layers and fail for secure content.
                    File file = MemoryCaptureSupport.captureScreen(host);
                    if (file == null || !file.isFile() || file.length() == 0) {
                        throw new IllegalStateException("未取得可保存的截图");
                    }
                    Object info = info(loader, file, 2401, "屏幕记忆");
                    requireUnlocked(host);
                    Uri uri = MemoryCaptureSupport.uriForFile(host, file);
                    verifyReadable(host, uri);
                    set(info, "g", uri.toString());
                    // 2005 starts the original screen overlay. Do not start it until
                    // its attachment and explicit read grant are both ready.
                    Log.i(TAG, "Screen attachment ready, bytes=" + file.length());
                    notifyOperation(callback, 2005, info, null, null);
                    notifyOperation(callback, 0, info, null, null);
                } catch (Throwable error) {
                    MemoryCaptureSupport.releaseScreenHide();
                    fail(callback, error);
                }
            });
        } else if ("screen_capture_go_favorite".equals(operation)
                || "start_record_go_memory_edit".equals(operation)
                || "start_record_go_favorite".equals(operation)) {
            launchMemory(host);
            notifyOperation(callback, 4, null, null, null);
        } else if (operation != null && (operation.startsWith("show_")
                || operation.startsWith("dialog_authorize_"))) {
            // Let the backend report its actual service/privacy state; never assert vivo consent.
            Bundle request = options == null ? new Bundle() : new Bundle(options);
            WORK.execute(() -> {
                Bundle missing = MemorySaveClient.checkReady(host, request);
                String message = missing == null ? "小布记忆已就绪，请重试操作"
                        : missing.getString("error", "小布记忆暂时未就绪，请稍后重试");
                toast(host, message);
                notifyOperation(callback, 5, null, message, null);
                if (missing != null && missing.getBoolean("requiresSetup")) MAIN.post(() -> {
                    try { launchMemory(host); } catch (Throwable error) { toast(host, message(error)); }
                });
            });
        } else {
            throw new UnsupportedOperationException("暂未支持的记忆操作：" + operation);
        }
    }

    private static void installPhoto(XposedModule module, Application host, ClassLoader loader) {
        try {
            Class<?> wrapper = loader.loadClass("a6.p");
            Class<?> actionInterface = loader.loadClass("i6.e");
            Method action = null;
            for (Method candidate : actionInterface.getDeclaredMethods()) {
                if (candidate.getReturnType() == void.class && candidate.getParameterCount() == 0) {
                    if (action != null) throw new NoSuchMethodException("Ambiguous photo action interface");
                    action = wrapper.getDeclaredMethod(candidate.getName());
                }
            }
            if (action == null) throw new NoSuchMethodException("Photo action signature changed");
            action.setAccessible(true);
            Log.i(TAG, "Photo action hook=" + action.toGenericString());
            installPhotoButton(module, host, loader);
            module.hook(action).intercept(chain -> {
                try {
                    Log.i(TAG, "Photo action invoked: " + chain.getThisObject().getClass().getName());
                    requireUnlocked(host);
                    // The wrapper is the original i6 unlock-success action, not an animation callback.
                    MemoryCaptureSupport.takePhoto(host, chain.getThisObject(), loader);
                    Log.i(TAG, "Photo action handed to embedded camera");
                } catch (Throwable error) {
                    Log.e(TAG, "Photo entry failed", error);
                    toast(host, message(error));
                }
                return null;
            });
        } catch (Throwable error) {
            throw new IllegalStateException("Unable to replace original photo action", error);
        }
    }

    private static void installPhotoButton(XposedModule module, Application host, ClassLoader loader)
            throws Exception {
        Class<?> click = loader.loadClass("com.vivo.memory.card.view.CardBottomView$b");
        Class<?> bottom = loader.loadClass("com.vivo.memory.card.view.CardBottomView");
        Class<?> auth = loader.loadClass("b6.g");
        Class<?> completion = loader.loadClass("b6.g$d");
        Field outer = null, manager = null;
        for (Field field : click.getDeclaredFields()) {
            if (field.getType() == bottom) { field.setAccessible(true); outer = field; }
        }
        for (Field field : bottom.getDeclaredFields()) {
            if (field.getType() == auth) { field.setAccessible(true); manager = field; }
        }
        java.lang.reflect.Constructor<?> completed = null;
        for (Class<?> nested : click.getDeclaredClasses()) {
            if (completion.isAssignableFrom(nested)) {
                if (completed != null) throw new IllegalStateException("Ambiguous photo authorization callback");
                completed = nested.getDeclaredConstructor(click); completed.setAccessible(true);
            }
        }
        if (completed == null) {
            // This callback is anonymous (EnclosingMethod), so Java does not include
            // it in getDeclaredClasses. Verify the APK's anchored class structurally.
            Class<?> nested = loader.loadClass(click.getName() + "$b");
            if (!completion.isAssignableFrom(nested)) throw new LinkageError("Photo authorization interface changed");
            completed = nested.getDeclaredConstructor(click); completed.setAccessible(true);
        }
        Method guide = null;
        for (Method candidate : auth.getDeclaredMethods()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 3 && p[0] == String.class && p[1] == Runnable.class && p[2] == completion) {
                guide = candidate; guide.setAccessible(true);
            }
        }
        if (outer == null || manager == null || completed == null || guide == null)
            throw new NoSuchMethodException("Original photo-button authorization signature changed");
        Field parentField = outer, authField = manager;
        Method authorize = guide;
        java.lang.reflect.Constructor<?> callbackConstructor = completed;
        java.util.concurrent.atomic.AtomicLong lastClick = new java.util.concurrent.atomic.AtomicLong();
        module.hook(click.getDeclaredMethod("onClick", android.view.View.class)).intercept(chain -> {
            try {
                long now = SystemClock.elapsedRealtime();
                if (now - lastClick.getAndSet(now) < 1000) return null;
                Object listener = chain.getThisObject();
                Object view = parentField.get(listener);
                Field count = bottom.getDeclaredField("P"); count.setAccessible(true);
                if (count.getInt(view) >= 13) { toast(host, "卡片附件已满"); return null; }
                // Preserve the original authorization and unlock continuations, replacing
                // only the precheck for the unavailable vivo camera APK.
                authorize.invoke(authField.get(view), "take_photo", null,
                        callbackConstructor.newInstance(listener));
                Log.i(TAG, "Original photo button dispatched to authorization");
            } catch (Throwable error) {
                Log.e(TAG, "Photo button failed", error); toast(host, message(error));
            }
            return null;
        });
    }

    private static void installRelease(XposedModule module, Class<?> boundary) {
        try {
            Method unbind = null;
            for (Method candidate : boundary.getDeclaredMethods()) {
                Class<?>[] p = candidate.getParameterTypes();
                if (!Modifier.isStatic(candidate.getModifiers()) && candidate.getReturnType() == void.class
                        && p.length == 1 && p[0] == Context.class) {
                    if (unbind != null) throw new NoSuchMethodException("Ambiguous memory unbind signature");
                    unbind = candidate;
                }
            }
            if (unbind == null) throw new NoSuchMethodException("Memory unbind signature changed");
            unbind.setAccessible(true);
            module.hook(unbind).intercept(chain -> {
                // Original service.onUnbind stops its recorder. Match that cleanup after card release.
                WORK.execute(() -> {
                    Recording active = recording;
                    if (active != null) {
                        recording = null;
                        MAIN.removeCallbacks(active.sample);
                        release(active.recorder);
                        active.file.delete();
                        publishWave(active.wave, 0);
                    }
                    RECORD_PENDING.set(false);
                });
                return null;
            });
        } catch (Throwable error) {
            throw new IllegalStateException("Unable to install memory release cleanup", error);
        }
    }

    private static void startRecord(Application host, ClassLoader loader,
                                    Object callback, Object wave) {
        if (!RECORD_PENDING.compareAndSet(false, true)) {
            notifyOperation(callback, 2005, null, "录音已经在进行中", null);
            return;
        }
        WORK.execute(() -> {
            MediaRecorder recorder = null;
            File file = null;
            try {
                requireUnlocked(host);
                if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("尚未取得麦克风权限");
                }
                File directory = new File(host.getCacheDir(), "supercard-memory");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法建立录音目录");
                file = new File(directory, "memory-" + UUID.randomUUID() + ".m4a");
                recorder = new MediaRecorder(host);
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setOutputFile(file.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                Log.i(TAG, "Recording started: " + file.getName());
                Recording active = new Recording(host, loader, recorder, file, wave);
                recording = active;
                recorder.setOnErrorListener((ignored, what, extra) -> abort(active, callback,
                        new IllegalStateException("录音设备错误：" + what + "/" + extra)));
                notifyOperation(callback, 2006, null, null, null);
                MAIN.post(active.sample);
            } catch (Throwable error) {
                Log.e(TAG, "Recording start failed", error);
                release(recorder);
                if (file != null) file.delete();
                recording = null;
                RECORD_PENDING.set(false);
                notifyOperation(callback, 2005, null, message(error), null);
            }
        });
    }

    private static void stopRecord(Application host, Object callback) {
        Log.i(TAG, "Recording stop requested, callback="
                + (callback == null ? "null" : callback.getClass().getName()));
        // Serial with start so a rapid real stop press cannot race recorder.prepare().
        WORK.execute(() -> {
            Recording active = recording;
            if (active == null) {
                Log.w(TAG, "Recording stop has no active recorder");
                notifyOperation(callback, 2005, null, "当前没有可停止的录音", null);
                return;
            }
            recording = null;
            MAIN.removeCallbacks(active.sample);
            long elapsed = SystemClock.elapsedRealtime() - active.started;
            try {
                Log.i(TAG, "Stopping recorder, elapsedMs=" + elapsed);
                active.recorder.stop();
                release(active.recorder);
                Log.i(TAG, "Recorder stopped, bytes=" + active.file.length());
                if (elapsed < 1000) {
                    active.file.delete();
                    notifyOperation(callback, 2009, null, "录音时间不足一秒", null);
                    return;
                }
                if (!active.file.isFile() || active.file.length() == 0) throw new IllegalStateException("录音文件保存失败");
                long duration = duration(active.file);
                if (duration < 1000) {
                    active.file.delete();
                    notifyOperation(callback, 2009, null, "录音时间不足一秒", null);
                    return;
                }
                Uri uri = MemoryCaptureSupport.uriForFile(host, active.file);
                verifyReadable(host, uri);
                Log.i(TAG, "Recording attachment readable, durationMs=" + duration);
                Object info = info(active.loader, active.file, 401, "语音记忆");
                set(info, "g", uri.toString());
                set(info, "j", duration);
                set(info, "k", duration * 1000L);
                int[] levels = new int[active.levels.size()];
                for (int index = 0; index < levels.length; index++) levels[index] = active.levels.get(index);
                set(info, "p", levels);
                Log.i(TAG, "Returning RECORD_FINISH(2008), waveformSamples=" + levels.length);
                notifyOperation(callback, 2008, info, null, null);
            } catch (Throwable error) {
                Log.e(TAG, "Recording stop/attachment failed, elapsedMs=" + elapsed
                        + ", bytes=" + active.file.length(), error);
                release(active.recorder);
                notifyOperation(callback, elapsed < 1000 ? 2009 : 2005,
                        null, message(error), null);
            } finally {
                publishWave(active.wave, 0);
                RECORD_PENDING.set(false);
            }
        });
    }

    private static void abort(Recording active, Object callback, Throwable error) {
        WORK.execute(() -> {
            if (recording != active) return;
            recording = null;
            MAIN.removeCallbacks(active.sample);
            release(active.recorder);
            active.file.delete();
            RECORD_PENDING.set(false);
            publishWave(active.wave, 0);
            notifyOperation(callback, 2005, null, message(error), null);
        });
    }

    private static final class Recording {
        final Application host;
        final ClassLoader loader;
        final MediaRecorder recorder;
        final File file;
        final Object wave;
        final long started = SystemClock.elapsedRealtime();
        final ArrayList<Integer> levels = new ArrayList<>();
        final Runnable sample = new Runnable() {
            @Override public void run() {
                if (recording != Recording.this) return;
                WORK.execute(() -> {
                    if (recording != Recording.this) return;
                    try {
                        int amplitude = recorder.getMaxAmplitude();
                        // Bound long sessions without recording fabricated waveform samples.
                        if (levels.size() < 36000) levels.add(amplitude);
                        // Original ao.a.B -> xn.c.a supplies twice the integer decibel level,
                        // not the raw 0..32767 MediaRecorder peak. Silence stays actual silence.
                        float level = amplitude > 100
                                ? 2f * (int) (20d * Math.log10(amplitude / 100d)) : 0f;
                        publishWave(wave, level);
                    } catch (Throwable error) {
                        Log.w(TAG, "Recording amplitude unavailable", error);
                    }
                });
                MAIN.postDelayed(this, 100);
            }
        };

        Recording(Application host, ClassLoader loader, MediaRecorder recorder, File file, Object wave) {
            this.host = host;
            this.loader = loader;
            this.recorder = recorder;
            this.file = file;
            this.wave = wave;
        }
    }

    private static Object info(ClassLoader loader, File file, int type, String title) throws Exception {
        Object result = loader.loadClass(INFO).getDeclaredConstructor().newInstance();
        set(result, "a", file.getAbsolutePath());
        set(result, "b", title);
        set(result, "c", System.currentTimeMillis());
        set(result, "d", UUID.randomUUID().toString());
        set(result, "e", type);
        // f is a vivo collect database id, so it deliberately stays zero.
        return result;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void publishWave(Object wrapper, float amplitude) {
        if (wrapper == null) return;
        MAIN.post(() -> {
            try {
                for (Field field : wrapper.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object executor = field.get(wrapper);
                    if (executor == null || !executor.getClass().getName().equals(
                            "com.vivo.memory.card.operation.RecordExecutor")) continue;
                    Field level = null;
                    for (Field candidate : executor.getClass().getDeclaredFields()) {
                        if (!Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == float.class) {
                            if (level != null) throw new IllegalStateException("Ambiguous record amplitude field");
                            level = candidate;
                        }
                    }
                    if (level != null) {
                        level.setAccessible(true);
                        level.setFloat(executor, amplitude);
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "Unable to update original record waveform", error);
            }
        });
    }

    private static long duration(File file) throws Exception {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null) throw new IllegalStateException("无法读取录音时长");
            return Long.parseLong(value);
        }
    }

    private static void release(MediaRecorder recorder) {
        if (recorder == null) return;
        try { recorder.reset(); } catch (Throwable ignored) {}
        try { recorder.release(); } catch (Throwable ignored) {}
    }

    private static void verifyReadable(Context context, Uri uri) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalStateException("附件URI无效");
        if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("附件URI尚未授权给卡包进程");
        }
        try (android.os.ParcelFileDescriptor descriptor =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IllegalStateException("附件尚不可读取");
        }
    }

    private static void requireUnlocked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()) {
            throw new SecurityException("请先解锁手机");
        }
    }

    private static void launchMemory(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.oplus.aimemory");
        if (intent == null) throw new IllegalStateException("尚未安装小布记忆");
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void fail(Object callback, Throwable error) {
        Log.e(TAG, "Memory operation failed", error);
        if (callback == null) toast(application, message(error));
        MAIN.post(() -> {
            clearScreenEffect(callback);
            notifyOperation(callback, 2002, null, message(error), null);
        });
    }

    /** Call the original cleanup; restoring the card alone does not remove its screen overlay. */
    private static boolean clearScreenEffect(Object callback) {
        if (callback == null) return false;
        boolean screenCallback = false;
        try {
            Field executorField = uniqueValueField(callback,
                    "com.vivo.memory.card.operation.b");
            if (executorField == null) return false;
            screenCallback = true;
            Object executor = executorField.get(callback);
            Field effectField = uniqueValueField(executor, "m6.b1");
            if (effectField == null) return true;
            Object effect = effectField.get(executor);
            if (effect != null) {
                try {
                    Method stop = effect.getClass().getDeclaredMethod("d", String.class);
                    stop.setAccessible(true);
                    stop.invoke(effect, "SuperCardMemory operation failed");
                    Log.i(TAG, "Original screen effect released after failure");
                } finally {
                    effectField.set(executor, null);
                }
            }
        } catch (Throwable cleanupError) {
            Log.e(TAG, "Unable to release original screen effect", cleanupError);
        }
        return screenCallback;
    }

    /** The synthetic callback stores its executor as Object; inspect its runtime type too. */
    private static Field uniqueValueField(Object owner, String typeName) throws Exception {
        if (owner == null) return null;
        Field found = null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field candidate : type.getDeclaredFields()) {
                if (Modifier.isStatic(candidate.getModifiers()) || candidate.getType().isPrimitive()) continue;
                candidate.setAccessible(true);
                Object value = candidate.get(owner);
                if (!candidate.getType().getName().equals(typeName)
                        && (value == null || !value.getClass().getName().equals(typeName))) continue;
                if (found != null) throw new IllegalStateException("Ambiguous original field: " + typeName);
                found = candidate;
            }
        }
        return found;
    }

    private static String message(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void notifyOperation(Object callback, int code, Object info, String text, Bundle bundle) {
        if (callback == null) return;
        MAIN.post(() -> {
            try {
                for (Method method : callback.getClass().getMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (method.getReturnType() == void.class && p.length == 4 && p[0] == int.class
                            && p[1].getName().equals(INFO) && p[2] == String.class && p[3] == Bundle.class) {
                        method.setAccessible(true);
                        method.invoke(callback, code, info, text, bundle);
                        return;
                    }
                }
                throw new NoSuchMethodException("Original memory operation callback");
            } catch (Throwable error) {
                Log.e(TAG, "Original memory callback failed", error);
                if (code != 2002 && clearScreenEffect(callback)) {
                    notifyOperation(callback, 2002, null, message(error), null);
                }
            }
        });
    }

    private static void notifySave(Object callback, int code, List<?> info, String text) {
        if (callback == null) return;
        MAIN.post(() -> {
            try {
                for (Method method : callback.getClass().getMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (method.getReturnType() == void.class && p.length == 3 && p[0] == int.class
                            && p[1] == List.class && p[2] == String.class) {
                        method.setAccessible(true);
                        method.invoke(callback, code, info, text);
                        return;
                    }
                }
                throw new NoSuchMethodException("Original memory save callback");
            } catch (Throwable error) {
                Log.e(TAG, "Original memory save callback failed", error);
            }
        });
    }

    private static void guarded(Runnable action) {
        try { action.run(); } catch (Throwable error) { Log.e(TAG, "Original memory continuation failed", error); }
    }

    private static void toast(Context context, String text) {
        if (context == null) return;
        MAIN.post(() -> {
            try { Toast.makeText(context, text, Toast.LENGTH_LONG).show(); }
            catch (Throwable error) { Log.w(TAG, "Memory error toast failed", error); }
        });
    }
}
