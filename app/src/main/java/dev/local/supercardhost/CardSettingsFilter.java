package dev.local.supercardhost;

import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/** PAY and LENS retain their native editors; memory and coupons have no editor. */
public final class CardSettingsFilter {
    private CardSettingsFilter() {}

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> holder = loader.loadClass("com.vivo.card.adapter.CardManagerAdapter$CardManagerHorizontalAdapter$CardListViewHolder");
            Class<?> data = loader.loadClass("com.vivo.card.model.CardManagerBean");
            Method bind = holder.getDeclaredMethod("bind4UsingHolderItem", data);
            bind.setAccessible(true);
            Method card = data.getMethod("getCardBean");
            Method type = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean").getMethod("getType");
            Field button = holder.getDeclaredField("buttonSet");
            button.setAccessible(true);
            module.hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                Object bean = card.invoke(chain.getArg(0));
                String name = bean == null ? null : (String) type.invoke(bean);
                View view = (View) button.get(chain.getThisObject());
                // Restore visibility when RecyclerView reuses a memory/coupon holder.
                view.setVisibility("memory".equals(name) || "staging".equals(name)
                        ? View.GONE : View.VISIBLE);
                return result;
            });
        } catch (Throwable error) {
            Log.w("SuperCardSettings", "Cannot adapt card editor buttons", error);
        }
    }
}
