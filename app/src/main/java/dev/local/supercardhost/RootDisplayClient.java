package dev.local.supercardhost;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
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

    public static void install(Application host) {
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
                try {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR); data.writeString(session);
                        data.writeStrongBinder(LIFETIME);
                        if (!candidate.transact(1, data, reply, 0)) throw new IllegalStateException("No handshake transaction");
                        reply.readException();
                    } finally { data.recycle(); reply.recycle(); }
                    candidate.linkToDeath(() -> {
                        synchronized (RootDisplayClient.class) {
                            if (service == candidate) { service = null; nonce = null; }
                        }
                        Log.w(TAG, "Secure display backend disconnected");
                    }, 0);
                    synchronized (RootDisplayClient.class) { service = candidate; nonce = session; }
                    Log.i(TAG, "Secure display backend connected, sender uid=0");
                } catch (Throwable error) { Log.e(TAG, "Display backend handshake failed", error); }
            }
        }, new IntentFilter(ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
    }

    public static synchronized VirtualDisplay create(String name, int width, int height, int density,
            Surface surface, int flags) {
        if (!"SuperCard_Virtual".equals(name)) throw new IllegalArgumentException("Unsupported display name");
        if (service == null || !service.isBinderAlive()) {
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
