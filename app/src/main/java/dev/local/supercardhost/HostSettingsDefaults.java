package dev.local.supercardhost;

import android.content.Context;
import android.util.Log;

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

    public static void ensure(Context context) { HostSettingsValues.ensure(context); }
    static String ensureRange(Context context, String raw) { return HostSettingsValues.ensureRange(context, raw); }
    static boolean validRange(String raw, int width, int height) { return HostSettingsValues.validRange(raw, width, height); }
}
