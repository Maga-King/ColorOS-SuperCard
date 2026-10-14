package dev.local.supercardhost;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Original settings thumbnail geometry, callable without framework injection. */
final class SelfPreviewCompat {
    private static final String TAG = "SuperCardPreview";
    private static final ThreadLocal<ImageView> TARGET = new ThreadLocal<>();
    private static final Map<ImageView, View.OnLayoutChangeListener> LIVE = new WeakHashMap<>();
    private static final String EMPTY_LENS = "supercard_empty_lens";

    static void repairEmptyLens(View card) {
        if (!"com.vivo.card.ui.view.CameraCardViewForEdit".equals(card.getClass().getName())) return;
        try {
            java.lang.reflect.Field data = card.getClass().getDeclaredField("mDatas"); data.setAccessible(true);
            java.util.List<?> functions = (java.util.List<?>) data.get(card);
            ViewGroup frame = (ViewGroup) content(card);
            if (frame == null) return;
            View old = frame.findViewWithTag(EMPTY_LENS);
            if (functions != null && !functions.isEmpty()) {
                if (old != null) frame.removeView(old);
                return;
            }
            // OEM setInitData returns before init/applySkin when no supported modes exist.
            java.lang.reflect.Method init = card.getClass().getDeclaredMethod("init", boolean.class);
            init.setAccessible(true); init.invoke(card, true);
            for (int i = 1; i <= 6; i++) {
                int id = card.getResources().getIdentifier("linear_item" + i, "id", "com.vivo.card");
                View row = card.findViewById(id); if (row != null) row.setVisibility(View.GONE);
            }
            if (old == null) {
                android.widget.TextView message = new android.widget.TextView(card.getContext());
                message.setTag(EMPTY_LENS); message.setText("暂无可用相机功能");
                message.setTextSize(13); message.setTextColor(android.graphics.Color.DKGRAY);
                message.setGravity(Gravity.CENTER);
                message.setBackgroundColor(0xDDEEEEEE);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
                int padding = Math.round(16 * card.getResources().getDisplayMetrics().density);
                params.leftMargin = padding; params.rightMargin = padding;
                message.setPadding(padding, padding, padding, padding); frame.addView(message, params);
            }
            card.setContentDescription("镜头卡，暂无可用相机功能");
        } catch (Exception error) { Log.w(TAG, "Cannot initialize empty Lens preview", error); }
    }

    static ImageView begin(ImageView image, Object bean) throws Exception {
        ImageView previous = TARGET.get();
        View.OnLayoutChangeListener old = LIVE.remove(image);
        if (old != null) image.removeOnLayoutChangeListener(old);
        String name = (String) bean.getClass().getMethod("getType").invoke(bean);
        if ("pay".equals(name) || "camera".equals(name)) TARGET.set(image);
        else TARGET.remove();
        return previous;
    }

    static void end(ImageView previous) {
        if (previous == null) TARGET.remove(); else TARGET.set(previous);
    }

    static Bitmap capture(View card, int width, int height) {
        ImageView target = TARGET.get();
        if (target == null || !editableCard(card)) return null;
        try {
            repairEmptyLens(card);
            View content = content(card);
            if (content == null) return null;
            card.measure(exact(width), exact(height));
            card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
            if (content.getWidth() <= 0 || content.getHeight() <= 0) return null;
            Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            try { content.draw(new Canvas(bitmap)); }
            catch (Throwable error) { bitmap.recycle(); throw error; }
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return bitmap;
        } catch (Throwable error) {
            Log.w(TAG, "Native thumbnail capture failed", error);
            return null;
        }
    }

    static boolean live(View card, ImageView target) {
        if (TARGET.get() != target || !editableCard(card)) return false;
        try {
            if (!scaleLive(card, target)) return false;
            View.OnLayoutChangeListener previous = LIVE.remove(target);
            if (previous != null) target.removeOnLayoutChangeListener(previous);
            WeakReference<View> source = new WeakReference<>(card);
            View.OnLayoutChangeListener listener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                View original = source.get();
                if (original == null || original.getParent() == null) return;
                try { scaleLive(original, (ImageView) view); }
                catch (Throwable error) { Log.w(TAG, "Live thumbnail relayout failed", error); }
            };
            LIVE.put(target, listener);
            target.addOnLayoutChangeListener(listener);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Live thumbnail scale failed", error);
            return false;
        }
    }

    private static boolean scaleLive(View card, ImageView target) {
        View content = content(card);
        if (content == null) return false;
        ViewGroup.LayoutParams source = content.getLayoutParams();
        int width = source != null && source.width > 0 ? source.width : content.getMeasuredWidth();
        int height = source != null && source.height > 0 ? source.height : content.getMeasuredHeight();
        ViewGroup.LayoutParams destination = target.getLayoutParams();
        int targetWidth = target.getWidth() > 0 ? target.getWidth() : destination == null ? 0 : destination.width;
        int targetHeight = target.getHeight() > 0 ? target.getHeight() : destination == null ? 0 : destination.height;
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return false;
        // The source's XML already resolved dp using its own resources. Keep that
        // layout intact; do not substitute a device-specific DPI or stretch axes.
        ViewGroup.LayoutParams root = card.getLayoutParams();
        if (root != null && (root.width != width || root.height != height)) {
            root.width = width; root.height = height;
            if (root instanceof FrameLayout.LayoutParams) ((FrameLayout.LayoutParams) root).gravity = Gravity.CENTER;
            card.setLayoutParams(root);
        }
        if (card.getParent() instanceof FrameLayout wrapper) {
            ViewGroup.LayoutParams wrapperParams = wrapper.getLayoutParams();
            if (wrapperParams != null && (wrapperParams.width != ViewGroup.LayoutParams.MATCH_PARENT
                    || wrapperParams.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                wrapperParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                wrapperParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                wrapper.setLayoutParams(wrapperParams);
            }
        }
        card.measure(exact(width), exact(height)); card.layout(0, 0, width, height);
        float scale = Math.max((float) targetWidth / width, (float) targetHeight / height);
        card.setPivotX(width / 2f); card.setPivotY(height / 2f);
        card.setScaleX(scale); card.setScaleY(scale);
        return true;
    }

    private static View content(View card) {
        int id = card.getResources().getIdentifier("frm_card", "id", "com.vivo.card");
        return id == 0 ? null : card.findViewById(id);
    }
    private static boolean editableCard(View card) {
        if (card == null) return false;
        String type = card.getClass().getName();
        return "com.vivo.card.ui.view.PayCardViewForEdit".equals(type)
                || "com.vivo.card.ui.view.CameraCardViewForEdit".equals(type);
    }
    private static int exact(int value) { return View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY); }
}
