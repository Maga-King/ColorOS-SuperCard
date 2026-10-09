package dev.local.supercardhost;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/** Supplies real ColorOS camera modes to the original vivo Lens card and settings. */
public final class LensRuntimeBridge {
    private static final String TAG = "SuperCardLens";
    private static final String PREFIX = "coloros17:";
    private static final String REVISION = "supercard_lens_adapter_revision";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private LensRuntimeBridge() {}

    public static synchronized void install(XposedModule module, ClassLoader loader) {
        if (INSTALLED.contains(loader)) return;
        try {
            Class<?> initializer = loader.loadClass("com.vivo.card.data.CardDataInitializer");
            module.hook(method(initializer, "getCameraFunType", int.class)).intercept(chain ->
                    ((Integer) chain.getArg(0)) == 17 ? "master" : chain.proceed());
            module.hook(method(initializer, "getOrLoadDefaultJson")).intercept(chain -> {
                String json = (String) chain.proceed();
                if (json == null || json.isEmpty()) return json;
                JSONObject defaults = new JSONObject(json);
                applyDefaultCardChoices(defaults);
                addMasterFunction(defaults, false);
                return defaults.toString();
            });
            Class<?> bean = loader.loadClass("com.vivo.card.model.CameraFunctionBean");
            Field initializerContext = field(initializer, "context");
            Method query = method(initializer, "queryCameraFunctions");
            if (!java.util.List.class.isAssignableFrom(query.getReturnType()))
                throw new NoSuchMethodException("Original camera capability signature changed");
            module.hook(query).intercept(chain -> {
                Context context = (Context) initializerContext.get(chain.getThisObject());
                ArrayList<Object> result = new ArrayList<>();
                try {
                    for (ColorOsLensModes.Mode mode : ColorOsLensModes.query(context)) {
                        Object value = bean.getDeclaredConstructor().newInstance();
                        method(bean, "setId", int.class).invoke(value, mode.vivoId());
                        method(bean, "setName", String.class).invoke(value, mode.title());
                        method(bean, "setCameraId", String.class).invoke(value, mode.front() ? "1" : "0");
                        method(bean, "setModuleId", String.class).invoke(value, Integer.toString(mode.cameraMode()));
                        method(bean, "setOption", String.class).invoke(value, PREFIX + mode.type());
                        result.add(value);
                    }
                    Log.i(TAG, "Real camera capabilities supplied to original Lens, modes=" + result.size());
                } catch (Throwable error) {
                    Log.e(TAG, "Cannot query ColorOS camera capabilities", error);
                }
                return result;
            });
            Class<?> configuration = loader.loadClass("com.vivo.card.model.CardSetBean");
            Class<?> cardType = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean");
            Method format = method(initializer, "processCameraFunctionFormat",
                    configuration, cardType, boolean.class);
            Field settingsManager = field(initializer, "settingsManager");
            module.hook(method(initializer, "init")).intercept(chain -> {
                Object result = chain.proceed();
                Context context = (Context) initializerContext.get(chain.getThisObject());
                // Before init a fresh install has no JSON to migrate. The original
                // initializer has now saved it, so complete the migration here too.
                String before = Settings.Secure.getString(context.getContentResolver(), "card_setting_data");
                prepareConfiguration(context);
                String after = Settings.Secure.getString(context.getContentResolver(), "card_setting_data");
                if (!java.util.Objects.equals(before, after)) {
                    Object manager = settingsManager.get(chain.getThisObject());
                    Object migrated = method(manager.getClass(), "getCardSetBean").invoke(manager);
                    if (migrated != null) result = migrated;
                }
                if (result != null && "true".equals(Settings.Secure.getString(
                        context.getContentResolver(), "key_need_format_camera_function"))) {
                    Object lens = null;
                    for (Object card : (java.util.List<?>) method(configuration, "getCards").invoke(result)) {
                        if ("camera".equals(method(cardType, "getType").invoke(card))) lens = card;
                    }
                    // Existing settings take a fast initialization path which does
                    // not invoke the original camera formatter on its own.
                    if (lens != null) format.invoke(chain.getThisObject(), result, lens, true);
                }
                return result;
            });
            Class<?> util = loader.loadClass("com.vivo.card.utils.CardUtil");
            Class<?> cameraUtils = loader.loadClass(
                    "com.vivo.card.cards.cardcamera.utils.CameraCardUtils");
            // The installed package is the host, while the original resource
            // table deliberately keeps com.vivo.card/0x7f and its numeric IDs.
            // Keep the calling Context's configuration/density and resolve only
            // these original Lens names against their actual resource package.
            module.hook(method(cameraUtils, "getResIdByName", Context.class,
                    String.class, String.class)).intercept(chain -> {
                Context context = (Context) chain.getArg(0);
                int id = context.getResources().getIdentifier((String) chain.getArg(1),
                        (String) chain.getArg(2), "com.vivo.card");
                return id != 0 ? id : chain.proceed();
            });
            for (Class<?> names : new Class<?>[]{util, cameraUtils}) {
                module.hook(method(names, "getStringByResName", Context.class, String.class))
                        .intercept(chain -> {
                            String name = (String) chain.getArg(1);
                            if ("master".equals(name)) return "大师模式";
                            if (names == cameraUtils) {
                                Context context = (Context) chain.getArg(0);
                                int id = context.getResources().getIdentifier(name,
                                        "string", "com.vivo.card");
                                if (id != 0) return context.getString(id);
                            }
                            return chain.proceed();
                        });
            }
            Method launch = method(util, "startCameraFuncition", Context.class,
                    String.class, String.class, String.class);
            module.hook(launch).intercept(chain -> {
                Context context = (Context) chain.getArg(0);
                String option = (String) chain.getArg(3);
                try {
                    if (option == null || !option.startsWith(PREFIX))
                        throw new IllegalArgumentException("请在 LENS 设置中刷新相机功能");
                    String type = option.substring(PREFIX.length());
                    ColorOsLensModes.launch(context, type);
                    Log.i(TAG, "Launched native camera mode from Lens: " + type);
                } catch (Throwable error) {
                    Log.e(TAG, "Cannot launch Lens camera mode", error);
                    toast(context, error.getMessage() == null ? "相机功能暂不可用" : error.getMessage());
                }
                return null;
            });
            // This old provider only reports vivo's alternate localized mode names.
            // Names and support now come from the original card resources and real modes.
            module.hook(method(initializer, "queryCameraFuncNameChanged")).intercept(chain -> null);
            installUnlockContinuation(module, loader);
            Class<?> compose = loader.loadClass("androidx.compose.foundation.lazy.LazyDslKt");
            Log.i(TAG, "Original Lens Compose loader=" + compose.getClassLoader());
            INSTALLED.add(loader);
            Log.i(TAG, "Original Lens capability and launch bridge installed");
        } catch (Throwable error) { Log.e(TAG, "Cannot install Lens adapter", error); }
    }

    /** Only transforms the default template: an existing user's order is never sorted. */
    private static void applyDefaultCardChoices(JSONObject config) throws Exception {
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
        try {
            int revision = Settings.Secure.getInt(host.getContentResolver(), REVISION, 0);
            if (revision >= 3) return;
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
            if (!Settings.Secure.putInt(host.getContentResolver(), REVISION, 3))
                throw new IllegalStateException("Cannot record Lens migration");
            Log.i(TAG, "Lens adapter migration complete; original function formatter requested");
        } catch (Throwable error) { Log.e(TAG, "Cannot prepare original Lens configuration", error); }
    }

    /** Extend the original model, keeping its existing resources and six-row limit. */
    private static void addMasterFunction(JSONObject config, boolean enableNew) throws Exception {
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

    private static void installUnlockContinuation(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> handler = loader.loadClass("com.vivo.card.cards.cardcamera.handler.CameraCardEventHandler");
        Class<?> function = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean$FunctionBean");
        Field contextField = field(handler, "context");
        Method expanded = method(handler, "isCardExpanded");
        Method start = method(handler, "startCameraFunction", Context.class, function);
        Method requestUnlock = method(handler, "requestUnlock", Context.class);
        Class<?> invalidation = loader.loadClass("com.vivo.card.helper.InvalidateRegionHelper");
        Method invalidationInstance = method(invalidation, "getInstance");
        Method setCardShow = method(invalidation, "setCardShow", boolean.class);
        Map<Object, Object> pending = Collections.synchronizedMap(new WeakHashMap<>());
        module.hook(method(handler, "handleFunctionClick", Context.class, function)).intercept(chain -> {
            Object owner = chain.getThisObject();
            Context context = (Context) chain.getArg(0);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard != null && keyguard.isKeyguardLocked() && Boolean.TRUE.equals(expanded.invoke(owner))) {
                pending.put(owner, chain.getArg(1));
                setCardShow.invoke(invalidationInstance.invoke(null), false);
                // The original isUserUnlocked checks credential storage, which stays
                // unlocked after the first boot unlock. Use the real screen lock here.
                requestUnlock.invoke(owner, context);
                return null;
            }
            pending.remove(owner);
            return chain.proceed();
        });
        module.hook(method(handler, "handleSettingsClick", Context.class)).intercept(chain -> {
            pending.remove(chain.getThisObject());
            return chain.proceed();
        });
        module.hook(method(handler, "resetPendingKeyguardRequest")).intercept(chain -> {
            pending.remove(chain.getThisObject());
            return chain.proceed();
        });
        module.hook(method(handler, "onKeyguardSuccess")).intercept(chain -> {
            Object owner = chain.getThisObject();
            Object selected = pending.remove(owner);
            Object result = chain.proceed();
            if (selected != null) {
                Context context = (Context) contextField.get(owner);
                KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                if (keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked())
                    start.invoke(owner, context, selected);
            }
            return result;
        });
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method result = type.getDeclaredMethod(name, args); result.setAccessible(true); return result;
    }
}
