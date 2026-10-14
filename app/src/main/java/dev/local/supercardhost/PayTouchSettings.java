package dev.local.supercardhost;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;

/** Per-function opt-ins. An absent setting preserves the original page whitelist. */
public final class PayTouchSettings {
    private static final String PREFIX = "supercard_pay_touch_";
    public static final String[] FUNCTIONS = {"wx_scan", "alipay_scan", "wx_pay", "alipay_pay",
            "alipay_ride", "wx_ride", "wx2_scan", "wx2_pay", "alipay_health_code"};

    private PayTouchSettings() { }

    public static boolean supported(String function) {
        for (String value : FUNCTIONS) if (value.equals(function)) return true;
        return false;
    }

    public static boolean enabled(Context context, String function) {
        return supported(function) && Settings.Secure.getInt(context.getContentResolver(),
                PREFIX + function, 0) == 1;
    }

    static void save(Context context, String function, boolean value) {
        if (!supported(function)) throw new IllegalArgumentException("Unknown PAY function");
        if (!Settings.Secure.putInt(context.getContentResolver(), PREFIX + function, value ? 1 : 0))
            throw new IllegalStateException("Cannot save PAY touch setting");
    }

    static Bundle read(Context context) {
        Bundle values = new Bundle();
        for (String function : FUNCTIONS) values.putBoolean(function, enabled(context, function));
        return values;
    }

    static String targetPackage(String function) {
        if (!supported(function)) return null;
        return function.startsWith("wx") ? "com.tencent.mm" : "com.eg.android.AlipayGphone";
    }
}
