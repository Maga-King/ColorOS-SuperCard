package dev.local.supercardhost;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Captures explicit user requests and adapts their files to the original card UI. */
public final class MemoryCaptureSupport {
    public static final String RESULT = "dev.local.supercardhost.MEMORY_PHOTO_RESULT";
    private static final String TAG = "SuperCardMemoryCapture";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Uri> FILE_URIS = new ConcurrentHashMap<>();
    private static final Map<String, Photo> PHOTOS = new ConcurrentHashMap<>();
    private static Object activePlugin;
    private static ClassLoader memoryLoader;
    private static boolean receiverInstalled;

    public static synchronized void attachPlugin(Context host, Object plugin, ClassLoader loader) {
        activePlugin = plugin; memoryLoader = loader;
        if (receiverInstalled) return;
        host.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    int moduleUid = host.getPackageManager().getApplicationInfo("dev.local.supercardhost", 0).uid;
                    if (getSentFromUid() != moduleUid) throw new SecurityException("Untrusted photo result");
                    String token = intent.getStringExtra("token");
                    Photo photo = token == null ? null : PHOTOS.remove(token);
                    if (photo == null) return;
                    new Thread(() -> completePhoto(host, photo, intent.getBooleanExtra("success", false)),
                            "SuperCardPhotoResult").start();
                } catch (Throwable error) { Log.e(TAG, "Photo result rejected", error); }
            }
        }, new IntentFilter(RESULT), null, MAIN, Context.RECEIVER_EXPORTED);
        receiverInstalled = true;
    }

    public static File captureScreen(Context host) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Capture must run off main thread");
        CountDownLatch hidden = new CountDownLatch(1);
        MAIN.post(() -> {
            // Match vivo's screen-capture request: this is not a user-interactive hide.
            try { callback("requestTempHideCardPack", new Class<?>[]{boolean.class}, false); }
            catch (Exception error) { Log.w(TAG, "Cannot temporarily hide card", error); }
            MAIN.postDelayed(hidden::countDown, 350);
        });
        Object capture = null;
        boolean captured = false;
        try {
            if (!hidden.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("等待卡片隐藏超时");
            Class<?> api = Class.forName("android.window.ScreenCaptureInternal");
            Class<?> argsType = Class.forName("android.window.ScreenCaptureInternal$CaptureArgs");
            Class<?> listenerType = Class.forName("android.window.ScreenCaptureInternal$ScreenCaptureListener");
            Object builder = Class.forName("android.window.ScreenCaptureInternal$CaptureArgs$Builder")
                    .getConstructor().newInstance();
            // Keep platform defaults: secure and protected content are never requested.
            Object args = builder.getClass().getMethod("build").invoke(builder);
            Object listener = api.getMethod("createSyncCaptureListener").invoke(null);
            Class<?> wmGlobal = Class.forName("android.view.WindowManagerGlobal");
            Object wm = wmGlobal.getMethod("getWindowManagerService").invoke(null);
            Class.forName("android.view.IWindowManager").getMethod("captureDisplay", int.class, argsType, listenerType)
                    .invoke(wm, 0, args, listener);
            Method buffer = listener.getClass().getMethod("getBuffer"); buffer.setAccessible(true);
            capture = buffer.invoke(listener);
            if (capture == null) throw new IllegalStateException("当前页面无法截屏");
            if ((Boolean) capture.getClass().getMethod("containsSecureLayers").invoke(capture)) {
                throw new SecurityException("受保护页面不能保存为记忆");
            }
            Bitmap bitmap = (Bitmap) capture.getClass().getMethod("asBitmap").invoke(capture);
            if (bitmap == null) throw new IllegalStateException("截图没有有效图像");
            File output = new File(directory(host), "screen_" + UUID.randomUUID() + ".jpg");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new IllegalStateException("无法保存截图");
            }
            bitmap.recycle();
            captured = true;
            Log.i(TAG, "Screen captured; card stays hidden until original recognition handoff");
            return output;
        } finally {
            if (capture != null) {
                try {
                    Object hardware = capture.getClass().getMethod("getHardwareBuffer").invoke(capture);
                    if (hardware instanceof AutoCloseable closeable) closeable.close();
                } catch (Exception ignored) { }
            }
            // On success the original 2005 -> FileSaver -> b1 animation owns restore.
            // Restoring here exposes the card for a frame before its overlay is ready.
            // Later staging/callback errors already run the original 2002 restore path.
            if (!captured) releaseScreenHide();
        }
    }

    /** Also called if staging/authorization fails after capture but before the original overlay. */
    public static void releaseScreenHide() {
        MAIN.post(() -> {
            try {
                callback("requestRestoreCardPack", new Class<?>[0]);
                Log.i(TAG, "Card restored after screen capture pipeline failure");
            } catch (Exception error) { Log.w(TAG, "Cannot restore memory card", error); }
        });
    }

    public static Uri uriForFile(Context host, File source) throws Exception {
        String path = source.getCanonicalPath();
        Uri previous = FILE_URIS.get(path);
        if (previous != null) return previous;
        if (!source.isFile() || source.length() == 0) throw new IllegalStateException("附件文件为空");
        Uri uri = createStaging(host, source.getName());
        boolean success = false;
        try (var input = new FileInputStream(source); var output = host.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("无法写入记忆附件");
            input.transferTo(output); success = true;
        } finally {
            if (!success) host.getContentResolver().delete(uri, null, null);
        }
        host.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        FILE_URIS.put(path, uri);
        return uri;
    }

    public static void forgetUri(File source) {
        try { FILE_URIS.remove(source.getCanonicalPath()); } catch (Exception ignored) { }
    }

    private static Uri createStaging(Context host, String name) throws Exception {
        MemoryTransferConnection.ensureReady(host);
        try {
            Object user = Context.class.getMethod("getUserId").invoke(host);
            android.content.pm.ProviderInfo provider = host.getPackageManager()
                    .resolveContentProvider(MemoryTransferProvider.AUTHORITY, 0);
            Log.i(TAG, "Staging identity package=" + host.getPackageName() + ", uid="
                    + android.os.Process.myUid() + ", user=" + user + ", provider="
                    + (provider == null ? "unresolved" : provider.packageName));
        } catch (Exception error) { Log.w(TAG, "Cannot inspect staging identity", error); }
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.length() > 100) safe = safe.substring(safe.length() - 100);
        ContentValues values = new ContentValues(); values.put(OpenableColumns.DISPLAY_NAME, safe);
        Uri uri = host.getContentResolver().insert(MemoryTransferProvider.ROOT, values);
        if (uri == null) throw new IllegalStateException("无法创建记忆附件");
        return uri;
    }

    public static void takePhoto(Context host, Object actionWrapper, ClassLoader loader) throws Exception {
        Object action = null;
        for (Field field : actionWrapper.getClass().getDeclaredFields()) {
            if (field.getType() == Object.class) { field.setAccessible(true); action = field.get(actionWrapper); break; }
        }
        if (action == null) throw new IllegalStateException("拍照回调不存在");
        Object plugin = activePlugin;
        if (plugin == null) throw new IllegalStateException("记忆卡未加载");
        Field cameraField = plugin.getClass().getDeclaredField("cameraView"); cameraField.setAccessible(true);
        android.view.ViewGroup camera = (android.view.ViewGroup) cameraField.get(plugin);
        android.view.View memoryView = null;
        for (Field field : plugin.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals("com.vivo.memory.card.view.MemoryCardView")) {
                field.setAccessible(true); memoryView = (android.view.View) field.get(plugin); break;
            }
        }
        if (camera == null || memoryView == null || memoryView.getWidth() <= 0 || memoryView.getHeight() <= 0)
            throw new IllegalStateException("拍照卡片尚未完成布局");
        camera.setLayoutParams(new android.widget.FrameLayout.LayoutParams(memoryView.getWidth(), memoryView.getHeight()));
        camera.setVisibility(View.VISIBLE);
        memoryView.animate().alpha(0.7f).setDuration(350).start();
        File file = new File(directory(host), "photo_" + UUID.randomUUID() + ".jpg");
        Object originalAction = action;
        EmbeddedMemoryCamera.show(host, camera, file, new EmbeddedMemoryCamera.Callback() {
            @Override public void onSaved(File captured) {
                new Thread(() -> {
                    try {
                        Uri uri = uriForFile(host, captured);
                        completePhoto(host, new Photo(plugin, loader, originalAction, captured, uri), true);
                    } catch (Throwable error) { fail(host, error); finishPhotoUi(originalAction); }
                }, "SuperCardCameraSave").start();
            }
            @Override public void onCancelled() { finishPhotoUi(originalAction); }
            @Override public void onError(Throwable error) { fail(host, error); finishPhotoUi(originalAction); }
        });
    }

    private static void completePhoto(Context host, Photo photo, boolean success) {
        try {
            if (success) {
                try (var input = host.getContentResolver().openInputStream(photo.uri);
                     var output = new FileOutputStream(photo.file)) {
                    if (input == null) throw new IllegalStateException("相机没有返回照片");
                    input.transferTo(output);
                }
                if (photo.file.length() == 0) throw new IllegalStateException("相机返回了空文件");
                FILE_URIS.put(photo.file.getCanonicalPath(), photo.uri);
                host.grantUriPermission("com.android.systemui", photo.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Class<?> type = photo.loader.loadClass("com.vivo.memory.card.ipc.bean.MemoryCardInfo");
                Object info = type.getConstructor().newInstance();
                set(info, "a", photo.file.getAbsolutePath()); set(info, "i", photo.file.getAbsolutePath());
                set(info, "b", "拍照记忆"); set(info, "c", System.currentTimeMillis());
                set(info, "d", UUID.randomUUID().toString()); set(info, "e", 101);
                set(info, "g", photo.uri.toString()); set(info, "n", "com.oplus.camera");
                MAIN.post(() -> {
                    try {
                        Object view = null;
                        for (Field field : photo.plugin.getClass().getDeclaredFields()) {
                            if (field.getType().getName().equals("com.vivo.memory.card.view.MemoryCardView")) {
                                field.setAccessible(true); view = field.get(photo.plugin); break;
                            }
                        }
                        if (view == null) throw new IllegalStateException("原版记忆列表不存在");
                        Method attach = null;
                        for (Method method : view.getClass().getDeclaredMethods()) {
                            Class<?>[] p = method.getParameterTypes();
                            if (p.length == 3 && p[0] == type && p[1] == boolean.class
                                    && method.getReturnType() == void.class) {
                                if (attach != null) throw new IllegalStateException("附件入口不唯一");
                                attach = method;
                            }
                        }
                        if (attach == null) throw new NoSuchMethodException("原版附件入口");
                        attach.setAccessible(true); attach.invoke(view, info, true, null);
                    } catch (Throwable error) { fail(host, error); }
                });
            } else host.getContentResolver().delete(photo.uri, null, null);
        } catch (Throwable error) { fail(host, error); }
        finally {
            MAIN.post(() -> {
                try {
                    Method finished = photo.action.getClass().getDeclaredMethod("a");
                    finished.setAccessible(true); finished.invoke(photo.action);
                } catch (Throwable error) { Log.w(TAG, "Photo card restore failed", error); }
            });
        }
    }

    private static void finishPhotoUi(Object action) {
        MAIN.post(() -> {
            try {
                Method finished = action.getClass().getDeclaredMethod("a");
                finished.setAccessible(true); finished.invoke(action);
            } catch (Throwable error) { Log.w(TAG, "Cannot restore camera container", error); }
        });
    }

    private static void callback(String name, Class<?>[] params, Object... arguments) throws Exception {
        Object plugin = activePlugin;
        if (plugin == null) throw new IllegalStateException("记忆卡未加载");
        for (Field field : plugin.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals("a6.r")) {
                field.setAccessible(true); Object action = field.get(plugin);
                if (action == null) continue;
                for (Field item : action.getClass().getDeclaredFields()) {
                    if (item.getType().getName().endsWith("MemoryCardPlugin$CardPluginCallback")) {
                        item.setAccessible(true); Object callback = item.get(action);
                        item.getType().getMethod(name, params).invoke(callback, arguments);
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("记忆卡框架回调不存在");
    }
    private static File directory(Context host) {
        File result = new File(host.getFilesDir(), "supercard-memory");
        if (!result.isDirectory() && !result.mkdirs()) throw new IllegalStateException("无法创建记忆目录");
        return result;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void fail(Context host, Throwable error) {
        Log.e(TAG, "Memory capture failed", error);
        MAIN.post(() -> Toast.makeText(host, "记忆采集失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
    }
    private record Photo(Object plugin, ClassLoader loader, Object action, File file, Uri uri) {}
}
