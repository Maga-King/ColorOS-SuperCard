package dev.local.supercardhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only Android's protected boot/package-update broadcasts reach this entry. */
public final class RootDisplayBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        PendingResult pending = goAsync();
        AutoVirtualDisplayManager.start(context, pending::finish);
    }
}
