package dev.local.supercardhost;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.SecureRandom;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * Root app_process endpoint for the original SuperCard secure virtual display.
 *
 * Only SystemUI may connect. The endpoint creates one specifically named display;
 * it does not expose arbitrary system service calls or alter application security
 * flags. The client owns the display callback token after creation, while this
 * process retains the real VirtualDisplay and releases it on client death.
 */
public final class RootDisplayDaemon extends Binder {
    private static final String TAG = "SuperCardRootDisplay";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ACTION = "dev.local.supercardhost.ROOT_DISPLAY_SERVICE";
    private static final String DESCRIPTOR = "dev.local.supercardhost.RootDisplay.v1";
    private static final String DISPLAY_NAME = "SuperCard_Virtual";
    private static final int HELLO = IBinder.FIRST_CALL_TRANSACTION;
    private static final int CREATE = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int RELEASE = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int ORIGINAL_DISPLAY_FLAGS = 19917;
    private static final long ANNOUNCE_INTERVAL_MS = 2000;

    private final Context context;
    private final DisplayManager displayManager;
    private final Handler main;
    private final String nonce;
    private final int systemUiUid;
    private final int maximumEdge;
    private final int maximumDensity;
    private final Object lock = new Object();
    private final Bundle announcementOptions;
    private final Method sendBroadcastWithOptions;
    private final IBinder.DeathRecipient clientDeath = this::onClientDeath;
    private final Runnable announceAgain = this::announce;

    // Access to these fields is serialized by lock, including Binder thread calls.
    private IBinder clientLifetime;
    private VirtualDisplay activeDisplay;
    private Surface receivedSurface;
    private int activeDisplayId = Display.INVALID_DISPLAY;
    private boolean closed;

    private RootDisplayDaemon(Context context, String nonce) throws Exception {
        this.context = context;
        this.nonce = nonce;
        this.main = new Handler(Looper.getMainLooper());
        this.systemUiUid = context.getPackageManager().getPackageUid(SYSTEM_UI, 0);
        if (systemUiUid <= 0) throw new IllegalStateException("SystemUI UID unavailable");
        this.displayManager = context.getSystemService(DisplayManager.class);
        if (displayManager == null) throw new IllegalStateException("DisplayManager unavailable");

        Display physicalDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (physicalDisplay == null) throw new IllegalStateException("Physical display unavailable");
        DisplayMetrics metrics = new DisplayMetrics();
        physicalDisplay.getRealMetrics(metrics);
        this.maximumEdge = Math.max(metrics.widthPixels, metrics.heightPixels);
        this.maximumDensity = metrics.densityDpi;
        if (maximumEdge <= 0 || maximumDensity <= 0) {
            throw new IllegalStateException("Physical display bounds unavailable");
        }

        // Sharing the real sender UID lets the registered SystemUI receiver reject
        // forged announcements before it accepts the Binder or nonce.
        Class<?> optionsClass = Class.forName("android.app.BroadcastOptions");
        Object options = optionsClass.getMethod("makeBasic").invoke(null);
        optionsClass.getMethod("setShareIdentityEnabled", boolean.class).invoke(options, true);
        this.announcementOptions = (Bundle) optionsClass.getMethod("toBundle").invoke(options);
        this.sendBroadcastWithOptions = Context.class.getMethod("sendBroadcast",
                Intent.class, String.class, Bundle.class);
        attachInterface(null, DESCRIPTOR);
    }

    /** Launch using CLASSPATH=host.apk app_process / ...RootDisplayDaemon [nonce]. */
    public static void main(String[] args) {
        if (Process.myUid() != 0) {
            Log.e(TAG, "Refusing to start without root UID");
            System.exit(1);
            return;
        }
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            String nonce = args.length == 0 ? newNonce() : args[0];
            if (!nonce.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("Nonce must contain 32 hexadecimal characters");
            }

            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThread.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object thread = systemMain.invoke(null);
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context context = (Context) getSystemContext.invoke(thread);
            RootDisplayDaemon daemon = new RootDisplayDaemon(context, nonce);
            Log.i(TAG, "Root secure display endpoint ready for SystemUI UID " + daemon.systemUiUid);
            daemon.main.post(daemon.announceAgain);
            Looper.loop();
        } catch (Throwable failure) {
            Log.e(TAG, "Root secure display endpoint failed to start", unwrap(failure));
            System.exit(1);
        }
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(32);
        for (byte element : bytes) {
            int unsigned = element & 255;
            value.append(Character.forDigit(unsigned >>> 4, 16));
            value.append(Character.forDigit(unsigned & 15, 16));
        }
        return value.toString();
    }

    private void announce() {
        synchronized (lock) {
            if (closed || clientLifetime != null) return;
        }
        try {
            Bundle extras = new Bundle();
            extras.putBinder("service", this);
            extras.putString("nonce", nonce);
            Intent announcement = new Intent(ACTION).setPackage(SYSTEM_UI).putExtras(extras);
            sendBroadcastWithOptions.invoke(context, announcement,
                    "android.permission.DUMP", announcementOptions);
        } catch (Throwable failure) {
            Log.e(TAG, "Could not announce secure display endpoint", unwrap(failure));
        }
        synchronized (lock) {
            if (!closed && clientLifetime == null) {
                main.postDelayed(announceAgain, ANNOUNCE_INTERVAL_MS);
            }
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code != HELLO && code != CREATE && code != RELEASE) {
            return super.onTransact(code, data, reply, flags);
        }
        // These operations are synchronous so that failure cannot be mistaken for
        // successful display creation. A one-way call never mutates state.
        if (reply == null || (flags & IBinder.FLAG_ONEWAY) != 0) return false;
        try {
            data.enforceInterface(DESCRIPTOR);
            if (Binder.getCallingUid() != systemUiUid) {
                throw new SecurityException("Caller is not SystemUI");
            }
            if (!nonce.equals(data.readString())) {
                throw new SecurityException("Session nonce does not match");
            }
            if (code == HELLO) {
                handleHello(data, reply);
            } else if (code == CREATE) {
                handleCreate(data, reply);
            } else {
                handleRelease(data, reply);
            }
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            Log.e(TAG, "Secure display transaction " + code + " failed", cause);
            // A reflective/system failure may not be an exception Parcel can
            // encode; keep the failure explicit without killing the Binder pool.
            Exception error;
            if (cause instanceof SecurityException) {
                error = (SecurityException) cause;
            } else if (cause instanceof IllegalArgumentException) {
                error = (IllegalArgumentException) cause;
            } else if (cause instanceof IllegalStateException) {
                error = (IllegalStateException) cause;
            } else {
                error = new IllegalStateException("Secure display operation failed: "
                        + cause.getClass().getSimpleName());
            }
            reply.setDataSize(0);
            reply.setDataPosition(0);
            reply.writeException(error);
        }
        return true;
    }

    private void handleHello(Parcel data, Parcel reply) throws RemoteException {
        IBinder lifetime = data.readStrongBinder();
        data.enforceNoDataAvail();
        if (lifetime == null || !lifetime.isBinderAlive()) {
            throw new IllegalArgumentException("Client lifetime Binder is unavailable");
        }
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Endpoint is closed");
            if (clientLifetime != null && !clientLifetime.equals(lifetime)) {
                throw new SecurityException("Endpoint already has a client");
            }
            if (clientLifetime == null) {
                lifetime.linkToDeath(clientDeath, 0);
                clientLifetime = lifetime;
                main.removeCallbacks(announceAgain);
                Log.i(TAG, "SystemUI secure display client connected");
            }
        }
        reply.writeNoException();
    }

    private void handleCreate(Parcel data, Parcel reply) throws Exception {
        String name = data.readString();
        int width = data.readInt();
        int height = data.readInt();
        int density = data.readInt();
        Surface surface = data.readTypedObject(Surface.CREATOR);
        boolean retained = false;
        try {
            int flags = data.readInt();
            data.enforceNoDataAvail();
            if (!DISPLAY_NAME.equals(name)) throw new IllegalArgumentException("Unexpected display name");
            if (width <= 0 || height <= 0 || width > maximumEdge || height > maximumEdge) {
                throw new IllegalArgumentException("Display dimensions exceed physical screen bounds");
            }
            if (density <= 0 || density > maximumDensity) {
                throw new IllegalArgumentException("Display density exceeds physical screen density");
            }
            if (surface == null || !surface.isValid()) {
                throw new IllegalArgumentException("A valid client Surface is required");
            }
            if ((flags & ~ORIGINAL_DISPLAY_FLAGS) != 0
                    || (flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) == 0) {
                throw new IllegalArgumentException("Only original secure display flags are permitted");
            }

            synchronized (lock) {
                requireClientLocked();
                if (activeDisplay != null) {
                    throw new IllegalStateException("Release the active display before creating another");
                }
                long identity = Binder.clearCallingIdentity();
                VirtualDisplay created = null;
                try {
                    created = displayManager.createVirtualDisplay(name,
                            width, height, density, surface, flags);
                    if (created == null || created.getDisplay() == null) {
                        throw new IllegalStateException("DisplayManager did not create a display");
                    }
                    IBinder callback = callbackBinder(created);
                    int displayId = created.getDisplay().getDisplayId();
                    if (displayId <= Display.DEFAULT_DISPLAY) {
                        throw new IllegalStateException("DisplayManager returned an invalid display ID");
                    }
                    activeDisplay = created;
                    activeDisplayId = displayId;
                    receivedSurface = surface;
                    retained = true;
                    reply.writeNoException();
                    reply.writeInt(displayId);
                    reply.writeStrongBinder(callback);
                    Log.i(TAG, "Created secure SuperCard display " + displayId
                            + " " + width + "x" + height + " density=" + density);
                } catch (Throwable failure) {
                    if (created != null) {
                        try {
                            created.release();
                        } catch (Throwable releaseFailure) {
                            Log.w(TAG, "Failed to release an incomplete display", releaseFailure);
                        }
                    }
                    activeDisplay = null;
                    activeDisplayId = Display.INVALID_DISPLAY;
                    receivedSurface = null;
                    retained = false;
                    throw failure;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        } finally {
            if (!retained && surface != null) surface.release();
        }
    }

    private void handleRelease(Parcel data, Parcel reply) {
        int displayId = data.readInt();
        data.enforceNoDataAvail();
        synchronized (lock) {
            requireClientLocked();
            if (activeDisplay == null) {
                // DMS may already have released the callback token client-side.
                reply.writeNoException();
                return;
            }
            if (displayId != activeDisplayId) {
                throw new IllegalArgumentException("Display ID does not match the active display");
            }
            long identity = Binder.clearCallingIdentity();
            try {
                releaseDisplayLocked();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        reply.writeNoException();
    }

    private void requireClientLocked() {
        if (closed || clientLifetime == null || !clientLifetime.isBinderAlive()) {
            throw new IllegalStateException("Connect a live SystemUI client first");
        }
    }

    private static IBinder callbackBinder(VirtualDisplay display) throws Exception {
        // Present on the target ColorOS framework; the field fallback also
        // supports framework revisions that do not expose the hidden getter.
        try {
            Method getter = VirtualDisplay.class.getDeclaredMethod("getToken");
            getter.setAccessible(true);
            Object callback = getter.invoke(display);
            IBinder token = callback instanceof IInterface ? ((IInterface) callback).asBinder()
                    : callback instanceof IBinder ? (IBinder) callback : null;
            if (token != null) return token;
        } catch (NoSuchMethodException ignored) {
            // Continue with the framework's callback field below.
        }
        Field callbackField = null;
        for (Class<?> type = display.getClass(); type != null; type = type.getSuperclass()) {
            try {
                callbackField = type.getDeclaredField("mToken");
                break;
            } catch (NoSuchFieldException ignored) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().getName().equals(
                            "android.hardware.display.IVirtualDisplayCallback")) {
                        callbackField = field;
                        break;
                    }
                }
                if (callbackField != null) break;
            }
        }
        if (callbackField == null) throw new NoSuchFieldException("VirtualDisplay callback token");
        callbackField.setAccessible(true);
        Object callback = callbackField.get(display);
        IBinder token = callback instanceof IInterface ? ((IInterface) callback).asBinder()
                : callback instanceof IBinder ? (IBinder) callback : null;
        if (token == null) throw new IllegalStateException("VirtualDisplay callback token is unavailable");
        return token;
    }

    private void onClientDeath() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            clientLifetime = null;
            main.removeCallbacks(announceAgain);
            long identity = Binder.clearCallingIdentity();
            try {
                releaseDisplayLocked();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        Log.i(TAG, "SystemUI client died; secure displays released");
        // The DMS callback Binder is owned by this process, so process death also
        // removes a display if explicit cleanup encountered a service failure.
        main.post(() -> System.exit(0));
    }

    private void releaseDisplayLocked() {
        VirtualDisplay display = activeDisplay;
        Surface surface = receivedSurface;
        int displayId = activeDisplayId;
        activeDisplay = null;
        receivedSurface = null;
        activeDisplayId = Display.INVALID_DISPLAY;
        try {
            if (display != null) display.release();
        } catch (Throwable failure) {
            Log.w(TAG, "Could not explicitly release secure display " + displayId, failure);
        } finally {
            if (surface != null) surface.release();
        }
        if (display != null) Log.i(TAG, "Released secure SuperCard display " + displayId);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException
                && ((InvocationTargetException) failure).getTargetException() != null) {
            failure = ((InvocationTargetException) failure).getTargetException();
        }
        return failure;
    }
}
