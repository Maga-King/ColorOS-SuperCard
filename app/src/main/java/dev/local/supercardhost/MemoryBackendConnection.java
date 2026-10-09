package dev.local.supercardhost;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Holds AIMemory's public main-process service only while a request is running. */
final class MemoryBackendConnection implements AutoCloseable {
    private static final ExecutorService CALLBACKS = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SuperCardMemoryBinding");
        thread.setDaemon(true);
        return thread;
    });
    private final Context context;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile IBinder binder;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            ready.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { binder = null; }
        @Override public void onBindingDied(ComponentName name) { binder = null; ready.countDown(); }
        @Override public void onNullBinding(ComponentName name) { ready.countDown(); }
    };

    private MemoryBackendConnection(Context context) { this.context = context; }

    static MemoryBackendConnection connect(Context context) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("后台连接必须在工作线程执行");
        MemoryBackendConnection result = new MemoryBackendConnection(context);
        try {
            Intent intent = new Intent().setComponent(new ComponentName("com.oplus.aimemory",
                    "org.hapjs.features.channel.ChannelService"));
            result.bound = context.bindService(intent,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, CALLBACKS, result.connection);
            if (!result.bound || !result.ready.await(6, TimeUnit.SECONDS)
                    || result.binder == null || !result.binder.isBinderAlive())
                throw new IllegalStateException("小布记忆后台连接暂不可用");
            // No Messenger messages are sent. Binding completes after Application
            // initialization, so the authenticated import receiver is now registered.
            Log.i("SuperCardMemorySave", "Native memory service connected without activity");
            return result;
        } catch (Exception error) {
            result.close();
            throw error;
        }
    }

    @Override public void close() {
        if (bound) {
            bound = false;
            try { context.unbindService(connection); }
            catch (IllegalArgumentException ignored) { }
        }
    }
}
