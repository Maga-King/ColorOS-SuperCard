package dev.local.supercardhost;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Window;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Adds compatibility code to the original app before its activities are loaded. */
final class OriginalAppCompat {
    static void install(io.github.libxposed.api.XposedModule xposed, ClassLoader target, ApplicationInfo info) throws Exception {
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/", "Ldalvik/system/");
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        var current = threadType.getDeclaredMethod("currentActivityThread"); current.setAccessible(true);
        var systemContext = threadType.getDeclaredMethod("getSystemContext"); systemContext.setAccessible(true);
        Context system = (Context) systemContext.invoke(current.invoke(null));
        Context module = system.createPackageContext("dev.local.supercardhost", Context.CONTEXT_IGNORE_SECURITY);
        int newCount = 0;
        // The unified APK already contains the patched card classes and the host
        // shims. Re-prepending the archive would shadow those verified shims.
        if (!"dev.local.supercardhost".equals(info.packageName)) {
        File directory = new File(info.dataDir, "code_cache/supercard_compat");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create settings code directory");
        File code = new File(directory, "supercard-code.zip");
        if (code.exists() && !code.delete()) throw new java.io.IOException("Cannot replace settings code");
        try (var in = module.getAssets().open("supercard-code.zip"); var out = new java.io.FileOutputStream(code)) {
            if (!code.setReadOnly()) throw new java.io.IOException("Cannot protect settings code");
            in.transferTo(out);
        }
        Field pathListField = field(target.getClass(), "pathList");
        Object targetList = pathListField.get(target);
        Field elementsField = field(targetList.getClass(), "dexElements");
        int oldCount = Array.getLength(elementsField.get(targetList));
        // ART associates each DexFile with its defining loader. Add through the
        // target itself, then reorder; transferring another loader's elements is invalid.
        var addDexPath = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredMethod("addDexPath", String.class);
        addDexPath.setAccessible(true);
        addDexPath.invoke(target, code.getAbsolutePath() + File.pathSeparator + module.getApplicationInfo().sourceDir);
        Object all = elementsField.get(targetList);
        newCount = Array.getLength(all) - oldCount;
        Object merged = Array.newInstance(all.getClass().getComponentType(), newCount + oldCount);
        System.arraycopy(all, oldCount, merged, 0, newCount);
        System.arraycopy(all, 0, merged, newCount, oldCount);
        elementsField.set(targetList, merged);
        }
        HostSettingsDefaults.install(xposed, target);
        LensRuntimeBridge.install(xposed, target);
        CardPreviewCompat.install(xposed, target);
        SettingsLinkCompat.install(xposed, target);
        var onCreate = target.loadClass("com.vivo.card.preference.BasePreferenceActivity")
                .getDeclaredMethod("onCreate", Bundle.class);
        onCreate.setAccessible(true);
        xposed.hook(onCreate).intercept(chain -> {
            Activity activity = (Activity) chain.getThisObject();
            HostSettingsDefaults.ensure(activity);
            try { PluginRuntime.installCoupon(xposed, activity.getApplication(), target); }
            catch (Throwable error) { Log.e("SuperCardCoupon", "Settings coupon adapter unavailable", error); }
            // The OEM framework theme ID resolves to a different ColorOS theme.
            // Remove that theme's extra action bar before the vivo toolbar is inflated.
            activity.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Class<?> perf = target.loadClass("com.vivo.card.CardPerfContext");
            perf.getMethod("setOriginContext", Context.class).invoke(null, activity.getApplication());
            perf.getMethod("setPerfContext", Context.class).invoke(null, activity.getApplication());
            return chain.proceed();
        });
        hideReplacedOpenStylePreferences(xposed, target);
        Log.i("SuperCardSettings", "Original APK settings compatibility attached, added dex elements=" + newCount);
    }

    private static void hideReplacedOpenStylePreferences(
            io.github.libxposed.api.XposedModule xposed, ClassLoader target) {
        try {
            Class<?> fragment = target.loadClass("com.vivo.card.setting.CardSettingsPreferenceFragment");
            Method find = fragment.getMethod("findPreference", CharSequence.class);
            Method visible = target.loadClass("androidx.preference.Preference")
                    .getMethod("setVisible", boolean.class);
            // These two original selectors are replaced by the host's gesture settings.
            // Keep the native main switch, unlocked gesture and their category intact.
            for (String name : new String[]{"initPreference", "updateAllPreferenceVisibility"}) {
                Method refresh = fragment.getDeclaredMethod(name);
                refresh.setAccessible(true);
                xposed.hook(refresh).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        for (String key : new String[]{"lock_screen_open_style", "reset_screen_open_style"}) {
                            Object preference = find.invoke(chain.getThisObject(), key);
                            if (preference != null) visible.invoke(preference, false);
                        }
                    } catch (Throwable error) {
                        Log.w("SuperCardSettings", "Unable to hide replaced open-style selectors", error);
                    }
                    return result;
                });
            }
        } catch (Throwable error) {
            Log.w("SuperCardSettings", "Unable to attach open-style preference visibility adapter", error);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
