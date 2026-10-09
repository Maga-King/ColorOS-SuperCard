package com.vivo.framework.themeicon;

import vivo.app.themeicon.SystemFilletListener;

/** Local rendering defaults only. No replacement for an OEM Binder service. */
public final class ThemeIconManager {
    private static final ThemeIconManager INSTANCE = new ThemeIconManager();
    public static ThemeIconManager getInstance() { return INSTANCE; }
    // Original device: theme_custom_fillet_level=1, theme_custom_fillet=-1.
    // Level 2 multiplies OriginUI radii by 1.4 and rounds Pay buttons too far.
    public int getSystemFilletLevel() { return 1; }
    public int getSystemFillet() { return -1; }
    public int getSystemColorMode() { return 0; }
    public int getSystemPrimaryColor() { return 0xff2979ff; }
    public int getSystemSecondaryColor() { return 0xff89b4ff; }
    public int[] getSystemColorWheelIntArray() { return new int[]{getSystemPrimaryColor(), getSystemSecondaryColor()}; }
    public boolean registerSystemFilletChangeListener(SystemFilletListener listener) {
        // Fixed rendering defaults do not emit subsequent OEM theme changes.
        return false;
    }
    public boolean unregisterSystemFilletChangeListener(SystemFilletListener listener) { return false; }
}
