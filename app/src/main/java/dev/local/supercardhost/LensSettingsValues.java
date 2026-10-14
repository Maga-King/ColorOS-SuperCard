package dev.local.supercardhost;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/** Original settings migrations shared without a dependency on the hook framework. */
final class LensSettingsValues {
    private static final String TAG = "SuperCardLens";
    private static final String REVISION = "supercard_lens_adapter_revision";
    /** Only transforms the default template: an existing user's order is never sorted. */
    static void applyDefaultCardChoices(JSONObject config) throws Exception {
        JSONArray cards = config.optJSONArray("cards");
        if (cards == null) return;
        JSONArray ordered = new JSONArray();
        String[] priority = {"pay", "memory", "staging", "camera"};
        for (String type : priority) {
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                if (!type.equals(card.optString("type"))) continue;
                if ("camera".equals(type)) card.put("enable", true);
                ordered.put(card);
            }
        }
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.getJSONObject(i);
            String type = card.optString("type");
            boolean prioritized = false;
            for (String known : priority) if (known.equals(type)) prioritized = true;
            if (!prioritized) ordered.put(card);
        }
        config.put("cards", ordered);
    }

    /** A one-time migration enables this requested card without resetting other card choices. */
    public static void prepareConfiguration(Context host) {
        PayFunctionValues.migrate(host);
        try {
            int revision = Settings.Secure.getInt(host.getContentResolver(), REVISION, 0);
            if (revision >= 5) return;
            String previous = Settings.Secure.getString(host.getContentResolver(), "card_setting_data");
            if (previous == null || previous.isEmpty()) return; // Original initializer owns fresh setup.
            JSONObject config = new JSONObject(previous);
            JSONArray cards = config.getJSONArray("cards");
            JSONObject lens = null;
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                if ("camera".equals(card.optString("type"))) lens = card;
            }
            if (lens == null) return;
            File backup = new File(host.getFilesDir(), "supercard-lens-settings-before.json");
            if (!backup.exists()) {
                try (FileOutputStream output = new FileOutputStream(backup)) {
                    output.write(previous.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            }
            if (revision < 1) lens.put("enable", true);
            boolean masterSupported = ColorOsLensModes.query(host).stream()
                    .anyMatch(mode -> "master".equals(mode.type()));
            addMasterFunction(config, masterSupported);
            if (!Settings.Secure.putString(host.getContentResolver(), "card_setting_data", config.toString())
                    || !Settings.Secure.putString(host.getContentResolver(), "key_need_format_camera_function", "true"))
                throw new IllegalStateException("Cannot migrate Lens configuration");
            if (!Settings.Secure.putInt(host.getContentResolver(), REVISION, 5))
                throw new IllegalStateException("Cannot record Lens migration");
            Log.i(TAG, "Lens adapter migration complete; original function formatter requested");
        } catch (Throwable error) { Log.e(TAG, "Cannot prepare original Lens configuration", error); }
    }

    /** Extend the original model, keeping its existing resources and six-row limit. */
    static void addMasterFunction(JSONObject config, boolean enableNew) throws Exception {
        JSONArray cards = config.optJSONArray("cards");
        if (cards == null) return;
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.getJSONObject(i);
            if (!"camera".equals(card.optString("type"))) continue;
            JSONArray functions = card.getJSONArray("functions");
            JSONObject professional = null;
            int enabled = 0;
            for (int j = 0; j < functions.length(); j++) {
                JSONObject function = functions.getJSONObject(j);
                if ("master".equals(function.optString("type"))) return;
                if ("profession".equals(function.optString("type"))) professional = function;
                if (function.optBoolean("enable")) enabled++;
            }
            if (professional == null) throw new IllegalStateException("Original professional icon missing");
            JSONObject master = new JSONObject(professional.toString());
            master.put("type", "master").put("type_name", "大师模式")
                    .put("nameResId", "master").put("nameResId2", "")
                    .put("info", "").put("support", false)
                    .put("enable", enableNew && enabled < 6);
            JSONArray updated = new JSONArray();
            boolean inserted = false;
            for (int j = 0; j < functions.length(); j++) {
                JSONObject function = functions.getJSONObject(j);
                updated.put(function);
                if ("night_view".equals(function.optString("type"))) {
                    updated.put(master); inserted = true;
                }
            }
            if (!inserted) updated.put(master);
            card.put("functions", updated);
        }
    }

}
