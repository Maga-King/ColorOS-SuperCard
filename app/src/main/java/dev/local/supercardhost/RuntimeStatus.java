package dev.local.supercardhost;

import android.content.Context;
import android.os.Bundle;

/** Filled only by the module actually running in SystemUI; no self-hook detection needed. */
final class RuntimeStatus {
    static String framework = "", version = "";
    static boolean hooked;

    static Bundle read(Context context) {
        Bundle result = new Bundle();
        result.putString("framework", framework);
        result.putString("frameworkVersion", version);
        result.putBoolean("systemUiHook", hooked);
        result.putBoolean("rootDisplayReady", RootDisplayClient.isReady());
        result.putBundle("payTouchOverrides", PayTouchSettings.read(context));
        if (PayUserRouting.ready) result.putBundle("payUsers", PayUserSettings.read(context));
        return result;
    }
}
