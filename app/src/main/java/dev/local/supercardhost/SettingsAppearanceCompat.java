package dev.local.supercardhost;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/** Restores night resource colors only in the native PAY/LENS function editor. */
public final class SettingsAppearanceCompat {
    private SettingsAppearanceCompat() {}

    public static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> adapter = loader.loadClass("com.vivo.card.setting.CardEditAdapter");
        Class<?> holder = loader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
        Method bind = adapter.getDeclaredMethod("onBindViewHolder", holder, int.class);
        Method viewType = holder.getMethod("getItemViewType");
        Field itemView = holder.getField("itemView");
        module.hook(bind).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object row = chain.getArg(0);
                int type = (Integer) viewType.invoke(row);
                if (type == 1002 || type == 1004) {
                    View view = (View) itemView.get(row);
                    Resources resources = view.getResources();
                    int titleId = resources.getIdentifier("tv_title", "id", "com.vivo.card");
                    View title = titleId == 0 ? null : view.findViewById(titleId);
                    if (title instanceof TextView text) {
                        boolean night = (resources.getConfiguration().uiMode
                                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                        // settings_function_text_color has no night override. The
                        // original OriginUI main-list color already has both variants.
                        String name = night ? "originui_dialog_list_main_item_text_color_rom15_0"
                                : "settings_function_text_color";
                        int color = resources.getIdentifier(name, "color", "com.vivo.card");
                        if (color != 0) text.setTextColor(resources.getColorStateList(color, view.getContext().getTheme()));
                        text.setForceDarkAllowed(false);
                    }
                }
            } catch (Throwable error) {
                Log.w("SuperCardSettings", "Cannot refresh editor row colors", error);
            }
            return result;
        });
        Log.i("SuperCardSettings", "PAY/LENS editor night colors attached");
    }
}
