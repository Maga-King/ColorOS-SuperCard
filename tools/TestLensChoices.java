package dev.local.supercardhost;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline migration regression checks against every bundled OEM default template. */
public final class TestLensChoices {
    private static JSONObject lens(JSONObject config) throws Exception {
        JSONArray cards = config.getJSONArray("cards");
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.getJSONObject(i);
            if ("camera".equals(card.optString("type"))) return card;
        }
        throw new AssertionError("No Lens card");
    }

    private static List<String> order(JSONObject card) throws Exception {
        ArrayList<String> types = new ArrayList<>();
        JSONArray functions = card.getJSONArray("functions");
        for (int i = 0; i < functions.length(); i++) types.add(functions.getJSONObject(i).getString("type"));
        return types;
    }

    private static Set<String> enabled(JSONObject card) throws Exception {
        HashSet<String> result = new HashSet<>();
        JSONArray functions = card.getJSONArray("functions");
        for (int i = 0; i < functions.length(); i++) {
            JSONObject function = functions.getJSONObject(i);
            if (function.optBoolean("enable")) result.add(function.getString("type"));
        }
        return result;
    }

    private static void select(JSONObject card, Set<String> choices) throws Exception {
        JSONArray functions = card.getJSONArray("functions");
        for (int i = 0; i < functions.length(); i++) {
            JSONObject function = functions.getJSONObject(i);
            function.put("enable", choices.contains(function.getString("type")));
            function.put("support", false).put("info", "stale-provider-result");
        }
    }

    private static void verify(JSONObject card) throws Exception {
        int supported = 0;
        JSONArray functions = card.getJSONArray("functions");
        for (int i = 0; i < functions.length(); i++) {
            JSONObject function = functions.getJSONObject(i);
            if (!function.getBoolean("support")) {
                check(!function.getBoolean("enable"), "Removed mode still enabled");
                continue;
            }
            supported++;
            String type = function.getString("type");
            JSONObject info = new JSONObject(function.getString("info"));
            check(info.getString("option").equals("coloros17:" + type), "Stale camera info");
            if (type.equals("master")) check(info.getString("moduleId").equals("4"), "Master entry changed");
            if (type.equals("high_piexl")) check(info.getString("moduleId").equals("24"), "High pixel entry changed");
        }
        check(supported == 11, "Expected exactly 11 choices, got " + supported);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        int cases = 0;
        try (ZipFile zip = new ZipFile(args[0])) {
            JSONObject defaults = new JSONObject(new String(zip.getInputStream(
                    zip.getEntry("assets/card_default_data.json")).readAllBytes(), StandardCharsets.UTF_8));
            LensSettingsValues.addMasterFunction(defaults, false);
            JSONObject template = lens(defaults);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("assets/card_default_data.*\\.json")) continue;
                String json = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                for (Set<String> selected : List.of(Set.of("high_piexl"), Set.of("video", "portrait"),
                        Set.of("take_photo", "portrait", "video", "night_view", "panoramic", "high_piexl"),
                        Set.<String>of(), Set.of("profession", "super_moon", "short_video"))) {
                    JSONObject card = lens(new JSONObject(json));
                    card.put("skinName", "user-custom-background").put("enable", false);
                    select(card, selected);
                    // Exercise saved custom ordering, not just the bundled order.
                    JSONArray functions = card.getJSONArray("functions");
                    JSONArray reversed = new JSONArray();
                    for (int i = functions.length() - 1; i >= 0; i--) reversed.put(functions.getJSONObject(i));
                    card.put("functions", reversed);
                    List<String> before = order(card);
                    LensSettingsValues.applyFixedChoices(card, template);
                    verify(card);
                    check(order(card).subList(0, before.size()).equals(before), "Saved order changed");
                    check(!card.getBoolean("enable"), "Disabled card enabled");
                    check(card.getString("skinName").equals("user-custom-background"), "Background changed");
                    Set<String> expected = selected.isEmpty() || selected.contains("profession")
                            ? Set.of("take_photo", "portrait", "video", "night_view", "master", "high_piexl") : selected;
                    check(enabled(card).equals(expected), "User selection changed: " + enabled(card));
                    String once = card.toString();
                    LensSettingsValues.applyFixedChoices(card, template);
                    check(once.equals(card.toString()), "Second migration changed data");
                    cases++;
                }
            }
            JSONObject empty = new JSONObject().put("type", "camera");
            LensSettingsValues.applyFixedChoices(empty, template);
            verify(empty);
            check(enabled(empty).size() == 6, "Empty configuration not recovered");
            cases++;
        }
        System.out.println("PASS: " + cases + " Lens migration cases across all OEM templates");
    }
}
