package dev.local.supercardhost;

import android.app.Activity;
import android.app.Application;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small configuration protocol shared by SystemUI and the ColorOS settings integration. */
public final class CardConfiguration {
    private static final String ACTION = "dev.local.supercardhost.CONFIGURATION";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String FLOAT_SETTINGS_DEADLINE = "openFloatSettingsDeadlineElapsed";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public interface Callback { void complete(Bundle state, String error); }

    public static void install(Application host) throws Exception {
        int sidebarUid = host.getPackageManager().getPackageUid("com.coloros.smartsidebar", 0);
        int moduleUid = host.getPackageManager().getPackageUid("dev.local.supercardhost", 0);
        host.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int uid = getSentFromUid();
                if (uid != sidebarUid && uid != moduleUid) {
                    Log.w("SuperCardSettings", "Rejected configuration sender uid=" + uid);
                    return;
                }
                try {
                    apply(host, intent);
                    Bundle result;
                    if ("openFloatSettings".equals(intent.getStringExtra("operation"))) {
                        result = new Bundle(); result.putBoolean("opened", true);
                    } else result = read(host);
                    setResultCode(Activity.RESULT_OK); setResultExtras(result);
                } catch (Throwable error) {
                    Bundle failed = new Bundle(); failed.putString("error", error.toString());
                    setResultCode(Activity.RESULT_CANCELED); setResultExtras(failed);
                    Log.e("SuperCardSettings", "Configuration request failed", error);
                }
            }
        }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    public static void request(Context context, String operation, Bundle values, Callback callback) {
        Intent intent = new Intent(ACTION).setPackage(SYSTEM_UI).putExtra("operation", operation);
        if (values != null) intent.putExtras(values);
        if ("openFloatSettings".equals(operation))
            intent.putExtra(FLOAT_SETTINGS_DEADLINE, SystemClock.elapsedRealtime() + 5000);
        BroadcastOptions options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true);
        // Bound read/ephemeral-preview and fixed settings-launch calls. A late callback after a service
        // restart must not wedge the client's querying/previewSending flags.
        boolean bounded = callback != null && ("query".equals(operation) || "preview".equals(operation)
                || "openFloatSettings".equals(operation));
        AtomicBoolean completed = new AtomicBoolean();
        Runnable timeout = () -> {
            if (completed.compareAndSet(false, true)) callback.complete(null,
                    "openFloatSettings".equals(operation) ? "卡包浮标设置启动超时" : "卡包服务读取超时");
        };
        if (bounded) MAIN.postDelayed(timeout, 5000);
        try {
            context.sendOrderedBroadcast(intent, null, options.toBundle(), new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent result) {
                if (!completed.compareAndSet(false, true)) return;
                if (bounded) MAIN.removeCallbacks(timeout);
                Bundle state = getResultExtras(false);
                if (callback != null) callback.complete(state,
                        getResultCode() == Activity.RESULT_OK && state != null ? null
                        : state == null ? "卡包服务尚未连接" : state.getString("error", "设置未保存"));
            }
            }, null, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException | Error error) {
            completed.set(true);
            if (bounded) MAIN.removeCallbacks(timeout);
            throw error;
        }
    }

    private static void apply(Context context, Intent intent) {
        String operation = intent.getStringExtra("operation");
        if ("query".equals(operation)) return;
        if ("openFloatSettings".equals(operation)) {
            // Only this fixed destination is accepted. No external component, Intent, flags,
            // or arbitrary extras are forwarded from the authenticated request.
            long remaining = intent.getLongExtra(FLOAT_SETTINGS_DEADLINE, 0) - SystemClock.elapsedRealtime();
            if (remaining <= 0 || remaining > 5000)
                throw new IllegalArgumentException("Float-bar settings launch expired");
            Intent settings = new Intent().setClassName("com.coloros.smartsidebar",
                    "com.oplus.smartsidebar.settings.FloatBarSettingsActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("CARD_FLOATBAR_MODE", true)
                    .putExtra("dev.local.supercardhost.CARD_FLOATBAR_MODE", true);
            context.startActivity(settings);
            return;
        }
        if ("preview".equals(operation)) {
            // Complete snapshots avoid mixing unsaved UI values with saved ones.
            // Sender UID validation has already run in the registered receiver.
            if (!intent.hasExtra("top") || !intent.hasExtra("bottom")
                    || !intent.hasExtra("handleOpacity") || !intent.hasExtra("handleVisible")
                    || !(intent.getExtras().get("handleVisible") instanceof Boolean)) {
                throw new IllegalArgumentException("Incomplete gesture preview");
            }
            GestureHandle.setPreview(intent.getFloatExtra("top", Float.NaN),
                    intent.getFloatExtra("bottom", Float.NaN),
                    intent.getFloatExtra("handleOpacity", Float.NaN),
                    intent.getBooleanExtra("handleVisible", false));
            return;
        }
        if ("previewEnd".equals(operation)) {
            GestureHandle.clearPreview();
            return;
        }
        if ("enabled".equals(operation)) {
            put(context, "card_service_total_switch", intent.getBooleanExtra("value", false) ? 1 : 0);
        } else if ("unlocked".equals(operation)) {
            String old = Settings.Secure.getString(context.getContentResolver(), "navigation_gesture_right_side");
            String first = old == null || old.isEmpty() ? "side_back" : old.split(";", -1)[0];
            String side = first + ";" + (intent.getBooleanExtra("value", false) ? "side_card" : "side_back");
            if (!Settings.Secure.putString(context.getContentResolver(), "navigation_gesture_right_side", side)) {
                throw new IllegalStateException("Cannot save unlocked gesture");
            }
        } else if ("lockStyle".equals(operation)) {
            int style = intent.getIntExtra("value", -1);
            if (style < 0 || style > 3) throw new IllegalArgumentException("Invalid lock style");
            put(context, "bbk_lock_disable_card_slide_setting", style == 0 ? 0 : 1);
            put(context, "bbk_lock_disable_card_slide_open_style", style);
        } else if ("handleVisible".equals(operation)) {
            put(context, "supercard_handle_visible", intent.getBooleanExtra("value", true) ? 1 : 0);
        } else if ("handleAutoHide".equals(operation)) {
            put(context, "supercard_handle_auto_hide", intent.getBooleanExtra("value", true) ? 1 : 0);
        } else if ("handleOpacity".equals(operation)) {
            float value = intent.getFloatExtra("value", Float.NaN);
            if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid handle opacity");
            put(context, "supercard_handle_opacity", Math.round(value * 100));
        } else if ("range".equals(operation)) {
            float top = intent.getFloatExtra("top", Float.NaN), bottom = intent.getFloatExtra("bottom", Float.NaN);
            if (!Float.isFinite(top) || !Float.isFinite(bottom) || top < 0 || bottom > 1
                    || top >= bottom || bottom - top + .000001f < .04f) {
                throw new IllegalArgumentException("Invalid gesture range");
            }
            DisplayMetrics metrics = metrics(context);
            String range = (metrics.widthPixels - Math.round(24 * metrics.density)) + ";"
                    + Math.round(top * metrics.heightPixels) + ";" + metrics.widthPixels + ";"
                    + Math.round(bottom * metrics.heightPixels);
            if (!Settings.Secure.putString(context.getContentResolver(), "navigation_slide_card_range", range)) {
                throw new IllegalStateException("Cannot save gesture range");
            }
        } else throw new IllegalArgumentException("Unknown configuration operation");
        GestureConfigBridge.refresh();
        GestureHandle.reveal();
    }

    private static void put(Context context, String key, int value) {
        if (!Settings.Secure.putInt(context.getContentResolver(), key, value)) {
            throw new IllegalStateException("Cannot save " + key);
        }
    }

    public static Bundle read(Context context) {
        Bundle state = new Bundle();
        state.putBoolean("enabled", Settings.Secure.getInt(context.getContentResolver(), "card_service_total_switch", 1) == 1);
        String side = Settings.Secure.getString(context.getContentResolver(), "navigation_gesture_right_side");
        state.putBoolean("unlocked", side == null || side.contains("side_card"));
        state.putBoolean("handleVisible", Settings.Secure.getInt(context.getContentResolver(), "supercard_handle_visible", 1) == 1);
        state.putBoolean("handleAutoHide", Settings.Secure.getInt(context.getContentResolver(), "supercard_handle_auto_hide", 1) == 1);
        state.putFloat("handleOpacity", Math.max(0, Math.min(100,
                Settings.Secure.getInt(context.getContentResolver(), "supercard_handle_opacity", 40))) / 100f);
        int style = Settings.Secure.getInt(context.getContentResolver(), "bbk_lock_disable_card_slide_open_style", 0);
        state.putInt("lockStyle", Settings.Secure.getInt(context.getContentResolver(), "bbk_lock_disable_card_slide_setting", 0) == 1 ? style : 0);
        DisplayMetrics metrics = metrics(context);
        state.putInt("screenWidth", metrics.widthPixels); state.putInt("screenHeight", metrics.heightPixels);
        float top = .76f, bottom = .98f;
        String range = Settings.Secure.getString(context.getContentResolver(), "navigation_slide_card_range");
        if (range != null) try {
            String[] pieces = range.split(";");
            float source = Float.parseFloat(pieces[2]);
            float scale = metrics.widthPixels / source / metrics.heightPixels;
            float parsedTop = Float.parseFloat(pieces[1]) * scale;
            float parsedBottom = Float.parseFloat(pieces[3]) * scale;
            if (Float.isFinite(parsedTop) && Float.isFinite(parsedBottom) && parsedTop >= 0
                    && parsedBottom <= 1 && parsedBottom > parsedTop) { top = parsedTop; bottom = parsedBottom; }
        } catch (RuntimeException ignored) { }
        state.putFloat("top", top); state.putFloat("bottom", bottom);
        return state;
    }

    private static DisplayMetrics metrics(Context context) {
        DisplayMetrics result = new DisplayMetrics();
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(result);
        return result;
    }
}
