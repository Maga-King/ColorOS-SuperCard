import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

/** Offline only: host shims win; original card's numeric resource IDs are untouched. */
public final class MergeDex {
    private static List<ZipEntry> dexEntries(ZipFile zip) {
        return Collections.list(zip.entries()).stream()
            .map(e -> (ZipEntry) e)
            .filter(e -> e.getName().matches("classes[0-9]*\\.dex"))
            .sorted(Comparator.comparingInt(e -> e.getName().equals("classes.dex") ? 1
                : Integer.parseInt(e.getName().substring(7, e.getName().length() - 4))))
            .toList();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("host.apk patched-card.zip output-directory");
        Path out = Path.of(args[2]); Files.createDirectories(out);
        Set<String> seen = new TreeSet<>();
        Map<String, ClassDef> hostDefinitions = new HashMap<>();
        List<String> conflicts = new ArrayList<>(), summary = new ArrayList<>();
        List<String> kotlinApiDifferences = new ArrayList<>();
        int number = 1;
        for (int source = 0; source < 2; source++) {
            try (ZipFile zip = new ZipFile(args[source])) {
                for (ZipEntry entry : dexEntries(zip)) {
                    DexBackedDexFile dex;
                    try (var input = new BufferedInputStream(zip.getInputStream(entry))) {
                        dex = DexBackedDexFile.fromInputStream(Opcodes.forDexVersion(39), input);
                    }
                    DexPool pool = new DexPool(Opcodes.forDexVersion(39));
                    int kept = 0, dropped = 0;
                    for (ClassDef definition : dex.getClasses()) {
                        String type = definition.getType();
                        // Host code uses getIdentifier for its four drawables. Its
                        // old 0x7f R constants must not alias original vivo resources.
                        if (source == 0 && (type.equals("Ldev/local/supercardhost/R;")
                                || type.startsWith("Ldev/local/supercardhost/R$"))) {
                            conflicts.add("unused_host_R_removed\t" + type); dropped++; continue;
                        }
                        if (type.startsWith("Lio/github/libxposed/api/"))
                            throw new IllegalStateException("Framework API was bundled: " + type);
                        if (!seen.add(type)) {
                            if (source == 0) throw new IllegalStateException("Duplicate host class: " + type);
                            conflicts.add("host_definition_wins\t" + type);
                            if (type.startsWith("Lkotlin/")) {
                                ClassDef replacement = hostDefinitions.get(type);
                                Set<String> available = new HashSet<>();
                                replacement.getMethods().forEach(m -> available.add(m.getName()
                                    + m.getParameterTypes() + m.getReturnType()));
                                definition.getMethods().forEach(m -> {
                                    String signature = m.getName() + m.getParameterTypes() + m.getReturnType();
                                    if ((m.getAccessFlags() & 3) == 1 && !available.contains(signature))
                                        kotlinApiDifferences.add(type + "\t" + signature);
                                });
                            }
                            dropped++; continue;
                        }
                        if (source == 0) hostDefinitions.put(type, definition);
                        pool.internClass(definition); kept++;
                    }
                    if (kept > 0) {
                        String name = number == 1 ? "classes.dex" : "classes" + number + ".dex";
                        MemoryDataStore store = new MemoryDataStore(); pool.writeTo(store);
                        Files.write(out.resolve(name), store.getData()); number++;
                        summary.add((source == 0 ? "host" : "patched_card") + "\t" + entry.getName()
                            + "\t" + name + "\tkept=" + kept + "\tdropped=" + dropped);
                    }
                }
            }
        }
        Files.write(out.resolve("dex-conflicts.tsv"), conflicts);
        Files.write(out.resolve("dex-summary.tsv"), summary);
        Files.write(out.resolve("kotlin-public-api-differences.tsv"), kotlinApiDifferences);
        Files.writeString(out.resolve("dex-definition-count.txt"), "Unique definitions: " + seen.size() + "\n");
        summary.forEach(System.out::println);
    }
}
