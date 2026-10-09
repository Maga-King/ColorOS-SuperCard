package dev.local.supercardhost;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/** Defaults for settings owned by the card adapter, including absent fresh-install geometry. */
public final class HostSettingsDefaults {
    private static final String TAG = "SuperCardDefaults";
    private static final String RANGE = "navigation_slide_card_range";
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());

    private HostSettingsDefaults() {}

    public static synchronized void install(XposedModule module, ClassLoader loader) throws Exception {
        if (INSTALLED.contains(loader)) return;
        Method getString = loader.loadClass("com.vivo.card.utils.SettingsUtils$Secure")
                .getDeclaredMethod("getString", Context.class, String.class, String.class, String.class);
        getString.setAccessible(true);
        module.hook(getString).intercept(chain -> {
            if (!RANGE.equals(chain.getArg(1))) return chain.proceed();
            Context context = (Context) chain.getArg(0);
            String raw;
            try { raw = (String) chain.proceed(); }
            catch (Throwable error) {
                Log.w(TAG, "Original range read failed; supplying valid geometry", error);
                raw = null;
            }
            return ensureRange(context, raw);
        });
        // The original first-boot routine applies vivo RAM/project rules and can
        // overwrite valid choices before the ColorOS gesture adapter sees them.
        Class<?> initializer = loader.loadClass("com.vivo.card.data.CardDataInitializer");
        var context = initializer.getDeclaredField("context");
        context.setAccessible(true);
        Method firstBoot = initializer.getDeclaredMethod("handleSwitchStateOnFirstBoot");
        firstBoot.setAccessible(true);
        module.hook(firstBoot).intercept(chain -> {
            ensure((Context) context.get(chain.getThisObject()));
            return null;
        });
        INSTALLED.add(loader);
    }

    public static synchronized void ensure(Context context) {
        if (context == null) return;
        try {
            ContentResolver resolver = context.getContentResolver();
            ensureRange(context, Settings.Secure.getString(resolver, RANGE));
            integer(resolver, "card_service_total_switch", 1, 0, 1);
            integer(resolver, "bbk_lock_disable_card_slide_setting", 0, 0, 1);
            integer(resolver, "bbk_lock_disable_card_slide_open_style", 0, 0, 3);
            integer(resolver, "bbk_screen_disable_card_slide_setting", 0, 0, 1);
            integer(resolver, "bbk_screen_disable_card_slide_open_style", 0, 0, 3);
            integer(resolver, "supercard_handle_visible", 1, 0, 1);
            integer(resolver, "supercard_handle_auto_hide", 1, 0, 1);
            integer(resolver, "supercard_handle_opacity", 40, 0, 100);
            String side = Settings.Secure.getString(resolver, "navigation_gesture_right_side");
            if (!validSide(side)) put(resolver, "navigation_gesture_right_side", "side_back;side_card");
            String format = Settings.Secure.getString(resolver, "key_need_format_camera_function");
            if (!"true".equals(format) && !"false".equals(format))
                put(resolver, "key_need_format_camera_function", "true");
        } catch (Throwable error) {
            Log.w(TAG, "Unable to initialize card-owned defaults", error);
        }
    }

    static String ensureRange(Context context, String raw) {
        DisplayMetrics metrics = metrics(context);
        // An absent display cannot justify inventing a resolution or density.
        // This transient empty region is parseable by the original settings UI;
        // do not persist it, so a later real display can initialize the range.
        if (metrics == null) return "0;0;0;0";
        if (validRange(raw, metrics.widthPixels, metrics.heightPixels)) return raw;
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int edge = Math.min(width, Math.max(1, Math.round(24 * metrics.density)));
        int top = Math.max(0, Math.min(height - 1, Math.round(.76f * height)));
        int bottom = Math.min(height, Math.max(top + 1, Math.round(.98f * height)));
        String replacement = (width - edge) + ";" + top + ";" + width + ";" + bottom;
        if (context != null) try { put(context.getContentResolver(), RANGE, replacement); }
        catch (Throwable error) { Log.w(TAG, "Cannot persist repaired gesture geometry", error); }
        return replacement;
    }

    static boolean validRange(String raw, int width, int height) {
        if (raw == null || width <= 0 || height <= 0) return false;
        String[] values = raw.split(";", -1);
        if (values.length != 4) return false;
        try {
            double[] range = new double[4];
            for (int i = 0; i < 4; i++) {
                // Match the original TransformUtil's Float.parseFloat, including
                // overflow rejection, rather than accepting double-only numbers.
                range[i] = Float.parseFloat(values[i].trim());
                if (!Double.isFinite(range[i])) return false;
            }
            if (range[0] < 0 || range[2] <= range[0] || range[1] < 0 || range[3] <= range[1]) return false;
            double scale = width / range[2];
            double top = range[1] * scale, bottom = range[3] * scale;
            return Double.isFinite(top) && Double.isFinite(bottom) && bottom <= height
                    && Math.round(top) < Math.round(bottom);
        } catch (NumberFormatException ignored) { return false; }
    }

    private static boolean validSide(String side) {
        if (side == null) return false;
        String[] parts = side.split(";", -1);
        return parts.length == 2 && sideToken(parts[0].trim()) && sideToken(parts[1].trim());
    }

    private static boolean sideToken(String token) {
        // CardUtil.SIDE_SLIDE_{BACK,CARD,MUSIC,NONE} in the original APK.
        return "side_back".equals(token) || "side_card".equals(token)
                || "side_music".equals(token) || "side_none".equals(token);
    }

    private static void integer(ContentResolver resolver, String key, int fallback, int min, int max) {
        String raw = Settings.Secure.getString(resolver, key);
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) return;
        } catch (NumberFormatException ignored) { }
        put(resolver, key, Integer.toString(fallback));
    }

    private static void put(ContentResolver resolver, String key, String value) {
        if (!Settings.Secure.putString(resolver, key, value)) Log.w(TAG, "Cannot save default for " + key);
    }

    private static DisplayMetrics metrics(Context context) {
        DisplayMetrics result = new DisplayMetrics();
        if (context != null) try {
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) display.getRealMetrics(result);
        } catch (Throwable ignored) { }
        if (result.widthPixels <= 0 || result.heightPixels <= 0)
            result.setTo(context == null ? android.content.res.Resources.getSystem().getDisplayMetrics()
                    : context.getResources().getDisplayMetrics());
        if (result.widthPixels <= 0 || result.heightPixels <= 0
                || !Float.isFinite(result.density) || result.density <= 0) return null;
        return result;
    }
}
