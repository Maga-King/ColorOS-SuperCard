package dev.local.supercardhost;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/** Discovery only: never invokes an importer, creates a native model or changes a native gate. */
public final class MemoryDexResolver {
    private static final String PKG = "com.oplus.aimemory";
    private static final String TAG = "SuperCardMemoryDex";
    private static final Map<ClassLoader, Map<String, Method>> CACHE = new WeakHashMap<>();
    private MemoryDexResolver() { }

    public static Method gate(ClassLoader loader, String role, boolean forceDexKit) throws Exception {
        if (!forceDexKit) {
            synchronized (CACHE) {
                Method cached = CACHE.computeIfAbsent(loader, ignored -> new LinkedHashMap<>()).get(role);
                if (cached != null) return cached;
            }
        }
        Method result;
        try {
            if (forceDexKit) throw new NoSuchMethodException("Forced read-only DexKit probe");
            String owner, name;
            switch (role) {
                case "privacy": owner = PKG + ".utils.p2"; name = "l"; break;
                case "service": owner = PKG + ".utils.f0"; name = "P"; break;
                case "network": owner = PKG + ".network.NetworkUtil"; name = "s"; break;
                default: throw new IllegalArgumentException("Unknown memory gate");
            }
            result = load(loader, owner).getDeclaredMethod(name);
            if (!staticBoolean(result)) throw new NoSuchMethodException("Native gate signature changed");
            result.setAccessible(true);
        } catch (ReflectiveOperationException | LinkageError failure) {
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                result = dexGate(dex, loader, role);
                Log.i(TAG, "Resolved " + role + " via DexKit: " + result);
            }
        }
        if (!forceDexKit) synchronized (CACHE) { CACHE.get(loader).put(role, result); }
        return result;
    }

    private static Method dexGate(ObfuscationResolver dex, ClassLoader loader, String role) throws Exception {
        switch (role) {
            case "privacy":
                return dex.uniqueMethod("memory privacy", MethodMatcher.create().returnType("boolean")
                        .paramCount(0).usingStrings("SuperBreenoAuthorizationHelper", "isMemoryPrivacyEnable: "),
                        method -> nativeOwner(method) && staticBoolean(method));
            case "service":
                // P() -> t(null,1,null) -> s(key). P has no own strings; match the read-only chain.
                MethodMatcher read = MethodMatcher.create().returnType("int").paramTypes("java.lang.String")
                        .usingStrings("AuthorizationUtils", "getMemoryServiceSwitchStatus ");
                MethodMatcher defaults = MethodMatcher.create().returnType("int")
                        .paramTypes("java.lang.String", "int", "java.lang.Object")
                        .usingStrings("breeno_memory_service_switch_enable").addInvoke(read);
                return dex.uniqueMethod("memory service", MethodMatcher.create().returnType("boolean")
                        .paramCount(0).addInvoke(defaults), method -> nativeOwner(method) && staticBoolean(method));
            case "network": {
                Class<?> network = load(loader, PKG + ".network.NetworkUtil");
                Field validated = null;
                // The native NetworkUtil has one static volatile boolean, updated from NetworkCapabilities.
                for (Field field : network.getDeclaredFields()) {
                    if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())
                            && Modifier.isVolatile(field.getModifiers())) {
                        if (validated != null) throw new NoSuchFieldException("Ambiguous network validation state");
                        validated = field;
                    }
                }
                if (validated == null) throw new NoSuchFieldException("Native network validation state changed");
                final String fieldName = validated.getName();
                return dex.uniqueMethod("memory validated network", MethodMatcher.create()
                        .declaredClass(network.getName()).returnType("boolean").paramCount(0), method -> {
                    if (!staticBoolean(method) || method.isSynthetic()) return false;
                    MethodData data = dex.bridge().getMethodData(method);
                    if (data == null || !data.getOpNames().equals(Arrays.asList("sget-boolean", "return"))) return false;
                    if (data.getUsingFields().size() != 1) return false;
                    UsingFieldData use = data.getUsingFields().get(0);
                    FieldData field = use.getField();
                    if (!use.getUsingType().isRead() || !field.getName().equals(fieldName)
                            || !field.getDeclaredClassName().equals(network.getName())) return false;
                    for (MethodData writer : field.getWriters()) {
                        if (!writer.getDeclaredClassName().equals(network.getName())
                                || !writer.getParamTypeNames().equals(Arrays.asList("android.net.NetworkCapabilities"))) continue;
                        for (MethodData invoke : writer.getInvokes()) {
                            if (invoke.getDeclaredClassName().equals("android.net.NetworkCapabilities")
                                    && invoke.getName().equals("hasCapability")) return true;
                        }
                    }
                    return false;
                });
            }
            default: throw new IllegalArgumentException("Unknown memory gate");
        }
    }

    public static Method storageGuard(ClassLoader loader, boolean forceDexKit) throws Exception {
        if (!forceDexKit) try {
            Method selected = null;
            for (Method method : load(loader, PKG + ".voicecollect.utils.w").getDeclaredMethods()) {
                if (storageShape(method)) {
                    if (selected != null) throw new NoSuchMethodException("Ambiguous native storage guard");
                    selected = method;
                }
            }
            if (selected == null) throw new NoSuchMethodException("Native storage guard changed");
            selected.setAccessible(true); return selected;
        } catch (ReflectiveOperationException | LinkageError failure) {
            // Discovery failure only; native guard invocation happens outside this fallback.
        }
        try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
            return dexStorage(dex);
        }
    }

    private static Method dexStorage(ObfuscationResolver dex) throws Exception {
        return dex.uniqueMethod("voice storage guard", MethodMatcher.create().returnType("boolean")
                .paramTypes("long").usingStrings("VoiceCollect-Utils", "isStorageLow: "),
                method -> nativeOwner(method) && storageShape(method));
    }

    public static Class<?> voiceCallback(ClassLoader loader, Method handle, boolean forceDexKit) throws Exception {
        Class<?> contract = handle.getParameterTypes()[5];
        if (!forceDexKit) try {
            Class<?> named = load(loader, PKG + ".voicecollect.collect.f");
            if (!callbackShape(named, contract)) throw new ClassNotFoundException("Native voice callback changed");
            return named;
        } catch (ReflectiveOperationException | LinkageError failure) {
            // Recover the same callback singleton that the native record-result coroutine reads.
        }
        try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
            return dexVoiceCallback(dex, loader, handle);
        }
    }

    private static Class<?> dexVoiceCallback(ObfuscationResolver dex, ClassLoader loader, Method handle) throws Exception {
        Class<?> contract = handle.getParameterTypes()[5];
        Set<String> sources = new LinkedHashSet<>();
        MethodMatcher caller = MethodMatcher.create().returnType("java.lang.Object")
                .paramTypes("java.lang.Object").addInvoke(MethodMatcher.create(handle));
        for (MethodData data : dex.bridge().findMethod(FindMethod.create().matcher(caller))) {
            if (!data.getDeclaredClassName().startsWith(PKG
                    + ".business.collection.voice.VoiceCollectionDetailFragment$startRecord$")) continue;
            for (UsingFieldData use : data.getUsingFields()) {
                FieldData field = use.getField();
                if (!use.getUsingType().isRead() || !Modifier.isStatic(field.getModifiers())
                        || !field.getDeclaredClassName().equals(field.getTypeName())) continue;
                Class<?> type = load(loader, field.getTypeName());
                if (callbackShape(type, contract)) sources.add(type.getName());
            }
        }
        if (sources.size() != 1) throw new ClassNotFoundException("Original recording callback sources=" + sources.size());
        String source = sources.iterator().next();
        return dex.uniqueClass("original recording callback", ClassMatcher.create().className(source),
                type -> sources.contains(type.getName()) && callbackShape(type, contract));
    }

    private static boolean callbackShape(Class<?> type, Class<?> contract) {
        if (!contract.isAssignableFrom(type) || type.isInterface() || Modifier.isAbstract(type.getModifiers())) return false;
        int singletonFields = 0;
        for (Field field : type.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) singletonFields++;
        return singletonFields == 1;
    }

    private static boolean nativeOwner(Method method) { return method.getDeclaringClass().getName().startsWith(PKG + "."); }
    private static boolean staticBoolean(Method method) {
        return Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class && method.getParameterCount() == 0;
    }
    private static boolean storageShape(Method method) {
        return Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class
                && Arrays.equals(method.getParameterTypes(), new Class<?>[]{long.class});
    }
    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /** Does not invoke resolved methods or read singleton fields. Safe even while an import is active. */
    public static Bundle probe(ClassLoader loader, boolean forceDexKit) {
        Bundle report = new Bundle();
        report.putBoolean("forceDexKit", forceDexKit);
        boolean ok = true;
        // Share one DEX index across all forced checks to stay inside the ordered-reply budget.
        try (ObfuscationResolver dex = forceDexKit ? ObfuscationResolver.open(loader) : null) {
            for (String role : Arrays.asList("privacy", "service", "network", "storage", "voiceCallback")) {
                try {
                    if (role.equals("storage")) report.putString(role,
                            (forceDexKit ? dexStorage(dex) : storageGuard(loader, false)).toGenericString());
                    else if (role.equals("voiceCallback")) {
                        Class<?> handler = load(loader, PKG + ".voicecollect.collect.VoiceRecordResultHandler");
                        Method handle = null;
                        for (Method method : handler.getDeclaredMethods()) {
                            Class<?>[] params = method.getParameterTypes();
                            if (params.length == 7 && method.getReturnType() == Object.class
                                    && params[0].getName().equals("android.content.Context") && params[1] == long.class
                                    && params[2].getName().startsWith(PKG + ".voicecollect.collect.VoiceRecorder$")
                                    && params[3].getName().equals(PKG + ".voicecollect.service.TriggerType")
                                    && params[4] == java.util.List.class && params[5].isInterface() && params[6].isInterface()) {
                                if (handle != null) throw new NoSuchMethodException("Ambiguous original voice handler");
                                handle = method;
                            }
                        }
                        if (handle == null) throw new NoSuchMethodException("Original voice handler unavailable");
                        report.putString(role, (forceDexKit ? dexVoiceCallback(dex, loader, handle)
                                : voiceCallback(loader, handle, false)).getName());
                    } else report.putString(role, (forceDexKit ? dexGate(dex, loader, role)
                            : gate(loader, role, false)).toGenericString());
                } catch (Throwable failure) {
                    ok = false;
                    report.putString(role + "Error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
            }
        } catch (Throwable failure) {
            ok = false;
            report.putString("dexkitError", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        report.putBoolean("ok", ok); report.putString("status", ok ? "probe_ok" : "probe_failed");
        return report;
    }
}
