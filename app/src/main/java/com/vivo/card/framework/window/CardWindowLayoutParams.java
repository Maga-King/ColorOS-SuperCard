package com.vivo.card.framework.window;

import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;

/** Replace only the privileged window boundary, leaving the OEM card views intact. */
public final class CardWindowLayoutParams extends WindowManager.LayoutParams {
    public static final int $stable = 0;
    public static final Companion Companion = new Companion();
    public static final class Companion {
        public CardWindowLayoutParams defaultLayoutParams() {
            CardWindowLayoutParams p = new CardWindowLayoutParams();
            p.type = dev.local.supercardhost.PluginRuntime.systemHost ? 2009 : TYPE_APPLICATION_OVERLAY;
            p.flags = 8519968;
            p.format = PixelFormat.TRANSLUCENT;
            p.gravity = Gravity.BOTTOM;
            p.width = MATCH_PARENT; p.height = MATCH_PARENT;
            p.setTitle("SuperCard-ColorOS");
            p.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            p.setFitInsetsTypes(0);
            return p;
        }
    }
}
