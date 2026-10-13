package dev.local.supercardhost;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.Surface;
import java.lang.reflect.Method;

/** Narrow root backend for the original secure payment display; no payment data is handled here. */
public final class RootDisplayClient {
    private static final String ACTION = "dev.local.supercardhost.ROOT_DISPLAY_SERVICE";
    private static final String DESCRIPTOR = "dev.local.supercardhost.RootDisplay.v1";
    private static final String TAG = "SuperCardRootDisplay";
    private static final IBinder LIFETIME = new Binder();
    private static IBinder service;
    private static String nonce;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Application host;
    private static boolean starterBound;
    private static final ServiceConnection STARTER = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "APK starter service connected");
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
        @Override public void onBindingDied(ComponentName name) { releaseStarter(); requestBackend(); }
        @Override public void onNullBinding(ComponentName name) { releaseStarter(); }
    };
    private static final Runnable RELEASE_STARTER = RootDisplayClient::releaseStarter;

    public static void install(Application host) {
        if (RootDisplayClient.host != null) return;
        RootDisplayClient.host = host;
        host.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSentFromUid() != 0) {
                    Log.w(TAG, "Rejected display backend announcement from uid=" + getSentFromUid());
                    return;
                }
                Bundle extras = intent.getExtras();
                IBinder candidate = extras == null ? null : extras.getBinder("service");
                String session = intent.getStringExtra("nonce");
                if (candidate == null || session == null || !session.matches("[0-9a-f]{32}")) return;
                synchronized (RootDisplayClient.class) {
                    if (candidate.equals(service) && session.equals(nonce)) return;
                }
                try {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR); data.writeString(session);
                        data.writeStrongBinder(LIFETIME);
                        if (!candidate.transact(1, data, reply, 0)) throw new IllegalStateException("No handshake transaction");
                        reply.readException();
                    } finally { data.recycle(); reply.recycle(); }
                    candidate.linkToDeath(() -> {
                        boolean disconnected;
                        synchronized (RootDisplayClient.class) {
                            disconnected = service == candidate;
                            if (disconnected) { service = null; nonce = null; }
                        }
                        if (!disconnected) return;
                        Log.w(TAG, "Secure display backend disconnected");
                        MAIN.post(() -> {
                            PluginRuntime.setPaymentSecure(false);
                            requestBackend();
                        });
                    }, 0);
                    synchronized (RootDisplayClient.class) { service = candidate; nonce = session; }
                    Log.i(TAG, "Secure display backend connected, sender uid=0");
                    releaseStarter();
                } catch (Throwable error) { Log.e(TAG, "Display backend handshake failed", error); }
            }
        }, new IntentFilter(ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        requestBackend();
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (RootDisplayClient.class) {
                    if (service == null || !service.isBinderAlive()) requestBackend();
                }
                MAIN.postDelayed(this, 15_000);
            }
        }, 15_000);
    }

    public static synchronized boolean isReady() {
        return service != null && service.isBinderAlive();
    }

    public static void requestBackend() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(RootDisplayClient::requestBackend);
            return;
        }
        if (host == null) return;
        if (isReady() || starterBound) return;
        try {
            starterBound = host.bindService(new Intent().setClassName(
                    AutoVirtualDisplayManager.PACKAGE,
                    AutoVirtualDisplayManager.PACKAGE + ".RootDisplayStartService"),
                    STARTER, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            Log.i(TAG, "Requested APK starter binding, accepted=" + starterBound);
            if (starterBound) MAIN.postDelayed(RELEASE_STARTER, 12_000);
        } catch (Throwable error) { Log.w(TAG, "Cannot bind APK root starter", error); }
    }

    private static void releaseStarter() {
        MAIN.removeCallbacks(RELEASE_STARTER);
        if (!starterBound) return;
        starterBound = false;
        try { host.unbindService(STARTER); }
        catch (IllegalArgumentException ignored) { }
    }

    public static synchronized VirtualDisplay create(String name, int width, int height, int density,
            Surface surface, int flags) {
        if (!"SuperCard_Virtual".equals(name)) throw new IllegalArgumentException("Unsupported display name");
        if (service == null || !service.isBinderAlive()) {
            requestBackend();
            throw new IllegalStateException("Secure payment display backend is not connected");
        }
        flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
        PluginRuntime.setPaymentSecure(true);
        int displayId = -1;
        try {
            IBinder callback;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR); data.writeString(nonce);
                data.writeString(name); data.writeInt(width); data.writeInt(height); data.writeInt(density);
                data.writeTypedObject(surface, 0); data.writeInt(flags);
                if (!service.transact(2, data, reply, 0)) throw new IllegalStateException("No create transaction");
                reply.readException(); displayId = reply.readInt(); callback = reply.readStrongBinder();
            } finally { data.recycle(); reply.recycle(); }
            if (displayId < 0 || callback == null) throw new IllegalStateException("Root display was not created");
            Class<?> globalType = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Class<?> callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
            Class<?> stubType = Class.forName("android.hardware.display.IVirtualDisplayCallback$Stub");
            Object token = stubType.getMethod("asInterface", IBinder.class).invoke(null, callback);
            Method instance = globalType.getDeclaredMethod("getInstance"); instance.setAccessible(true);
            Object global = instance.invoke(null);
            VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(name, width, height, density)
                    .setSurface(surface).setFlags(flags).build();
            Method wrap = globalType.getDeclaredMethod("createVirtualDisplayWrapper",
                    VirtualDisplayConfig.class, callbackType, int.class);
            wrap.setAccessible(true);
            VirtualDisplay result = (VirtualDisplay) wrap.invoke(global, config, token, displayId);
            if (result == null) throw new IllegalStateException("Cannot wrap root display " + displayId);
            Log.i(TAG, "Original secure payment display ready id=" + displayId + ", size=" + width + "x" + height + ", flags=" + flags);
            return result;
        } catch (Throwable error) {
            if (displayId >= 0) releaseFailedDisplay(displayId);
            PluginRuntime.setPaymentSecure(false);
            throw new IllegalStateException("Cannot create secure payment display", error);
        }
    }

    public static synchronized void released(int id) {
        if (service != null && service.isBinderAlive()) releaseFailedDisplay(id);
    }

    private static void releaseFailedDisplay(int id) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(nonce); data.writeInt(id);
            service.transact(3, data, reply, 0); reply.readException();
        } catch (Throwable error) { Log.w(TAG, "Cleanup failed for display " + id, error); }
        finally { data.recycle(); reply.recycle(); }
    }
}
