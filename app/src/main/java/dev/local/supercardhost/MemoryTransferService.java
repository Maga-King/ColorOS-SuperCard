package dev.local.supercardhost;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** Explicit binding lets the privileged card host prepare its attachment provider. */
public final class MemoryTransferService extends Service {
    private final Binder binder = new Binder();
    @Override public IBinder onBind(Intent intent) { return binder; }
}
