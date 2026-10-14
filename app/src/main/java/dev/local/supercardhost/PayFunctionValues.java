package dev.local.supercardhost;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Configuration filtering shared by settings and the injected card runtime. */
final class PayFunctionValues {
    private static boolean removed(String type) {
        return "vivo_scan".equals(type) || "vivo_pay".equals(type);
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

    static String filterJson(String json) throws Exception {
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
