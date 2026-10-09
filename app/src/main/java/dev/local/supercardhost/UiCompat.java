package dev.local.supercardhost;

/** OEM drawing hint absent on ColorOS; Android resources continue to supply colors. */
public final class UiCompat {
    public static void releaseVirtualDisplay(android.hardware.display.VirtualDisplay display) {
        int id = display.getDisplay().getDisplayId();
        try { display.release(); }
        finally { RootDisplayClient.released(id); }
    }
    public static android.hardware.display.VirtualDisplay createVirtualDisplay(
            android.hardware.display.DisplayManager manager, String name, int width, int height, int density,
            android.view.Surface surface, int flags) {
        if ("SuperCard_Virtual".equals(name)) {
            return RootDisplayClient.create(name, width, height, density, surface, flags);
        }
        return manager.createVirtualDisplay(name, width, height, density, surface, flags);
    }
    public static boolean bindServiceAsUser(android.content.Context context, android.content.Intent intent,
            android.content.ServiceConnection connection, int flags, android.os.UserHandle user) {
        if ("vivo.intent.action.CARD_REMOTE_VIEW".equals(intent.getAction())) {
            android.util.Log.i("SuperCardRuntime", "OEM card keyguard interface unavailable; binding skipped");
            return false;
        }
        try {
            return (Boolean) android.content.Context.class.getMethod("bindServiceAsUser", android.content.Intent.class,
                android.content.ServiceConnection.class, int.class, android.os.UserHandle.class)
                .invoke(context, intent, connection, flags, user);
        } catch (Exception error) {
            android.util.Log.e("SuperCardRuntime", "bindServiceAsUser failed", error); return false;
        }
    }
    public static void setNightMode(Object target, int mode) {
        // The OEM hint controls automatic recoloring, not application state.
        // Leave the original explicit drawable/text colors unchanged.
    }
}
