package dev.local.supercardhost;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Starts the attachment process without opening an activity or depending on a cold provider start. */
final class MemoryTransferConnection {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TAG = "SuperCardMemoryTransfer";
    private static volatile CountDownLatch ready = new CountDownLatch(1);
    private static volatile boolean bound;
    private static volatile IBinder binder;
    private static volatile Context owner;
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            ready.countDown();
            Log.i(TAG, "Attachment service ready without activity");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            binder = null; ready = new CountDownLatch(1);
        }
        @Override public void onBindingDied(ComponentName name) {
            binder = null; bound = false; ready = new CountDownLatch(1);
            Context host = owner;
            if (host != null) {
                try { host.unbindService(this); }
                catch (IllegalArgumentException ignored) { }
                install(host);
            }
        }
        @Override public void onNullBinding(ComponentName name) {
            binder = null; bound = false;
            Log.e(TAG, "Attachment service returned an empty binding");
        }
    };
    private MemoryTransferConnection() {}

    static void install(Context host) {
        owner = host.getApplicationContext();
        MAIN.post(() -> {
            if (bound) return;
            try {
                Intent service = new Intent().setComponent(new ComponentName("dev.local.supercardhost",
                        "dev.local.supercardhost.MemoryTransferService"));
                bound = host.bindService(service, CONNECTION, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                if (!bound) Log.e(TAG, "Attachment service binding rejected");
            } catch (Exception error) { Log.e(TAG, "Cannot prepare attachment service", error); }
        });
    }

    static void ensureReady(Context host) throws Exception {
        IBinder current = binder;
        if (current != null && current.isBinderAlive()) return;
        install(host);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("附件服务正在准备，请稍后重试");
        }
        if (!ready.await(4, TimeUnit.SECONDS) || binder == null || !binder.isBinderAlive()) {
            throw new IllegalStateException("附件服务未能启动，请重试");
        }
    }
}
