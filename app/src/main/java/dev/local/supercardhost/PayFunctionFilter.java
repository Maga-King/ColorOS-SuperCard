package dev.local.supercardhost;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.libxposed.api.XposedModule;

/** Removes the two vivo-only PAY entries from defaults and existing selections. */
final class PayFunctionFilter {
    private static boolean removed(String type) {
        return "vivo_scan".equals(type) || "vivo_pay".equals(type);
    }

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> initializer = loader.loadClass("com.vivo.card.data.CardDataInitializer");
        Method defaults = initializer.getDeclaredMethod("getOrLoadDefaultJson");
        defaults.setAccessible(true);
        module.hook(defaults).intercept(chain -> {
            String json = (String) chain.proceed();
            return filterJson(json);
        });
        Class<?> manager = loader.loadClass("com.vivo.card.cards.commondata.local.CardSettingsManager");
        Class<?> config = loader.loadClass("com.vivo.card.model.CardSetBean");
        Class<?> card = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean");
        Class<?> function = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean$FunctionBean");
        Method cards = config.getMethod("getCards"), type = card.getMethod("getType");
        Method functions = card.getMethod("getFunctions"), set = card.getMethod("setFunctions", List.class);
        Method functionType = function.getMethod("getType");
        module.hook(manager.getDeclaredMethod("getCardSetBean")).intercept(chain -> {
            Object result = chain.proceed();
            if (result != null) {
                List<?> list = (List<?>) cards.invoke(result);
                if (list != null) for (Object entry : list) {
                    if (!"pay".equals(type.invoke(entry))) continue;
                    List<?> old = (List<?>) functions.invoke(entry);
                    if (old == null) continue;
                    ArrayList<Object> kept = new ArrayList<>();
                    for (Object item : old) if (!removed((String) functionType.invoke(item))) kept.add(item);
                    if (kept.size() != old.size()) set.invoke(entry, kept);
                }
            }
            return result;
        });
    }

    static void migrate(Context context) {
        try {
            String old = Settings.Secure.getString(context.getContentResolver(), "card_setting_data");
            String filtered = filterJson(old);
            if (!java.util.Objects.equals(old, filtered)
                    && !Settings.Secure.putString(context.getContentResolver(), "card_setting_data", filtered)) {
                throw new IllegalStateException("Cannot save PAY function migration");
            }
        } catch (Exception error) { Log.e("SuperCardSettings", "Cannot filter PAY functions", error); }
    }

    private static String filterJson(String json) throws Exception {
        if (json == null || json.isEmpty()) return json;
        JSONObject config = new JSONObject(json);
        JSONArray cards = config.optJSONArray("cards");
        boolean changed = false;
        if (cards != null) for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.getJSONObject(i);
            if (!"pay".equals(card.optString("type"))) continue;
            JSONArray functions = card.optJSONArray("functions");
            if (functions == null) continue;
            for (int j = functions.length() - 1; j >= 0; j--) {
                if (removed(functions.getJSONObject(j).optString("type"))) {
                    functions.remove(j); changed = true;
                }
            }
        }
        return changed ? config.toString() : json;
    }
}
