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
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/** Density-independent snapshots confined to the original settings card thumbnails. */
public final class CardPreviewCompat {
    private static final String TAG = "SuperCardPreview";
    private static final ThreadLocal<ImageView> TARGET = new ThreadLocal<>();
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<ImageView, View.OnLayoutChangeListener> LIVE = new WeakHashMap<>();

    private CardPreviewCompat() {}

    public static synchronized void install(XposedModule module, ClassLoader loader) throws Exception {
        if (INSTALLED.contains(loader)) return;
        Class<?> adapter = loader.loadClass("com.vivo.card.adapter.CardManagerAdapter");
        Class<?> bean = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean");
        Method bind = method(adapter, "bindImageCardView", ImageView.class, FrameLayout.class, bean);
        Method type = method(bean, "getType");
        module.hook(bind).intercept(chain -> {
            ImageView previous = TARGET.get();
            ImageView image = (ImageView) chain.getArg(0);
            View.OnLayoutChangeListener oldLive = LIVE.remove(image);
            if (oldLive != null) image.removeOnLayoutChangeListener(oldLive);
            String name = (String) type.invoke(chain.getArg(2));
            if ("pay".equals(name) || "camera".equals(name)) TARGET.set(image);
            else TARGET.remove();
            try { return chain.proceed(); }
            finally { if (previous == null) TARGET.remove(); else TARGET.set(previous); }
        });
        Method capture = method(loader.loadClass("com.vivo.card.utils.CardUtil"),
                "createViewToBitmap", View.class, int.class, int.class);
        module.hook(capture).intercept(chain -> {
            ImageView target = TARGET.get();
            View card = (View) chain.getArg(0);
            if (target == null || !editableCard(card)) return chain.proceed();
            try {
                // The original helper sizes this outer root using initial physical
                // density. Its fixed-dp frm_card uses the resource context density,
                // leaving transparent space around the actual card in that bitmap.
                View content = content(card);
                if (content == null) return chain.proceed();
                int width = (Integer) chain.getArg(1), height = (Integer) chain.getArg(2);
                card.measure(exact(width), exact(height));
                card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
                if (content.getWidth() <= 0 || content.getHeight() <= 0) return chain.proceed();
                Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
                try { content.draw(new Canvas(bitmap)); }
                catch (Throwable error) { bitmap.recycle(); throw error; }
                // The native ImageView now scales only actual card pixels to its
                // current bounds, including recycler relayouts and display changes.
                target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                return bitmap;
            } catch (Throwable error) {
                Log.w(TAG, "Native card thumbnail capture failed", error);
                return chain.proceed();
            }
        });
        Method live = method(adapter, "setupCardViewScale", View.class, ImageView.class);
        module.hook(live).intercept(chain -> {
            View card = (View) chain.getArg(0);
            ImageView target = (ImageView) chain.getArg(1);
            if (TARGET.get() != target || !editableCard(card)) return chain.proceed();
            try {
                if (!scaleLive(card, target)) return chain.proceed();
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
                return null;
            } catch (Throwable error) {
                Log.w(TAG, "Live card thumbnail scale failed", error);
                return chain.proceed();
            }
        });
        INSTALLED.add(loader);
        Log.i(TAG, "Settings-only Pay/Lens thumbnail geometry adapter installed");
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
    private static Method method(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException {
        Method result = owner.getDeclaredMethod(name, args); result.setAccessible(true); return result;
    }
}
