package dev.local.supercardhost;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

/** Short-lived, read-only DexKit fallback. Business anchors belong to each adapter. */
public final class ObfuscationResolver implements AutoCloseable {
    @FunctionalInterface public interface MethodCheck { boolean test(Method method) throws Exception; }
    @FunctionalInterface public interface ClassCheck { boolean test(Class<?> type) throws Exception; }
    private static boolean nativeLoaded;
    private final DexKitBridge dex;
    private final ClassLoader target;

    private ObfuscationResolver(DexKitBridge dex, ClassLoader target) throws Exception {
        this.dex = dex;
        this.target = target;
        if (!dex.isValid() || dex.getDexNum() == 0) {
            dex.close();
            throw new IllegalStateException("DexKit target contains no readable DEX");
        }
    }

    /** Call only after the adapter's named/structural fast path fails. Includes loaded splits. */
    public static ObfuscationResolver open(ClassLoader target) throws Exception {
        if (target == null) throw new IllegalArgumentException("Target loader required");
        loadNative();
        return new ObfuscationResolver(DexKitBridge.create(target, false), target);
    }

    /** Offline metadata probe; never invokes any target business method. */
    public static ObfuscationResolver openApk(String apk, ClassLoader target) throws Exception {
        if (apk == null || apk.isEmpty()) throw new IllegalArgumentException("Target APK required");
        loadNative();
        return new ObfuscationResolver(DexKitBridge.create(apk), target);
    }

    private static synchronized void loadNative() throws Exception {
        if (nativeLoaded) return;
        try { System.loadLibrary("dexkit"); }
        catch (LinkageError failure) { throw new Exception("DexKit native library unavailable", failure); }
        nativeLoaded = true;
    }

    public DexKitBridge bridge() { return dex; }

    /** No first-match fallback: every candidate is checked and ambiguity is a hard failure. */
    public Method uniqueMethod(String purpose, MethodMatcher matcher, MethodCheck check) throws Exception {
        if (target == null) throw new IllegalStateException("Reflection target loader required");
        Map<String, Method> matches = new LinkedHashMap<>();
        for (MethodData data : dex.findMethod(FindMethod.create().matcher(matcher))) {
            Method method;
            try { method = data.getMethodInstance(target); }
            catch (NoSuchMethodException | LinkageError unavailable) { continue; }
            if (!check.test(method)) continue;
            method.setAccessible(true);
            matches.put(data.getDescriptor(), method);
        }
        if (matches.size() != 1) throw new NoSuchMethodException(
                purpose + " DexKit candidates=" + matches.size());
        return matches.values().iterator().next();
    }

    public Class<?> uniqueClass(String purpose, ClassMatcher matcher, ClassCheck check) throws Exception {
        if (target == null) throw new IllegalStateException("Reflection target loader required");
        Map<String, Class<?>> matches = new LinkedHashMap<>();
        for (ClassData data : dex.findClass(FindClass.create().matcher(matcher))) {
            Class<?> type;
            try { type = data.getInstance(target); }
            catch (ClassNotFoundException | LinkageError unavailable) { continue; }
            if (check.test(type)) matches.put(type.getName(), type);
        }
        if (matches.size() != 1) throw new ClassNotFoundException(
                purpose + " DexKit candidates=" + matches.size());
        return matches.values().iterator().next();
    }

    @Override public void close() { dex.close(); }
}
