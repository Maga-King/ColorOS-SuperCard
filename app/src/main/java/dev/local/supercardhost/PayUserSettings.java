package dev.local.supercardhost;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import java.util.Set;
import java.util.TreeSet;

/** Stored by SystemUI, separately from each payment app's own data/profile. */
final class PayUserSettings {
    static final String WECHAT = "wechat", ALIPAY = "alipay";
    static final String[] APPS = {WECHAT, ALIPAY};
    private static final String PREFIX = "supercard_pay_user_";

    private PayUserSettings() { }

    static String packageName(String app) {
        if (WECHAT.equals(app)) return "com.tencent.mm";
        if (ALIPAY.equals(app)) return "com.eg.android.AlipayGphone";
        throw new IllegalArgumentException("Unknown PAY app");
    }

    static String appForFunction(String function) {
        if ("wx_scan".equals(function) || "wx_pay".equals(function) || "wx_ride".equals(function)
                || "wx2_scan".equals(function) || "wx2_pay".equals(function) || "wx2_ride".equals(function))
            return WECHAT;
        if ("alipay_scan".equals(function) || "alipay_pay".equals(function)
                || "alipay_ride".equals(function) || "alipay_health_code".equals(function)) return ALIPAY;
        return null;
    }

    static int selected(Context host, String app) {
        packageName(app);
        return Settings.Secure.getInt(host.getContentResolver(), PREFIX + app, 0);
    }

    static void save(Context host, String app, int user) {
        String pkg = packageName(app);
        if (user < 0 || (user != 0 && !cloneUsers(host, pkg).contains(user)))
            throw new IllegalArgumentException("所选分身已不可用，请刷新后重新选择");
        if (!Settings.Secure.putInt(host.getContentResolver(), PREFIX + app, user))
            throw new IllegalStateException("启动用户设置未保存");
    }

    static int resolve(Context host, String function) {
        String app = appForFunction(function);
        if (app == null) return -1;
        int user = selected(host, app);
        // Existing explicit wx2 entries stay clone-only, even with the default main selection.
        if (function.startsWith("wx2_") && user == 0) {
            Set<Integer> clones = cloneUsers(host, packageName(app));
            if (clones.isEmpty()) throw unavailable(app);
            user = clones.iterator().next();
        }
        if (user < 0 || !installed(host, packageName(app), user)
                || (user > 0 && !cloneUsers(host, packageName(app)).contains(user))) throw unavailable(app);
        return user;
    }

    static IllegalStateException unavailable(String app) {
        return new IllegalStateException((WECHAT.equals(app) ? "微信" : "支付宝")
                + "所选用户不可用，请在卡包的 PAY 设置中重新选择");
    }

    static Bundle read(Context host) {
        Bundle result = new Bundle();
        for (String app : APPS) {
            String pkg = packageName(app);
            int selected = selected(host, app);
            Set<Integer> users = cloneUsers(host, pkg);
            users.add(0);
            Bundle state = new Bundle();
            state.putInt("selected", selected);
            state.putIntArray("users", users.stream().mapToInt(Integer::intValue).toArray());
            state.putBoolean("available", users.contains(selected) && installed(host, pkg, selected));
            result.putBundle(app, state);
        }
        return result;
    }

    static Context forUser(Context host, int user) throws ReflectiveOperationException {
        if (user < 0 || user > Integer.MAX_VALUE / 100000) throw new IllegalArgumentException("Invalid user");
        UserHandle handle = UserHandle.getUserHandleForUid(user * 100000);
        // ContextImpl's special "android" branch retains the caller's LoadedApk/attribution
        // while setting the user. It also works when SystemUI is not installed in a clone.
        return (Context) Context.class.getMethod("createPackageContextAsUser",
                String.class, int.class, UserHandle.class).invoke(host, "android", 0, handle);
    }

    static boolean installed(Context host, String pkg, int user) {
        try {
            ApplicationInfo info = forUser(host, user).getPackageManager().getApplicationInfo(pkg, 0);
            return info.enabled && (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0
                    && info.uid / 100000 == user;
        } catch (Exception error) { return false; }
    }

    private static Set<Integer> cloneUsers(Context host, String pkg) {
        Set<Integer> users = new TreeSet<>();
        try {
            Class<?> type = Class.forName("com.oplus.multiapp.OplusMultiAppManager");
            Object manager = type.getMethod("getInstance").invoke(null);
            try {
                Object values = type.getMethod("getMultiAppUids", String.class).invoke(manager, pkg);
                if (values instanceof Iterable<?>) for (Object value : (Iterable<?>) values) {
                    if (value instanceof Integer && (Integer) value >= 100000)
                        users.add((Integer) value / 100000);
                }
            } catch (ReflectiveOperationException olderRom) { }
            // Older ColorOS exposes a single clone handle. Never assume its ID is 999.
            try {
                Object handle = type.getMethod("getMultiAppUserHandle").invoke(manager);
                if (handle instanceof UserHandle)
                    users.add((Integer) UserHandle.class.getMethod("getIdentifier").invoke(handle));
            } catch (ReflectiveOperationException olderRom) { }
            // Some releases lack getMultiAppUids but still support multiple clone users.
            try {
                UserManager um = host.getSystemService(UserManager.class);
                Object all = UserManager.class.getMethod("getUsers").invoke(um);
                if (all instanceof Iterable<?>) for (Object info : (Iterable<?>) all) {
                    int id = info.getClass().getField("id").getInt(info);
                    if (Boolean.TRUE.equals(type.getMethod("isMultiApp", int.class, String.class)
                            .invoke(manager, id, pkg))) users.add(id);
                }
            } catch (ReflectiveOperationException olderRom) { }
        } catch (Exception unavailable) { }
        users.removeIf(id -> id <= 0 || !installed(host, pkg, id));
        return users;
    }
}
