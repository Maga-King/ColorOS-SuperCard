package dev.local.supercardhost;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit calls from patched OEM DEX. This class and its runtime dependencies need no Xposed API. */
public final class SelfSettingsCompat {
    private static final String TAG = "SuperCardSelf";
    private static final String PACKAGE = "dev.local.supercardhost";
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> ORIGINALS = new ConcurrentHashMap<>();
    private static boolean announced;

    private SelfSettingsCompat() {}

    /** The owner class is embedded in each DEX wrapper, so inherited methods resolve correctly. */
    public static Object invoke(Class<?> owner, Object receiver, String name, Object[] args) throws Throwable {
        Method original = original(owner, name);
        // The same asset also runs inside SystemUI, where existing framework hooks still own adaptation.
        if (!PACKAGE.equals(Application.getProcessName())) return call(original, receiver, args);
        if (!announced) {
            announced = true;
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/", "Ldalvik/system/");
            Log.i(TAG, "Built-in settings compatibility active; self injection is not required");
        }
        String type = owner.getName();
        if (name.equals("isStagingCardPluginAvailable")) {
            Context context = (Context) field(receiver, "context");
            try (java.io.InputStream archive = context.getAssets().open("coupon-plugin.apk")) {
                return archive.read() == 'P' && archive.read() == 'K';
            } catch (Exception unavailable) { return false; }
        }
        if (name.equals("bindImageCardView")) {
            android.widget.ImageView previous = SelfPreviewCompat.begin((android.widget.ImageView) args[0], args[2]);
            try { return call(original, receiver, args); }
            finally { SelfPreviewCompat.end(previous); }
        }
        if (name.equals("createViewToBitmap")) {
            android.graphics.Bitmap bitmap = SelfPreviewCompat.capture((View) args[0], (Integer) args[1], (Integer) args[2]);
            if (bitmap != null) return bitmap;
        }
        if (name.equals("setupCardViewScale")
                && SelfPreviewCompat.live((View) args[0], (android.widget.ImageView) args[1])) return null;
        if (name.equals("onBindViewHolder")
                && Integer.valueOf(1007).equals(invoke(args[0], "getItemViewType"))) {
            // Keep the OEM adapter's drag/function positions stable, but remove the unsupported row.
            View row = (View) args[0].getClass().getField("itemView").get(args[0]);
            row.setVisibility(View.GONE);
            android.view.ViewGroup.LayoutParams params = row.getLayoutParams();
            params.height = 0;
            if (params instanceof android.view.ViewGroup.MarginLayoutParams margins) {
                margins.topMargin = 0; margins.bottomMargin = 0;
            }
            row.setLayoutParams(params);
            return null;
        }
        if (name.equals("handleStagingCardClick")) {
            Context context = (Context) invoke(receiver, "getMContext");
            try {
                Intent destination = WalletCouponClient.managementIntent(context);
                if (destination == null) throw new IllegalStateException("Wallet management unavailable");
                context.startActivity(destination);
            } catch (Exception error) {
                Log.w(TAG, "Cannot open wallet management", error);
                Toast.makeText(context, "钱包券管理入口暂不可用", Toast.LENGTH_SHORT).show();
            }
            return null;
        }
        if (name.equals("handleSwitchStateOnFirstBoot")) {
            HostSettingsValues.ensure((Context) field(receiver, "context"));
            return null;
        }
        if (name.equals("queryCameraFunctions")) return cameraFunctions(receiver, owner.getClassLoader());
        if (name.equals("processCameraFunctionFormat")) {
            LensSettingsValues.formatOriginal(receiver, args[0], args[1]);
            return null;
        }
        if (name.equals("queryCameraFuncNameChanged")) return null;
        if (name.equals("getCameraFunType") && Integer.valueOf(17).equals(args[0])) return "master";
        if (name.equals("getResIdByName")) {
            Context context = (Context) args[0];
            int id = context.getResources().getIdentifier((String) args[1], (String) args[2], "com.vivo.card");
            if (id != 0) return id;
        }
        if (name.equals("getStringByResName")) {
            if ("master".equals(args[1])) return "大师模式";
            if (type.endsWith("CameraCardUtils")) {
                Context context = (Context) args[0];
                int id = context.getResources().getIdentifier((String) args[1], "string", "com.vivo.card");
                if (id != 0) return context.getString(id);
            }
        }
        if (name.equals("onCreate") && receiver instanceof Activity activity) {
            HostSettingsValues.ensure(activity);
            activity.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Class<?> perf = owner.getClassLoader().loadClass("com.vivo.card.CardPerfContext");
            perf.getMethod("setOriginContext", Context.class).invoke(null, activity.getApplication());
            perf.getMethod("setPerfContext", Context.class).invoke(null, activity.getApplication());
            Class<?> skinType = owner.getClassLoader().loadClass("com.vivo.card.skin.controller.SkinManager");
            Object skins = skinType.getField("INSTANCE").get(null);
            android.util.SparseArray<?> strategies = (android.util.SparseArray<?>) skinType.getMethod("getMStrategyMap").invoke(skins);
            if (strategies.size() == 0) {
                skinType.getMethod("init", Context.class).invoke(skins, activity.getApplication());
                skinType.getMethod("loadSkin", Context.class).invoke(skins, activity.getApplication());
            }
        }
        if (name.equals("setSpanSummaryExText")) {
            try { adaptSettingsLink(owner.getClassLoader(), args); }
            catch (Throwable error) { Log.w(TAG, "Cannot adapt floating-bar link", error); }
        }
        boolean newCoupon = name.equals("init") && !hasCoupon((Context) field(receiver, "context"));
        Object result;
        try { result = call(original, receiver, args); }
        catch (Throwable error) {
            if (name.equals("getString") && "navigation_slide_card_range".equals(args[1]))
                return HostSettingsValues.ensureRange((Context) args[0], null);
            throw error;
        }
        // UI enhancements fail independently; never re-run an original method after a side effect.
        try {
            switch (name) {
                case "onCreate" -> {
                    Activity activity = (Activity) receiver;
                    activity.getWindow().getDecorView().post(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) RootAppSetup.guardSettings(activity);
                    });
                }
                case "setInitData", "setRefreshData" -> SelfPreviewCompat.repairEmptyLens((View) receiver);
                case "initPreference", "updateAllPreferenceVisibility" -> updatePreferences(receiver);
                case "onBindViewHolder" -> updateEditorColor(args[0]);
                case "bind4UsingHolderItem" -> updateEditorButton(receiver, args[0]);
                case "getOrLoadDefaultJson" -> result = defaults((String) result);
                case "getCardSetBean" -> filterPay(result);
                case "init" -> result = finishInitialization(receiver, result, newCoupon);
                case "getString" -> {
                    if ("navigation_slide_card_range".equals(args[1]))
                        result = HostSettingsValues.ensureRange((Context) args[0], (String) result);
                }
                default -> { }
            }
        } catch (Throwable error) { Log.w(TAG, "Settings adaptation failed: " + name, error); }
        return result;
    }

    private static Method original(Class<?> owner, String name) throws NoSuchMethodException {
        ConcurrentHashMap<String, Method> methods = ORIGINALS.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>());
        Method cached = methods.get(name);
        if (cached != null) return cached;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (candidate.getName().equals("supercardOriginal$" + name)) {
                candidate.setAccessible(true);
                methods.put(name, candidate);
                return candidate;
            }
        }
        throw new NoSuchMethodException(owner.getName() + '#' + name);
    }

    private static Object call(Method method, Object receiver, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static void updatePreferences(Object fragment) throws Exception {
        ClassLoader loader = fragment.getClass().getClassLoader();
        Class<?> preference = loader.loadClass("androidx.preference.Preference");
        Method find = fragment.getClass().getMethod("findPreference", CharSequence.class);
        for (String key : new String[]{"lock_screen_open_style", "reset_screen_open_style"}) {
            Object item = find.invoke(fragment, key);
            if (item != null) preference.getMethod("setVisible", boolean.class).invoke(item, false);
        }
        if (find.invoke(fragment, "supercard_host_tools") != null) return;
        Context context = (Context) fragment.getClass().getMethod("requireContext").invoke(fragment);
        Object item = preference.getConstructor(Context.class).newInstance(context);
        preference.getMethod("setKey", String.class).invoke(item, "supercard_host_tools");
        preference.getMethod("setTitle", CharSequence.class).invoke(item, "运行状态与 PAY 设置");
        preference.getMethod("setSummary", CharSequence.class).invoke(item, "检查 Root、框架和卡包状态，设置 PAY 白名单");
        preference.getMethod("setPersistent", boolean.class).invoke(item, false);
        preference.getMethod("setIntent", Intent.class).invoke(item,
                new Intent().setClassName(PACKAGE, PACKAGE + ".HostToolsActivity"));
        Object screen = fragment.getClass().getMethod("getPreferenceScreen").invoke(fragment);
        screen.getClass().getMethod("addPreference", preference).invoke(screen, item);
    }

    private static void adaptSettingsLink(ClassLoader loader, Object[] args) throws Exception {
        Object preference = args[0];
        if (!"screen_slide_card_switch".equals(preference.getClass().getMethod("getKey").invoke(preference))) return;
        String text = (String) args[1], keyword = (String) args[2];
        if (text == null || keyword == null || keyword.isEmpty() || !text.contains(keyword)) return;
        String label = "智能侧边栏·超级卡包浮标设置";
        Context context = (Context) preference.getClass().getMethod("getContext").invoke(preference);
        Class<?> function = loader.loadClass("kotlin.jvm.functions.Function0");
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Object click = Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, values) -> {
            if (method.getName().equals("invoke")) {
                CardConfiguration.request(context, "openFloatSettings", null, (state, error) -> {
                    if (error == null) return;
                    try {
                        Intent fallback = new Intent().setClassName(PACKAGE, PACKAGE + ".GestureAreaActivity");
                        if (!(context instanceof Activity)) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(fallback);
                    } catch (Exception failure) {
                        Log.w(TAG, "Cannot open floating-bar settings", failure);
                        Toast.makeText(context, "无法打开浮标设置", Toast.LENGTH_SHORT).show();
                    }
                });
                return unit;
            }
            return switch (method.getName()) {
                case "toString" -> "SuperCardSettingsLink";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> values != null && values.length == 1 && proxy == values[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
        });
        args[1] = text.replace(keyword, label); args[2] = label; args[4] = click;
    }

    private static void updateEditorColor(Object holder) throws Exception {
        int type = (Integer) holder.getClass().getMethod("getItemViewType").invoke(holder);
        if (type != 1002 && type != 1004) return;
        View row = (View) holder.getClass().getField("itemView").get(holder);
        int id = row.getResources().getIdentifier("tv_title", "id", "com.vivo.card");
        if (id == 0 || !(row.findViewById(id) instanceof TextView text)) return;
        boolean night = (row.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        String name = night ? "originui_dialog_list_main_item_text_color_rom15_0" : "settings_function_text_color";
        int color = row.getResources().getIdentifier(name, "color", "com.vivo.card");
        if (color != 0) text.setTextColor(row.getResources().getColorStateList(color, row.getContext().getTheme()));
        text.setForceDarkAllowed(false);
    }

    private static void updateEditorButton(Object holder, Object item) throws Exception {
        Object card = invoke(item, "getCardBean");
        String type = card == null ? null : (String) invoke(card, "getType");
        ((View) field(holder, "buttonSet")).setVisibility("memory".equals(type) || "staging".equals(type)
                ? View.GONE : View.VISIBLE);
    }

    private static boolean removedPay(String type) { return "vivo_scan".equals(type) || "vivo_pay".equals(type); }

    private static void filterPay(Object config) throws Exception {
        if (config == null) return;
        List<?> cards = (List<?>) invoke(config, "getCards");
        if (cards == null) return;
        for (Object card : cards) {
            if (!"pay".equals(invoke(card, "getType"))) continue;
            List<?> old = (List<?>) invoke(card, "getFunctions");
            if (old == null) continue;
            ArrayList<Object> kept = new ArrayList<>();
            for (Object function : old) if (!removedPay((String) invoke(function, "getType"))) kept.add(function);
            if (old.size() != kept.size()) card.getClass().getMethod("setFunctions", List.class).invoke(card, kept);
        }
    }

    private static String defaults(String json) throws Exception {
        String filtered = PayFunctionValues.filterJson(json);
        if (filtered == null || filtered.isEmpty()) return filtered;
        JSONObject config = new JSONObject(filtered);
        LensSettingsValues.applyDefaultCardChoices(config);
        LensSettingsValues.addMasterFunction(config, false);
        return config.toString();
    }

    private static Object cameraFunctions(Object initializer, ClassLoader loader) throws Exception {
        Context context = (Context) field(initializer, "context");
        Class<?> bean = loader.loadClass("com.vivo.card.model.CameraFunctionBean");
        ArrayList<Object> result = new ArrayList<>();
        for (ColorOsLensModes.Mode mode : ColorOsLensModes.query(context)) {
            Object value = bean.getDeclaredConstructor().newInstance();
            bean.getMethod("setId", int.class).invoke(value, mode.vivoId());
            bean.getMethod("setName", String.class).invoke(value, mode.title());
            bean.getMethod("setCameraId", String.class).invoke(value, mode.front() ? "1" : "0");
            bean.getMethod("setModuleId", String.class).invoke(value, Integer.toString(mode.cameraMode()));
            bean.getMethod("setOption", String.class).invoke(value, "coloros17:" + mode.type());
            result.add(value);
        }
        return result;
    }

    private static boolean hasCoupon(Context context) {
        String raw = Settings.Secure.getString(context.getContentResolver(), "card_setting_data");
        if (raw == null || raw.isBlank()) return false;
        try {
            JSONArray cards = new JSONObject(raw).optJSONArray("cards");
            if (cards != null) for (int i = 0; i < cards.length(); i++)
                if ("staging".equals(cards.getJSONObject(i).optString("type"))) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private static Object finishInitialization(Object initializer, Object config, boolean newCoupon) throws Exception {
        if (config == null) return null;
        Context context = (Context) field(initializer, "context");
        String before = Settings.Secure.getString(context.getContentResolver(), "card_setting_data");
        LensSettingsValues.prepareConfiguration(context);
        String after = Settings.Secure.getString(context.getContentResolver(), "card_setting_data");
        if (!java.util.Objects.equals(before, after)) {
            Object migrated = invoke(field(initializer, "settingsManager"), "getCardSetBean");
            if (migrated != null) config = migrated;
        }
        filterPay(config);
        List<?> cards = (List<?>) invoke(config, "getCards");
        if (cards == null) return config;
        if (newCoupon) {
            ArrayList<Object> ordered = new ArrayList<>(cards);
            Object coupon = null;
            for (Object card : cards) if ("staging".equals(invoke(card, "getType"))) coupon = card;
            if (coupon != null) {
                ordered.remove(coupon);
                coupon.getClass().getMethod("setEnable", boolean.class).invoke(coupon, true);
                ordered.add(Math.min(2, ordered.size()), coupon);
                config.getClass().getMethod("setCards", List.class).invoke(config, ordered);
                Object manager = field(initializer, "settingsManager");
                if (!Boolean.TRUE.equals(manager.getClass().getMethod("saveCardSetBean", config.getClass()).invoke(manager, config)))
                    throw new IllegalStateException("Cannot persist new coupon default");
            }
        }
        if (!"true".equals(Settings.Secure.getString(context.getContentResolver(), "key_need_format_camera_function"))) return config;
        for (Object card : cards) {
            if (!"camera".equals(invoke(card, "getType"))) continue;
            Method format = initializer.getClass().getDeclaredMethod("processCameraFunctionFormat",
                    config.getClass(), card.getClass(), boolean.class);
            format.setAccessible(true); format.invoke(initializer, config, card, true);
        }
        return config;
    }

    private static Object invoke(Object object, String name) throws Exception {
        return object.getClass().getMethod(name).invoke(object);
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
