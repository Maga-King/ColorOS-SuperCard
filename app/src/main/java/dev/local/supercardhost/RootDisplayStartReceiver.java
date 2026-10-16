package dev.local.supercardhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Manifest DUMP permission restricts requests to privileged senders such as SystemUI. */
public final class RootDisplayStartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!AutoVirtualDisplayManager.START.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        AutoVirtualDisplayManager.start(context,
                intent.getBooleanExtra(AutoVirtualDisplayManager.PAYMENT_DEMAND, false), pending::finish);
    }
}
