package dev.local.supercardhost;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** Native ColorOS camera modes exposed through the original vivo Lens function types. */
public final class ColorOsLensModes {
    private static final String CAMERA_PACKAGE = "com.oplus.camera";
    private static final String SHORTCUT_ACTION = "com.oplus.camera.action.SHORTCUT_TYPE_MENU";

    // CameraEntry.n creates official launcher shortcuts with exactly these flags.
    private static final int SHORTCUT_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME;

    private ColorOsLensModes() {}

    /** zoom == 0 lets the camera retain its native default for that mode. */
    public record Mode(int vivoId, String type, String title, int cameraMode,
                       boolean front, float zoom) {}

    /** Stable choices, independent of camera/provider startup. The camera handles availability. */
    public static List<Mode> query(Context context) {
        return List.of(
                new Mode(15, "take_photo", "拍照", 0, false, 0f),
                new Mode(0, "portrait", "人像", 2, false, 0f),
                new Mode(1, "video", "录像", 6, false, 0f),
                new Mode(6, "night_view", "夜景", 1, false, 0f),
                // Official shortcut_master_static enters Master through mode 4.
                new Mode(17, "master", "大师模式", 4, false, 0f),
                new Mode(16, "mirror", "镜子", 0, true, 0f),
                new Mode(14, "panoramic", "全景", 3, false, 0f),
                new Mode(8, "high_piexl", "高像素", 24, false, 0f),
                new Mode(3, "mini_distance", "微距", 10, false, 0f),
                new Mode(9, "star_sky", "星空", 18, false, 0f),
                new Mode(11, "document_correction", "文档矫正", 11, false, 0f));
    }

    /** Launches a known mode without requiring the camera provider to be ready. */
    public static void launch(Context context, String type) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Lens function type is required");
        }
        Mode selected = null;
        for (Mode mode : query(context)) {
            if (mode.type().equals(type)) {
                selected = mode;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("Native camera function unavailable: " + type);
        }

        // Same action, component, flags and facing extras as CameraEntry.n(Context,...).
        // ColorOS advertises highPixel (24) on non-Hasselblad cameras too. The
        // shortcut action rejects it unless the Hasselblad-specific feature is
        // enabled; the normal camera entry accepts the same advertised mode.
        String action = "high_piexl".equals(type)
                ? "android.media.action.STILL_IMAGE_CAMERA" : SHORTCUT_ACTION;
        Intent intent = new Intent(action)
                .setComponent(new ComponentName(CAMERA_PACKAGE, CAMERA_PACKAGE + ".Camera"))
                .setPackage(CAMERA_PACKAGE)
                .addFlags(SHORTCUT_FLAGS)
                .putExtra("mode", selected.cameraMode())
                .putExtra("rear", !selected.front())
                .putExtra("front", selected.front());
        // CameraEntry.s0 consumes this float and selects its native ultra-wide path.
        if (selected.zoom() > 0f) {
            intent.putExtra("zoom", selected.zoom());
        }
        context.startActivity(intent);
    }

}
