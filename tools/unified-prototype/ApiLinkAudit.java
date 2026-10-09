import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.*;

/** Reports only links that existed in the caller's original dependency graph. */
public final class ApiLinkAudit {
    static Map<String, ClassDef> read(String path) throws Exception {
        Map<String, ClassDef> all = new HashMap<>();
        try (ZipFile zip = new ZipFile(path)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (var in = new BufferedInputStream(zip.getInputStream(entry))) {
                    for (ClassDef c : DexBackedDexFile.fromInputStream(Opcodes.forDexVersion(39), in).getClasses())
                        all.put(c.getType(), c);
                }
            }
        }
        return all;
    }
    static String signature(MethodReference method) {
        return method.getName() + method.getParameterTypes() + method.getReturnType();
    }
    static boolean exists(Map<String, ClassDef> classes, String type, MethodReference method, Set<String> seen) {
        if (type == null || !seen.add(type)) return false;
        ClassDef c = classes.get(type); if (c == null) return false;
        String signature = signature(method);
        for (Method m : c.getMethods()) if (signature(m).equals(signature)) return true;
        if (method.getName().equals("<init>") || method.getName().equals("<clinit>")) return false;
        if (exists(classes, c.getSuperclass(), method, seen)) return true;
        for (String i : c.getInterfaces()) if (exists(classes, i, method, seen)) return true;
        return false;
    }
    static boolean existsField(Map<String, ClassDef> classes, String type, FieldReference field, Set<String> seen) {
        if (type == null || !seen.add(type)) return false;
        ClassDef c = classes.get(type); if (c == null) return false;
        for (Field f : c.getFields())
            if (f.getName().equals(field.getName()) && f.getType().equals(field.getType())) return true;
        if (existsField(classes, c.getSuperclass(), field, seen)) return true;
        for (String i : c.getInterfaces()) if (existsField(classes, i, field, seen)) return true;
        return false;
    }
    static List<String> audit(Map<String, ClassDef> host, Map<String, ClassDef> original, boolean originalKotlin) {
        Map<String, ClassDef> merged = new HashMap<>(original);
        host.forEach((type, c) -> {
            if (!originalKotlin || !type.startsWith("Lkotlin/") || !original.containsKey(type)) merged.put(type, c);
        });
        List<String> failures = new ArrayList<>();
        for (int source = 0; source < 2; source++) {
            Map<String, ClassDef> own = source == 0 ? host : original;
            Map<String, ClassDef> before = new HashMap<>(source == 0 ? original : host); before.putAll(own);
            for (ClassDef caller : own.values()) {
                if (merged.get(caller.getType()) != caller) continue;
                for (Method method : caller.getMethods()) {
                    var implementation = method.getImplementation(); if (implementation == null) continue;
                    for (var instruction : implementation.getInstructions()) {
                        if (!(instruction instanceof ReferenceInstruction r)) continue;
                        if (r.getReference() instanceof MethodReference m && m.getDefiningClass().startsWith("Lkotlin/")
                                && exists(before, m.getDefiningClass(), m, new HashSet<>())
                                && !exists(merged, m.getDefiningClass(), m, new HashSet<>()))
                            failures.add((source == 0 ? "host" : "original") + "\t" + caller.getType()
                                + "->" + signature(method) + "\t" + m.getDefiningClass() + "->" + signature(m));
                        if (r.getReference() instanceof FieldReference f && f.getDefiningClass().startsWith("Lkotlin/")
                                && existsField(before, f.getDefiningClass(), f, new HashSet<>())
                                && !existsField(merged, f.getDefiningClass(), f, new HashSet<>()))
                            failures.add((source == 0 ? "host" : "original") + "\t" + caller.getType()
                                + "->" + signature(method) + "\t" + f.getDefiningClass() + "->" + f.getName() + ":" + f.getType());
                    }
                }
            }
        }
        return new TreeSet<>(failures).stream().toList();
    }
    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> host = read(args[0]), original = read(args[1]);
        for (boolean originalKotlin : new boolean[]{false, true}) {
            List<String> failures = audit(host, original, originalKotlin);
            String policy = originalKotlin ? "original-kotlin-wins" : "host-kotlin-wins";
            Files.write(Path.of(args[2]).resolve(policy + "-broken-links.tsv"), failures);
            System.out.println(policy + " broken retained method links=" + failures.size());
            if (!originalKotlin && !failures.isEmpty()) throw new IllegalStateException("Kotlin link audit failed");
        }
    }
}
