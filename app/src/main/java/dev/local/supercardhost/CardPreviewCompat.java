package dev.local.supercardhost;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/** Density-independent snapshots confined to the original settings card thumbnails. */
public final class CardPreviewCompat {
    private static final String TAG = "SuperCardPreview";
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());

    private CardPreviewCompat() {}

    public static synchronized void install(XposedModule module, ClassLoader loader) throws Exception {
        if (INSTALLED.contains(loader)) return;
        Class<?> adapter = loader.loadClass("com.vivo.card.adapter.CardManagerAdapter");
        Class<?> bean = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean");
        Method bind = method(adapter, "bindImageCardView", ImageView.class, FrameLayout.class, bean);
        module.hook(bind).intercept(chain -> {
            ImageView previous = SelfPreviewCompat.begin((ImageView) chain.getArg(0), chain.getArg(2));
            try { return chain.proceed(); }
            finally { SelfPreviewCompat.end(previous); }
        });
        Method capture = method(loader.loadClass("com.vivo.card.utils.CardUtil"),
                "createViewToBitmap", View.class, int.class, int.class);
        module.hook(capture).intercept(chain -> {
            Bitmap bitmap = SelfPreviewCompat.capture((View) chain.getArg(0),
                    (Integer) chain.getArg(1), (Integer) chain.getArg(2));
            return bitmap != null ? bitmap : chain.proceed();
        });
        Method live = method(adapter, "setupCardViewScale", View.class, ImageView.class);
        module.hook(live).intercept(chain -> SelfPreviewCompat.live((View) chain.getArg(0),
                (ImageView) chain.getArg(1)) ? null : chain.proceed());
        INSTALLED.add(loader);
        Log.i(TAG, "Settings-only Pay/Lens thumbnail geometry adapter installed");
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException {
        Method result = owner.getDeclaredMethod(name, args); result.setAccessible(true); return result;
    }
}
