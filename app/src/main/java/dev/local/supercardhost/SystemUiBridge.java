package dev.local.supercardhost;

import android.app.Application;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class SystemUiBridge {
    static Object dependency(Application host, String typeName) throws Exception {
        ClassLoader loader = host.getClassLoader();
        Class<?> dependency = loader.loadClass("com.android.systemui.Dependency");
        Field singleton = dependency.getDeclaredField("sDependency"); singleton.setAccessible(true);
        Method get = dependency.getDeclaredMethod("getDependencyInner", Object.class); get.setAccessible(true);
        return get.invoke(singleton.get(null), loader.loadClass(typeName));
    }

    static boolean[] keyguardState(Application host, boolean locked) {
        try {
            String name = "com.android.systemui.statusbar.policy.KeyguardStateController";
            Object controller = dependency(host, name);
            Class<?> type = controller.getClass();
            boolean showing = type.getField("mShowing").getBoolean(controller);
            boolean occluded = type.getField("mOccluded").getBoolean(controller);
            boolean bouncer = type.getField("mPrimaryBouncerShowing").getBoolean(controller);
            return new boolean[]{showing && !occluded, bouncer};
        } catch (Exception error) {
            return new boolean[]{host.getSystemService(android.app.KeyguardManager.class).inKeyguardRestrictedInputMode(), false};
        }
    }
}
