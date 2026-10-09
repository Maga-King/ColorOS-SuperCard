package dev.local.supercardhost;

import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Root app_process diagnostics only. Not an Android component; never starts target business code. */
public final class RuntimeDexProbe {
    private RuntimeDexProbe() { }

    public static void main(String[] args) {
        long started = SystemClock.elapsedRealtime();
        int exit = 1;
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root UID required");
            if (args.length < 2 || !Arrays.asList("wallet", "memory", "sidebar").contains(args[0]))
                throw new IllegalArgumentException("Usage: RuntimeDexProbe wallet|memory|sidebar target.apk [split.apk ...]");
            List<String> paths = new ArrayList<>();
            for (int index = 1; index < args.length; index++) {
                for (String part : args[index].split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (part.isEmpty()) throw new IllegalArgumentException("Empty APK path");
                    File apk = new File(part).getCanonicalFile();
                    if (!apk.isFile() || !apk.canRead()) throw new IllegalArgumentException("Target APK is not readable");
                    paths.add(apk.getAbsolutePath());
                }
            }
            if (paths.isEmpty()) throw new IllegalArgumentException("Target APK required");
            // Some DexKit distributions depend on the shared C++ runtime, others link it statically.
            try { System.loadLibrary("c++_shared"); }
            catch (UnsatisfiedLinkError optional) {
                System.out.println("jni.cpp=not_preloaded");
            }
            System.loadLibrary("dexkit");
            ClassLoader module = RuntimeDexProbe.class.getClassLoader();
            ClassLoader target = new PathClassLoader(String.join(File.pathSeparator, paths), module);
            System.out.println("kind=" + args[0] + " apkCount=" + paths.size() + " forceDexKit=true");
            Object report = invokeProbe(args[0], module, target);
            Map<String, Object> values = new TreeMap<>();
            if (report instanceof Bundle) {
                Bundle bundle = (Bundle) report;
                for (String key : bundle.keySet()) values.put(key, bundle.get(key));
            } else if (report instanceof Map) {
                for (Map.Entry<?, ?> item : ((Map<?, ?>) report).entrySet())
                    values.put(String.valueOf(item.getKey()), item.getValue());
            } else throw new IllegalStateException("Unsupported probe report type");
            int resolvedMethods = 0;
            for (Map.Entry<String, Object> item : values.entrySet()) {
                String text = String.valueOf(item.getValue());
                String descriptor = descriptor(target, text);
                if (descriptor != null) {
                    // Probe resolvers return a symbol only after their strict unique-candidate check.
                    System.out.println(item.getKey() + " candidates=1 descriptor=" + descriptor);
                    resolvedMethods++;
                } else System.out.println(item.getKey() + "=" + singleLine(text));
            }
            boolean success = !Boolean.FALSE.equals(values.get("ok"));
            Object status = values.get("status");
            if (status != null) success &= "ok".equals(status) || "probe_ok".equals(status);
            for (String key : values.keySet())
                if (key.endsWith("Error") || key.equals("error") && values.get(key) != null
                        && !String.valueOf(values.get(key)).isEmpty()) success = false;
            System.out.println("resolvedMethodCount=" + resolvedMethods);
            System.out.println("result=" + (success ? "ok" : "unavailable"));
            exit = success ? 0 : 1;
        } catch (Throwable failure) {
            while (failure instanceof InvocationTargetException && failure.getCause() != null)
                failure = failure.getCause();
            System.out.println("result=error type=" + failure.getClass().getName()
                    + " error=" + singleLine(String.valueOf(failure.getMessage())));
            exit = failure instanceof IllegalArgumentException || failure instanceof SecurityException ? 2 : 1;
        } finally {
            System.out.println("elapsedMs=" + (SystemClock.elapsedRealtime() - started));
        }
        System.exit(exit);
    }

    private static Object invokeProbe(String kind, ClassLoader module, ClassLoader target) throws Exception {
        String owner = kind.equals("wallet") ? "WalletDexResolver"
                : kind.equals("memory") ? "MemoryDexResolver" : "SidebarDexResolver";
        Class<?> resolver = Class.forName("dev.local.supercardhost." + owner, true, module);
        Method probe = kind.equals("wallet") ? resolver.getDeclaredMethod("probe", ClassLoader.class)
                : resolver.getDeclaredMethod("probe", ClassLoader.class, boolean.class);
        if (!Modifier.isStatic(probe.getModifiers())) throw new NoSuchMethodException("Static probe required");
        probe.setAccessible(true);
        return kind.equals("wallet") ? probe.invoke(null, target) : probe.invoke(null, target, true);
    }

    /** Converts the resolver's reflected method signature to a DEX descriptor without invocation. */
    private static String descriptor(ClassLoader target, String signature) {
        int open = signature.indexOf('(');
        if (open < 0) return null;
        String head = signature.substring(0, open);
        int start = head.lastIndexOf(' ') + 1;
        int dot = head.lastIndexOf('.');
        if (dot <= start) return null;
        try {
            Class<?> owner = Class.forName(head.substring(start, dot), false, target);
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.toGenericString().equals(signature)) continue;
                StringBuilder dex = new StringBuilder(type(owner)).append("->")
                        .append(method.getName()).append('(');
                for (Class<?> parameter : method.getParameterTypes()) dex.append(type(parameter));
                return dex.append(')').append(type(method.getReturnType())).toString();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) { }
        return null;
    }

    private static String type(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unknown primitive type");
    }

    private static String singleLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
