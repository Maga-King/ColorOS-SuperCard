package dev.local.supercardhost;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
            if (revision >= 6) return;
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
            applyFixedChoices(lens, defaultLens(host));
            if (!Settings.Secure.putString(host.getContentResolver(), "card_setting_data", config.toString())
                    || !Settings.Secure.putString(host.getContentResolver(), "key_need_format_camera_function", "false"))
                throw new IllegalStateException("Cannot migrate Lens configuration");
            if (!Settings.Secure.putInt(host.getContentResolver(), REVISION, 6))
                throw new IllegalStateException("Cannot record Lens migration");
            Log.i(TAG, "Fixed Lens choices migrated");
        } catch (Throwable error) { Log.e(TAG, "Cannot prepare original Lens configuration", error); }
    }

    /** Repair cached support/info synchronously, retaining the user's enabled choices and order. */
    static void applyFixedChoices(JSONObject lens, JSONObject template) throws Exception {
        JSONArray functions = lens.optJSONArray("functions");
        if (functions == null) { functions = new JSONArray(); lens.put("functions", functions); }
        // Some original templates (e.g. 2112) omit the mirror entirely. Restore
        // missing entries from the bundled resources without reordering saved ones.
        for (ColorOsLensModes.Mode mode : ColorOsLensModes.query(null)) {
            if (findFunction(functions, mode.type()) != null) continue;
            JSONObject source = findFunction(template.getJSONArray("functions"), mode.type());
            if (source == null) throw new IllegalStateException("Lens template missing " + mode.type());
            functions.put(new JSONObject(source.toString()).put("enable", false));
        }
        int enabled = 0;
        for (int i = 0; i < functions.length(); i++) {
            JSONObject function = functions.getJSONObject(i);
            ColorOsLensModes.Mode selected = null;
            for (ColorOsLensModes.Mode mode : ColorOsLensModes.query(null)) {
                if (mode.type().equals(function.optString("type"))) { selected = mode; break; }
            }
            function.put("support", selected != null);
            if (selected == null) { function.put("enable", false); continue; }
            JSONObject info = new JSONObject().put("id", selected.vivoId())
                    .put("name", selected.title()).put("cameraId", selected.front() ? "1" : "0")
                    .put("moduleId", Integer.toString(selected.cameraMode()))
                    .put("option", "coloros17:" + selected.type());
            function.put("info", info.toString());
            if (function.optBoolean("enable")) enabled++;
        }
        // A previous empty capability result may leave no selected function at all.
        if (enabled == 0) {
            java.util.Set<String> defaults = java.util.Set.of("take_photo", "portrait", "video",
                    "night_view", "master", "high_piexl");
            for (int i = 0; i < functions.length(); i++) {
                JSONObject function = functions.getJSONObject(i);
                function.put("enable", function.optBoolean("support")
                        && defaults.contains(function.optString("type")));
            }
        }
    }

    private static JSONObject findFunction(JSONArray functions, String type) throws Exception {
        for (int i = 0; i < functions.length(); i++) {
            JSONObject function = functions.getJSONObject(i);
            if (type.equals(function.optString("type"))) return function;
        }
        return null;
    }

    private static JSONObject defaultLens(Context context) throws Exception {
        try (var stream = context.getAssets().open("card_default_data.json");
             var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject config = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            addMasterFunction(config, false);
            JSONArray cards = config.getJSONArray("cards");
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                if ("camera".equals(card.optString("type"))) return card;
            }
        }
        throw new IllegalStateException("Bundled Lens template missing");
    }

    /** Synchronous replacement for the OEM provider worker, also used after reset/restore. */
    static void formatOriginal(Object initializer, Object config, Object lens) throws Exception {
        var contextField = initializer.getClass().getDeclaredField("context");
        contextField.setAccessible(true);
        Context context = (Context) contextField.get(initializer);
        JSONObject template = defaultLens(context);
        ClassLoader loader = initializer.getClass().getClassLoader();
        Class<?> gsonType = loader.loadClass("com.google.gson.Gson");
        Object gson = gsonType.getDeclaredConstructor().newInstance();
        Method toJson = gsonType.getMethod("toJson", Object.class);
        Method fromJson = gsonType.getMethod("fromJson", String.class, Class.class);
        if (lens == null) {
            Class<?> cardType = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean");
            lens = fromJson.invoke(gson, template.toString(), cardType);
            List<?> cards = (List<?>) config.getClass().getMethod("getCards").invoke(config);
            ArrayList<Object> updated = cards == null ? new ArrayList<>() : new ArrayList<>(cards);
            updated.add(lens);
            config.getClass().getMethod("setCards", List.class).invoke(config, updated);
        }
        JSONObject json = new JSONObject((String) toJson.invoke(gson, lens));
        applyFixedChoices(json, template);
        Object repaired = fromJson.invoke(gson, json.toString(), lens.getClass());
        // Update the same card object: the caller still holds it and saves it again.
        Object functions = lens.getClass().getMethod("getFunctions").invoke(repaired);
        lens.getClass().getMethod("setFunctions", List.class).invoke(lens, functions);
        var managerField = initializer.getClass().getDeclaredField("settingsManager");
        managerField.setAccessible(true);
        Object manager = managerField.get(initializer);
        if (!Boolean.TRUE.equals(manager.getClass().getMethod("saveCardSetBean", config.getClass())
                .invoke(manager, config))) throw new IllegalStateException("Cannot save fixed Lens choices");
        if (!Boolean.TRUE.equals(manager.getClass().getMethod("setNeedFormatCameraFunction", String.class)
                .invoke(manager, "false"))) throw new IllegalStateException("Cannot finish Lens formatting");
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
