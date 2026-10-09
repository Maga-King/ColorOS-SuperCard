package dev.local.supercardhost;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import io.github.libxposed.api.XposedModule;

/** Hides the two vivo-only destinations while retaining the native save-animation anchor. */
final class MemoryHeaderCompat {
    private MemoryHeaderCompat() {}

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> title = loader.loadClass("com.vivo.memory.card.view.CardTitleView");
        var constructor = title.getDeclaredConstructor(Context.class, AttributeSet.class, int.class);
        constructor.setAccessible(true);
        module.hook(constructor).intercept(chain -> {
            Object result = chain.proceed();
            View root = (View) chain.getThisObject();
            for (String name : new String[]{"img_memory", "img_edit"}) {
                int id = root.getResources().getIdentifier(name, "id", "com.vivo.memory.card");
                View button = id == 0 ? null : root.findViewById(id);
                if (button == null) throw new IllegalStateException("Original memory header control missing: " + name);
                // img_memory is also the native save-animation endpoint. Keep its
                // measured bounds so saving a draft cannot create a zero-size target.
                button.setVisibility(View.INVISIBLE);
                button.setOnClickListener(null);
                button.setClickable(false);
                button.setFocusable(false);
                button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            return result;
        });
        Log.i("SuperCardMemory", "Hidden original home and edit destinations");
    }
}
