package dev.local.supercardhost;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native ColorOS camera modes exposed through the original vivo Lens function types. */
public final class ColorOsLensModes {
    private static final String TAG = "SuperCardLensModes";
    private static final String CAMERA_PACKAGE = "com.oplus.camera";
    private static final Uri MODES_URI =
            Uri.parse("content://com.oplus.camera.entry/static_info");
    private static final String SHORTCUT_ACTION = "com.oplus.camera.action.SHORTCUT_TYPE_MENU";

    // CameraEntry.n creates official launcher shortcuts with exactly these flags.
    private static final int SHORTCUT_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME;
    private static long lastFailureLog = -30_000L;

    private ColorOsLensModes() {}

    /** zoom == 0 lets the camera retain its native default for that mode. */
    public record Mode(int vivoId, String type, String title, int cameraMode,
                       boolean front, float zoom) {}

    /**
     * Reads the camera's own 64-bit capability masks, rather than advertising modes
     * based on the model name. An unavailable provider produces an empty list.
     */
    public static List<Mode> query(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }

        final long modes;
        final long rear;
        final long front;
        try (Cursor cursor = context.getContentResolver().query(
                MODES_URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return Collections.emptyList();
            }
            // Mode 40 cannot be represented by a 32-bit cursor read or bit shift.
            modes = cursor.getLong(cursor.getColumnIndexOrThrow("mode"));
            rear = cursor.getLong(cursor.getColumnIndexOrThrow("rear"));
            front = cursor.getLong(cursor.getColumnIndexOrThrow("front"));
        } catch (Exception error) {
            logFailure("Native mode query unavailable", error);
            return Collections.emptyList();
        }

        ArrayList<Mode> result = new ArrayList<>();
        add(result, modes, rear, front, new Mode(15, "take_photo", "拍照", 0, false, 0f));
        add(result, modes, rear, front, new Mode(0, "portrait", "人像", 2, false, 0f));
        add(result, modes, rear, front, new Mode(1, "video", "录像", 6, false, 0f));
        add(result, modes, rear, front, new Mode(6, "night_view", "夜景", 1, false, 0f));
        // Capability bit 35 advertises the Master feature, but CameraEntry.J0's
        // official shortcut_master_static enters it through professional mode 4.
        if (has(modes, 35) && has(rear, 35)) {
            add(result, modes, rear, front, new Mode(17, "master", "大师模式", 4, false, 0f));
        }
        add(result, modes, rear, front, new Mode(16, "mirror", "镜子", 0, true, 0f));
        add(result, modes, rear, front, new Mode(13, "profession", "专业", 4, false, 0f));
        add(result, modes, rear, front, new Mode(14, "panoramic", "全景", 3, false, 0f));
        add(result, modes, rear, front, new Mode(12, "ar_cute", "萌拍", 5, false, 0f));
        // The spelling high_piexl is the original vivo function type.
        add(result, modes, rear, front, new Mode(8, "high_piexl", "高像素", 24, false, 0f));
        add(result, modes, rear, front, new Mode(3, "mini_distance", "微距", 10, false, 0f));
        add(result, modes, rear, front, new Mode(9, "star_sky", "星空", 18, false, 0f));
        add(result, modes, rear, front,
                new Mode(11, "document_correction", "文档矫正", 11, false, 0f));

        if (has(modes, 0) && has(rear, 0)) {
            float minimumZoom = rearMinimumZoom(context);
            if (minimumZoom > 0f && minimumZoom < 1f) {
                add(result, modes, rear, front,
                        new Mode(2, "max_degree", "超广角", 0, false, minimumZoom));
            }
        }
        add(result, modes, rear, front, new Mode(4, "long_focus", "长焦", 40, false, 0f));

        // No proven native equivalent for short_video, super_moon or motion_capture.
        return Collections.unmodifiableList(result);
    }

    /** Launches only a function currently advertised by the native camera provider. */
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
        Intent intent = new Intent(SHORTCUT_ACTION)
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

    private static void add(List<Mode> result, long modes, long rear, long front, Mode mode) {
        if (has(modes, mode.cameraMode())
                && has(mode.front() ? front : rear, mode.cameraMode())) {
            result.add(mode);
        }
    }

    private static boolean has(long mask, int mode) {
        return mode >= 0 && mode < Long.SIZE && (mask & (1L << mode)) != 0L;
    }

    /** Reads metadata only; this never opens a camera device. */
    private static float rearMinimumZoom(Context context) {
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            if (manager == null) {
                return 0f;
            }
            String[] cameraIds = manager.getCameraIdList();
            // Native rear/common on this ColorOS camera uses camera 0. Prefer its
            // logical zoom range, never a guessed factor or another physical lens.
            for (String cameraId : cameraIds) {
                if ("0".equals(cameraId)) {
                    CameraCharacteristics characteristics =
                            manager.getCameraCharacteristics(cameraId);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        return minimumZoom(characteristics);
                    }
                }
            }
            // Devices with nonnumeric IDs may still expose one rear logical camera.
            // Multiple candidates are ambiguous, so omit the shortcut in that case.
            CameraCharacteristics soleRearLogical = null;
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                int[] capabilities = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK
                        || !isLogical(capabilities)) {
                    continue;
                }
                if (soleRearLogical != null) {
                    return 0f;
                }
                soleRearLogical = characteristics;
            }
            return soleRearLogical == null ? 0f : minimumZoom(soleRearLogical);
        } catch (Exception error) {
            logFailure("Rear zoom metadata unavailable", error);
            return 0f;
        }
    }

    private static boolean isLogical(int[] capabilities) {
        if (capabilities != null) {
            for (int capability : capabilities) {
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float minimumZoom(CameraCharacteristics characteristics) {
        Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (range == null) {
            return 0f;
        }
        float lower = range.getLower();
        float upper = range.getUpper();
        return Float.isFinite(lower) && Float.isFinite(upper)
                && lower > 0f && lower < 1f && upper >= 1f ? lower : 0f;
    }

    private static synchronized void logFailure(String message, Exception error) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFailureLog >= 30_000L) {
            lastFailureLog = now;
            Log.w(TAG, message + " (" + error.getClass().getSimpleName() + ")");
        }
    }
}
