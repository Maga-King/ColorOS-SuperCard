package dev.local.supercardhost;

import android.app.ActivityManager;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.json.JSONObject;

/** Short-lived ColorOS policy adapter, launched by the app through authorized su. */
public final class AppPlatformPolicy {
    private static final String PACKAGE = "dev.local.supercardhost";

    public static void main(String[] args) {
        if (android.os.Process.myUid() != 1000) throw new SecurityException("System UID required");
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/", "Lcom/oplus/");
            int user = Integer.parseInt(args[0]);
            if (user < 0) throw new IllegalArgumentException("Invalid user");
            JSONObject result = new JSONObject();
            try (Provider startup = new Provider("com.oplus.startup.provider", user)) {
                try { result.put("associate", allowStartup(startup, "getAssociateStartState", "setAssociateStartState")); }
                catch (Exception error) { result.put("associateError", error.toString()); }
                try { result.put("autoStart", allowStartup(startup, "getStartupState", "setStartupState")); }
                catch (Exception error) { result.put("autoStartError", error.toString()); }
            } catch (Exception error) { result.put("startupError", error.toString()); }
            try (Provider battery = new Provider("com.oplus.powermanager", user)) {
                Bundle values = new Bundle(); values.putStringArrayList("pc_white_list", packages());
                battery.call("power_control_add_white_list", values);
                // The OEM writer is asynchronous. Confirm its persisted list, as its UI does.
                boolean saved = false;
                for (int i = 0; i < 20 && !saved; i++) {
                    Thread.sleep(100); saved = backgroundSaved(user);
                }
                result.put("background", saved);
            } catch (Exception error) { result.put("backgroundError", error.toString()); }
            System.out.println("SUPERCARD_POLICY " + result);
            System.exit(0);
        } catch (Exception error) { error.printStackTrace(); System.exit(1); }
    }

    private static ArrayList<String> packages() {
        ArrayList<String> result = new ArrayList<>(); result.add(PACKAGE); return result;
    }

    private static boolean allowStartup(Provider provider, String getter, String setter) throws Exception {
        Bundle request = new Bundle(); request.putStringArrayList("packageList", packages());
        Bundle before = provider.call(getter, request);
        if (before == null || !before.containsKey(PACKAGE)) throw new IllegalStateException("Startup API unavailable");
        if (before.getBoolean(PACKAGE)) return true;
        request.putBoolean(PACKAGE, true); provider.call(setter, request);
        Bundle after = provider.call(getter, request);
        return after != null && after.getBoolean(PACKAGE, false);
    }

    private static boolean backgroundSaved(int user) throws Exception {
        String path = (String) Class.forName("com.oplus.settings.OplusSettingsConfig")
                .getMethod("getFilePath", int.class, int.class, String.class)
                .invoke(null, 0, user, "battery/power_control_white_list");
        try (java.io.FileInputStream stream = new java.io.FileInputStream(path)) {
            org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser(); parser.setInput(stream, "UTF-8");
            for (int event = parser.next(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = parser.next())
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && "p".equals(parser.getName())
                        && PACKAGE.equals(parser.getAttributeValue(null, "att"))) return true;
        } catch (java.io.FileNotFoundException missing) { return false; }
        return false;
    }

    // Like Android's content command, use an external token lease: app_process has no registered app thread.
    private static final class Provider implements AutoCloseable {
        final String authority; final int user; final IBinder token = new Binder();
        final Object manager, provider; final Method release, call;
        Provider(String authority, int user) throws Exception {
            this.authority = authority; this.user = user;
            Method service = ActivityManager.class.getDeclaredMethod("getService"); service.setAccessible(true);
            manager = service.invoke(null);
            Class<?> api = Class.forName("android.app.IActivityManager");
            release = api.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class);
            call = Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource.class,
                    String.class, String.class, String.class, Bundle.class);
            Object holder = api.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                    .invoke(manager, authority, user, token, "SuperCardSetup");
            if (holder == null) throw new IllegalStateException("Provider unavailable: " + authority);
            Object resolved;
            try {
                resolved = holder.getClass().getField("provider").get(holder);
                if (resolved == null) throw new IllegalStateException("Provider binder unavailable: " + authority);
            } catch (Exception error) { release.invoke(manager, authority, token, user); throw error; }
            provider = resolved;
        }
        Bundle call(String method, Bundle values) throws Exception {
            return (Bundle) call.invoke(provider, new AttributionSource.Builder(1000).setPackageName("android").build(),
                    authority, method, null, values);
        }
        @Override public void close() throws Exception { release.invoke(manager, authority, token, user); }
    }
}
