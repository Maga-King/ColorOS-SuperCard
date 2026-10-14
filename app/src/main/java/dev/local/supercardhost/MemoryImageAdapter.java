package dev.local.supercardhost;

import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.json.JSONObject;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/** Two reviewed Portal contracts. Discovery never invokes target code or reads singletons. */
final class MemoryImageAdapter {
    private static final String PKG = "com.oplus.aimemory";
    final Class<?> model;
    final Constructor<?> info, request, modelConstructor;
    final Method importer, cleanup, save, modelFactory;
    final Field unit;

    private MemoryImageAdapter(ClassLoader loader) throws Exception {
        Class<?> image = load(loader, PKG + ".business_memory_entry.portal.usecase.PortalImageUseCase");
        model = load(loader, PKG + ".scene.bean.DataCollectMemory");
        info = load(loader, PKG + ".utils.ImageInfo").getDeclaredConstructor(
                String.class, Uri.class, String.class, int.class, int.class, long.class);
        Constructor<?> foundRequest = null;
        for (Class<?> nested : image.getDeclaredClasses()) {
            for (Constructor<?> candidate : nested.getDeclaredConstructors()) {
                if (!Arrays.equals(candidate.getParameterTypes(), new Class<?>[]{List.class,
                        String.class, String.class, String.class, boolean.class})) continue;
                if (foundRequest != null) throw new NoSuchMethodException("Ambiguous image request");
                foundRequest = candidate;
            }
        }
        if (foundRequest == null) throw new NoSuchMethodException("Image request signature changed");
        request = foundRequest;
        importer = unique(image, "image importer", method -> {
            Class<?>[] p = method.getParameterTypes();
            return p.length == 2 && p[0] == request.getDeclaringClass()
                    && continuation(p[1]) && method.getReturnType() == Object.class;
        }, true);
        cleanup = unique(image, "image cleanup", method -> method.getParameterCount() == 0
                && method.getReturnType() == void.class, true);
        modelFactory = unique(image, "legacy image model", method ->
                Arrays.equals(method.getParameterTypes(), new Class<?>[]{Uri.class, String.class, String.class})
                        && method.getReturnType() == model, false);
        Method legacySave = unique(image, "legacy image save", method ->
                Arrays.equals(method.getParameterTypes(), new Class<?>[]{Uri.class, String.class, String.class})
                        && method.getReturnType() == void.class, false);
        if (modelFactory != null && legacySave != null) {
            save = legacySave;
            unit = null;
            modelConstructor = null;
        } else {
            // A partially changed legacy contract is not the reviewed 17.3 coroutine contract.
            if (modelFactory != null || legacySave != null)
                throw new NoSuchMethodException("Incomplete legacy image contract");
            save = unique(image, "coroutine image save", method -> {
                Class<?>[] p = method.getParameterTypes();
                return p.length == 5 && p[0] == Uri.class && p[1] == String.class
                        && p[2] == String.class && p[3] == long.class && continuation(p[4])
                        && method.getReturnType() == Object.class;
            }, true);
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                // Confirm business identity as well as shape; do not accept an unrelated overload.
                Method anchored = dex.uniqueMethod("coroutine image save", MethodMatcher.create()
                        .declaredClass(image.getName()).name(save.getName())
                        .usingStrings("PortalImageUseCase", "saveImageAsMemory failed uri=", "portal", "1002"),
                        candidate -> candidate.equals(save));
                unit = unitField(dex, loader, anchored);
            }
            modelConstructor = model.getDeclaredConstructor(String.class, String.class, String.class,
                    long.class, long.class, String.class, String.class, String.class, String.class,
                    String.class, String.class, load(loader, PKG + ".db.bean.DataAbstract"),
                    String.class, Integer.class, String.class, String.class, String.class);
            String[] properties = {"PackageName", "ActivityName", "AppName", "CollectTimestamp",
                    "UpdateTime", "DataText", "DataEntity", "DataCategory", "Screenshot", "AudioFile",
                    "Deeplink", "DataAbstract", "SceneName", "DeviceType", "ExtraData", "SubSceneData", "MemoryType"};
            Class<?>[] params = modelConstructor.getParameterTypes();
            for (int i = 0; i < properties.length; i++) {
                if (model.getMethod("get" + properties[i]).getReturnType() != params[i])
                    throw new NoSuchMethodException("Image model property changed: " + properties[i]);
            }
        }
        if (model.getMethod("toMemoryWithDetails", String.class, String.class).getReturnType()
                != load(loader, PKG + ".db.bean.MemoryWithDetails"))
            throw new NoSuchMethodException("Image finalization signature changed");
        model.getMethod("getAttachments");
        model.getMethod("setScreenshot", String.class);
        model.getMethod("getExtraDataElement").getReturnType().getMethod("getImportId");
    }

    static MemoryImageAdapter resolve(ClassLoader loader) throws Exception {
        return new MemoryImageAdapter(loader);
    }

    Object completion() throws Exception {
        if (unit == null) return null;
        Object value = unit.get(null);
        if (value == null) throw new IllegalStateException("Native Kotlin Unit unavailable");
        return value;
    }

    Object createModel(Object useCase, Uri uri, String pkg, String name, String guid) throws Exception {
        if (modelFactory != null) return modelFactory.invoke(useCase, uri, pkg, name);
        // Same public Provider model as the old Portal factory. Nulls match its default arguments.
        // 1002 is the native Portal triggerType, also required in the save-method DEX anchor.
        String extra = new JSONObject().put("triggerType", "1002").put("importId", guid).toString();
        long now = System.currentTimeMillis();
        Object[] values = {pkg, null, name, now, -1L, "", null, null, uri.toString(),
                null, null, null, "portal", null, extra, null, null};
        Object result = modelConstructor.newInstance(values);
        String[] properties = {"PackageName", "ActivityName", "AppName", "CollectTimestamp",
                "UpdateTime", "DataText", "DataEntity", "DataCategory", "Screenshot", "AudioFile",
                "Deeplink", "DataAbstract", "SceneName", "DeviceType", "ExtraData", "SubSceneData", "MemoryType"};
        // Detect reordered same-type constructor parameters before any native write.
        for (int i = 0; i < properties.length; i++)
            if (!Objects.equals(values[i], model.getMethod("get" + properties[i]).invoke(result)))
                throw new IllegalStateException("Image model semantics changed: " + properties[i]);
        return result;
    }

    private static Field unitField(ObfuscationResolver dex, ClassLoader loader, Method save) throws Exception {
        MethodData body = dex.bridge().getMethodData(save);
        if (body == null) throw new NoSuchFieldException("Image save DEX unavailable");
        Map<String, Field> candidates = new LinkedHashMap<>();
        for (UsingFieldData read : body.getUsingFields()) {
            if (!read.getUsingType().isRead()) continue;
            if (!read.getField().getDeclaredClassName().equals(read.getField().getTypeName())) continue;
            Field field = read.getField().getFieldInstance(loader);
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                    || field.getType() != field.getDeclaringClass()) continue;
            try {
                dex.uniqueMethod("native Unit", MethodMatcher.create().declaredClass(field.getType().getName())
                        .name("toString").paramCount(0).returnType("java.lang.String").usingStrings("kotlin.Unit"),
                        method -> !Modifier.isStatic(method.getModifiers()));
                field.setAccessible(true);
                candidates.put(field.toGenericString(), field);
            } catch (NoSuchMethodException notUnit) { }
        }
        if (candidates.size() != 1) throw new NoSuchFieldException("Image completion Unit candidates=" + candidates.size());
        return candidates.values().iterator().next();
    }

    private interface Check { boolean matches(Method method); }
    private static Method unique(Class<?> owner, String role, Check check, boolean required) throws Exception {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || !check.matches(method)) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous " + role);
            method.setAccessible(true); found = method;
        }
        if (found == null && required) throw new NoSuchMethodException(role + " signature changed");
        return found;
    }

    private static boolean continuation(Class<?> type) {
        if (!type.isInterface()) return false;
        try { return type.getMethod("resumeWith", Object.class).getReturnType() == void.class
                && type.getMethod("getContext").getParameterCount() == 0; }
        catch (NoSuchMethodException ignored) { return false; }
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Bundle probe(ClassLoader loader) {
        Bundle report = new Bundle();
        try {
            MemoryImageAdapter adapter = resolve(loader);
            report.putString("imageContract", adapter.unit == null ? "legacy-provider" : "coroutine-provider-model");
            report.putString("imageImport", adapter.importer.toGenericString());
            report.putString("imageSave", adapter.save.toGenericString());
            report.putString("imageModel", adapter.modelFactory == null
                    ? adapter.modelConstructor.toGenericString() : adapter.modelFactory.toGenericString());
            report.putString("imageCompletion", adapter.unit == null ? "void" : adapter.unit.toGenericString());
        } catch (Throwable failure) {
            report.putString("imageContractError", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return report;
    }
}
