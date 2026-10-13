package dev.local.supercardhost;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** Short privileged binding keeps the app runnable while its root endpoint starts. */
public final class RootDisplayStartService extends Service {
    private final IBinder token = new Binder();

    @Override public IBinder onBind(Intent intent) {
        AutoVirtualDisplayManager.start(this, null);
        return token;
    }
}
