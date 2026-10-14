package dev.local.supercardhost;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.UserHandle;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Only permissions declared by our installed APK and grantable by Android. */
final class AppPermissionGrants {
    private static final String PACKAGE = "dev.local.supercardhost";

    static List<String> grantable(Context context) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo app = manager.getPackageInfo(PACKAGE, PackageManager.GET_PERMISSIONS);
        List<String> result = new ArrayList<>();
        if (app.requestedPermissions != null) for (String name : app.requestedPermissions) {
            try {
                PermissionInfo info = manager.getPermissionInfo(name, 0);
                int base = info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                if (base == PermissionInfo.PROTECTION_DANGEROUS
                        || (info.protectionLevel & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0)
                    result.add(name);
            } catch (PackageManager.NameNotFoundException absentOnThisRom) { }
        }
        return result;
    }

    static String summary(Context context) {
        try {
            List<String> permissions = grantable(context); int allowed = 0;
            for (String permission : permissions)
                if (context.getPackageManager().checkPermission(permission, PACKAGE) == PackageManager.PERMISSION_GRANTED) allowed++;
            return allowed + "/" + permissions.size();
        } catch (Exception error) { return "未确认"; }
    }

    /** Use the injected SystemUI's existing platform authority; self injection is unnecessary. */
    static void fromFramework(Context host) {
        if (!"com.android.systemui".equals(host.getPackageName())) return;
        try {
            PackageManager manager = host.getPackageManager();
            java.lang.reflect.Method grant = manager.getClass().getMethod("grantRuntimePermission",
                    String.class, String.class, UserHandle.class);
            int user = android.os.Process.myUid() / 100000;
            for (String permission : grantable(host)) {
                if (manager.checkPermission(permission, PACKAGE) == PackageManager.PERMISSION_GRANTED) continue;
                try { grant.invoke(manager, PACKAGE, permission, UserHandle.getUserHandleForUid(user * 100000)); }
                catch (Exception denied) { Log.i("SuperCardSetup", "Framework grant unavailable for " + permission + "; root setup will check"); }
            }
        } catch (Exception unavailable) { Log.w("SuperCardSetup", "Framework permission setup unavailable", unavailable); }
    }
}
