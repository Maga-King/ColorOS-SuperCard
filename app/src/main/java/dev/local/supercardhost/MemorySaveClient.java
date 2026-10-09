package dev.local.supercardhost;

import android.content.BroadcastReceiver;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Relays committed original attachments to the memory app's own checked importers. */
public final class MemorySaveClient {
    private static final String ACTION = "dev.local.supercardhost.MEMORY_IMPORT";
    private static final String TARGET = "com.oplus.aimemory";
    private static final String TAG = "SuperCardMemorySave";
    private static final int MAX_IMPORT_ATTEMPTS = 3;
    private static final int MAX_READINESS_ATTEMPTS = 3;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Handler RESPONSES;
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile Bundle readiness;
    private static volatile long readinessTime;
    static {
        HandlerThread thread = new HandlerThread("SuperCardMemoryResults");
        thread.start(); RESPONSES = new Handler(thread.getLooper());
    }
    private MemorySaveClient() {}

    public static Bundle checkReady(Context context, Bundle options) {
        Bundle cached = readiness;
        if (cached != null && android.os.SystemClock.elapsedRealtime() - readinessTime < 15000) {
            if (cached.getBoolean("ready")) return null;
        }
        try {
            Bundle result = request(context, new Intent(ACTION).putExtra("op", "check"), 6000);
            readiness = result.getBoolean("ready") ? result : null;
            readinessTime = android.os.SystemClock.elapsedRealtime();
            return result.getBoolean("ready") ? null : result;
        } catch (Exception error) {
            Bundle result = new Bundle(); result.putBoolean("ready", false);
            readiness = null;
            result.putString("status", "backend_unavailable");
            result.putBoolean("requiresSetup", false);
            result.putString("error", "无法连接小布记忆，草稿已保留");
            Log.w(TAG, "Memory readiness unavailable", error);
            return result;
        }
    }

    public static void save(Context context, ClassLoader loader, String operation,
            List<?> attachments, Bundle options, Object callback) {
        List<?> copied = attachments == null ? List.of() : new ArrayList<>(attachments);
        if ("auto_save_memory".equals(operation)) {
            // The original UI owns draft files and its own persisted attachment list.
            reply(loader, callback, 0, copied, null);
            return;
        }
        if ("delete_memory".equals(operation)) {
            // Do not remove an already committed memory for a draft-only cancel.
            reply(loader, callback, 3099, copied, "已保存的记忆请在小布记忆中删除");
            return;
        }
        if (!"save_memory".equals(operation) && !"add_memory".equals(operation)) {
            reply(loader, callback, 3099, copied, "不支持的记忆保存操作");
            return;
        }
        if (copied.isEmpty()) { reply(loader, callback, 0, copied, null); return; }
        WRITER.execute(() -> {
            try {
                Bundle ready = checkReady(context, options);
                if (ready != null) throw new IllegalStateException(ready.getString("error", "小布记忆尚未就绪"));
                SharedPreferences saved = context.getSharedPreferences("supercard_memory_commits", 0);
                ArrayList<Object> pending = new ArrayList<>();
                ArrayList<String> ids = new ArrayList<>();
                for (Object attachment : copied) {
                    String id = string(attachment, "d");
                    if (id.isEmpty()) throw new IllegalStateException("记忆附件缺少标识");
                    if (saved.contains("attachment_" + id)) {
                        MemoryCommitLifecycle.onCommitted(context, loader, List.of(attachment));
                        continue;
                    }
                    if (!ids.contains(id)) { ids.add(id); pending.add(attachment); }
                }
                if (pending.isEmpty()) { reply(loader, callback, 0, copied, null); return; }
                ArrayList<Integer> types = new ArrayList<>();
                int images = 0, voices = 0;
                for (Object attachment : pending) {
                    int type = ((Number) field(attachment, "e")).intValue();
                    if (type == 101 || type == 2401) images++;
                    else if (type == 401) voices++;
                    else throw new IllegalArgumentException("这类附件暂不支持保存到小布记忆");
                    types.add(type);
                }
                if (images > 12) throw new IllegalArgumentException("一条小布记忆最多支持 12 张图片，草稿已保留");
                if (voices > 1) throw new IllegalArgumentException("一条小布记忆只支持一段语音，请先保存当前草稿");
                ArrayList<String> sortedIds = new ArrayList<>(ids);
                java.util.Collections.sort(sortedIds);
                String identity = ids.size() == 1 ? ids.get(0) : "memory-batch:" + String.join("\n", sortedIds);
                String guid = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                ArrayList<Uri> uris = new ArrayList<>();
                ArrayList<File> sources = new ArrayList<>();
                for (Object attachment : pending) {
                    String local = string(attachment, "i");
                    if (local.isEmpty() || !new File(local).isFile()) local = string(attachment, "a");
                    File source = new File(local);
                    if (!source.isFile() || source.length() == 0) throw new IllegalStateException("记忆附件文件不存在");
                    Uri uri = MemoryCaptureSupport.uriForFile(context, source);
                    context.grantUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    uris.add(uri); sources.add(source);
                }
                String sourcePkg = string(pending.get(0), "n");
                int batchType = pending.size() == 1 ? types.get(0) : 0;
                Intent request = new Intent(ACTION).putExtra("op", "import")
                        .putExtra("guid", guid).putExtra("type", batchType)
                        .putExtra("sourcePkg", sourcePkg.isEmpty() ? "com.android.systemui" : sourcePkg)
                        .putExtra("sourceName", "超级卡包")
                        .putExtra("title", string(pending.get(0), "b"))
                        .putParcelableArrayListExtra("uris", uris)
                        .putIntegerArrayListExtra("types", types);
                Bundle response = request(context, request, 25000);
                for (int attempt = 1; attempt < MAX_IMPORT_ATTEMPTS
                        && guid.equals(response.getString("guid"))
                        && "native_error".equals(response.getString("status"))
                        && response.getBoolean("complete")
                        && response.getBoolean("retryableBeforeWrite")
                        && !response.getBoolean("canDeleteSources")
                        && response.getStringArrayList("memoryIds") != null
                        && response.getStringArrayList("memoryIds").isEmpty(); attempt++) {
                    Log.i(TAG, "Retrying confirmed pre-write import failure, attempt=" + (attempt + 1)
                            + "/" + MAX_IMPORT_ATTEMPTS + ", same attachment identity");
                    response = request(context, new Intent(request).putExtra("retryPrewrite", true), 25000);
                }
                long deadline = android.os.SystemClock.elapsedRealtime() + 55000;
                while (("pending".equals(response.getString("status"))
                        || "timeout".equals(response.getString("status")))
                        && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(1000);
                    response = request(context, new Intent(ACTION).putExtra("op", "check")
                            .putExtra("guid", guid), 10000);
                    if (!guid.equals(response.getString("guid"))) {
                        throw new IllegalStateException("小布保存任务已失效，附件仍保留在草稿中");
                    }
                }
                Log.i(TAG, "Import batch types=" + types + ", status=" + response.getString("status")
                        + ", memoryIds=" + response.getStringArrayList("memoryIds"));
                ArrayList<String> memoryIds = response.getStringArrayList("memoryIds");
                if (!"saved".equals(response.getString("status"))
                        || !response.getBoolean("canDeleteSources")
                        || memoryIds == null || memoryIds.size() != 1 || memoryIds.get(0).isEmpty()) {
                    throw new IllegalStateException(response.getString("error", "小布记忆未确认整条记忆保存成功"));
                }
                SharedPreferences.Editor commits = saved.edit();
                for (String id : ids) commits.putString("attachment_" + id, response.toString());
                if (!commits.commit()) throw new IllegalStateException("记忆已保存，但无法记录附件去重状态");
                MemoryCommitLifecycle.onCommitted(context, loader, pending);
                // Retain original source files; release transfer copies after the whole batch is acknowledged.
                for (int i = 0; i < uris.size(); i++) {
                    try {
                        context.getContentResolver().delete(uris.get(i), null, null);
                        MemoryCaptureSupport.forgetUri(sources.get(i));
                    } catch (Throwable cleanup) { Log.w(TAG, "Committed transfer cleanup deferred", cleanup); }
                }
                reply(loader, callback, 0, copied, null);
                MAIN.post(() -> Toast.makeText(context, "已保存到小布记忆", Toast.LENGTH_SHORT).show());
            } catch (Throwable error) {
                Log.e(TAG, "Original attachment commit failed", error);
                String message = error.getMessage() == null ? "记忆保存失败" : error.getMessage();
                reply(loader, callback, 3099, copied, message);
                MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static Bundle request(Context context, Intent intent, long timeout) throws Exception {
        // A dynamic receiver alone cannot start a reclaimed process. Bind the
        // public service and hold its process for the whole authenticated request.
        boolean check = "check".equals(intent.getStringExtra("op"));
        int maximum = check ? MAX_READINESS_ATTEMPTS : 1;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= maximum; attempt++) {
            try (MemoryBackendConnection backend = MemoryBackendConnection.connect(context)) {
                Bundle response = sendRequest(context, intent, timeout);
                if (response != null && !response.isEmpty()) {
                    if (check) Log.i(TAG, "Backend readiness received, attempts=" + attempt
                            + ", ready=" + response.getBoolean("ready")
                            + ", requiresSetup=" + response.getBoolean("requiresSetup"));
                    // A real not-ready/setup response is authoritative, not a reason
                    // to restart the service or replay an import.
                    return response;
                }
                lastFailure = new IllegalStateException("小布记忆接入服务尚未就绪");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception error) {
                if (!check) throw error;
                lastFailure = error;
            }
            // Only a read-only check may reconnect after an empty reply, timeout
            // or bind failure. A mutable import is sent at most once here.
            if (attempt < maximum) {
                Log.i(TAG, "Reconnecting memory readiness service, attempt=" + (attempt + 1)
                        + "/" + maximum);
                Thread.sleep(200);
            }
        }
        throw lastFailure == null ? new IllegalStateException("小布记忆接入服务尚未就绪") : lastFailure;
    }

    private static Bundle sendRequest(Context context, Intent intent, long timeout) throws Exception {
        intent.setPackage(TARGET);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        BroadcastOptions options = BroadcastOptions.makeBasic(); options.setShareIdentityEnabled(true);
        context.sendOrderedBroadcast(intent, null, options.toBundle(), new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent original) {
                Bundle response = getResultExtras(false);
                result.set(response == null ? new Bundle() : new Bundle(response));
                latch.countDown();
            }
        }, RESPONSES, 0, null, null);
        if (!latch.await(timeout, TimeUnit.MILLISECONDS)) throw new IllegalStateException("等待小布记忆保存超时，草稿已保留");
        return result.get();
    }

    private static void reply(ClassLoader loader, Object callback, int code, List<?> attachments, String message) {
        if (callback == null) return;
        MAIN.post(() -> {
            try {
                Class<?> api = loader.loadClass("f6.c");
                Method match = null;
                for (Method method : api.getDeclaredMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (p.length == 3 && p[0] == int.class && p[1] == List.class && p[2] == String.class) match = method;
                }
                if (match == null) throw new NoSuchMethodException("Original save callback signature");
                match.invoke(callback, code, attachments, message);
            } catch (Throwable error) { Log.e(TAG, "Cannot deliver original save callback", error); }
        });
    }
    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static String string(Object value, String name) throws Exception {
        Object found = field(value, name); return found == null ? "" : found.toString();
    }
}
