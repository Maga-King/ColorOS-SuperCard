package dev.local.supercardhost;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** Places the newly available original coupon card into its requested default slot once. */
final class CouponCardConfiguration {
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private CouponCardConfiguration() {}

    static synchronized void install(XposedModule module, ClassLoader loader) throws Exception {
        if (INSTALLED.contains(loader)) return;
        Class<?> initializer = loader.loadClass("com.vivo.card.data.CardDataInitializer");
        Field context = initializer.getDeclaredField("context"); context.setAccessible(true);
        Field settings = initializer.getDeclaredField("settingsManager"); settings.setAccessible(true);
        Class<?> bean = loader.loadClass("com.vivo.card.model.CardSetBean");
        Class<?> cardBean = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean");
        Method cards = bean.getMethod("getCards"), setCards = bean.getMethod("setCards", java.util.List.class);
        Method type = cardBean.getMethod("getType"), enable = cardBean.getMethod("setEnable", boolean.class);
        Method init = initializer.getDeclaredMethod("init"); init.setAccessible(true);
        Class<?> adapter = loader.loadClass("com.vivo.card.adapter.CardManagerAdapter");
        Method click = adapter.getDeclaredMethod("handleStagingCardClick", String.class);
        click.setAccessible(true);
        Method adapterContext = adapter.getMethod("getMContext");
        module.hook(click).intercept(chain -> {
            Context owner = (Context) adapterContext.invoke(chain.getThisObject());
            Intent destination = WalletCouponClient.managementIntent(owner);
            try {
                if (destination == null) throw new IllegalStateException("Wallet management unavailable");
                owner.startActivity(destination);
            } catch (Exception error) {
                Log.w("SuperCardCoupon", "Cannot open native wallet management", error);
                Toast.makeText(owner, "钱包券管理入口暂不可用", Toast.LENGTH_SHORT).show();
            }
            return null;
        });
        module.hook(init).intercept(chain -> {
            Context host = (Context) context.get(chain.getThisObject());
            boolean alreadyConfigured = hasCoupon(Settings.Secure.getString(host.getContentResolver(), "card_setting_data"));
            Object result = chain.proceed();
            if (alreadyConfigured || result == null) return result;
            try {
                ArrayList<Object> list = new ArrayList<>((java.util.List<?>) cards.invoke(result));
                Object coupon = null;
                for (Object card : list) if ("staging".equals(type.invoke(card))) coupon = card;
                if (coupon == null) return result;
                list.remove(coupon);
                enable.invoke(coupon, true);
                list.add(Math.min(2, list.size()), coupon);
                setCards.invoke(result, list);
                Object manager = settings.get(chain.getThisObject());
                if (!Boolean.TRUE.equals(manager.getClass().getMethod("saveCardSetBean", bean).invoke(manager, result)))
                    throw new IllegalStateException("Cannot persist new coupon card default");
                Log.i("SuperCardCoupon", "New original coupon card enabled in third default slot");
            } catch (Throwable error) { Log.e("SuperCardCoupon", "Cannot set new coupon default", error); }
            return result;
        });
        INSTALLED.add(loader);
    }

    private static boolean hasCoupon(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            JSONArray cards = new JSONObject(raw).optJSONArray("cards");
            if (cards != null) for (int i = 0; i < cards.length(); i++)
                if ("staging".equals(cards.getJSONObject(i).optString("type"))) return true;
        } catch (Exception ignored) { }
        return false;
    }
}
