import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.instruction.formats.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.rewriter.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Adapts OEM UI/keyguard/display boundaries, preserving resources and payment logic. */
public final class PatchPlugin {
    private static final String CARD_UTIL = "Lcom/vivo/card/utils/CardUtil;";
    private static final Map<String, Integer> PAY_DENSITY_CALLS = Map.of(
        "Lcom/vivo/card/cards/cardpay/adapter/PayCardAdapter;", 38,
        "Lcom/vivo/card/cards/cardpay/PayCardView;", 18,
        "Lcom/vivo/card/cards/cardpay/PayCardAnimator;", 2,
        "Lcom/vivo/card/cards/cardpay/view/DigHoleView;", 6,
        "Lcom/vivo/card/cards/cardpay/view/HoleDrawable;", 2);

    private record DensityCalls(Map<String, Integer> physical, Map<String, Integer> context) {}

    private static boolean isDensityCall(MethodReference method, String name) {
        return method.getDefiningClass().equals(CARD_UTIL) && method.getName().equals(name)
            && method.getReturnType().equals("I")
            && method.getParameterTypes().toString().equals("[Landroid/content/Context;, F]");
    }

    private static DensityCalls readDensityCalls(String archive) throws IOException {
        Map<String, Integer> physical = new TreeMap<>(), context = new TreeMap<>();
        try (var source = new ZipFile(archive)) {
            for (var entry : Collections.list(source.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                var dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(36),
                    new BufferedInputStream(source.getInputStream(entry)));
                for (var definition : dex.getClasses()) {
                    for (var method : definition.getMethods()) {
                        var implementation = method.getImplementation();
                        if (implementation == null) continue;
                        for (var instruction : implementation.getInstructions()) {
                            if (!(instruction instanceof ReferenceInstruction ref)
                                || !(ref.getReference() instanceof MethodReference target)) continue;
                            if (isDensityCall(target, "dip2px"))
                                physical.merge(definition.getType(), 1, Integer::sum);
                            if (isDensityCall(target, "dip2pxDefault"))
                                context.merge(definition.getType(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return new DensityCalls(physical, context);
    }

    private static List<String> verifyPayDensity(DensityCalls before, DensityCalls after) {
        List<String> report = new ArrayList<>();
        report.add("Pay-only density: CardUtil.dip2px(Context,float) -> dip2pxDefault(Context,float)");
        int total = 0;
        for (var entry : new TreeMap<>(PAY_DENSITY_CALLS).entrySet()) {
            String type = entry.getKey();
            int count = entry.getValue();
            if (before.physical().getOrDefault(type, 0) != count
                || after.physical().getOrDefault(type, 0) != 0
                || after.context().getOrDefault(type, 0) != before.context().getOrDefault(type, 0) + count)
                throw new IllegalStateException("Pay density verification failed: " + type);
            total += count;
            report.add(type + " replaced=" + count + " remaining=0");
        }
        for (boolean physical : new boolean[]{true, false}) {
            Map<String, Integer> source = new TreeMap<>(physical ? before.physical() : before.context());
            Map<String, Integer> output = new TreeMap<>(physical ? after.physical() : after.context());
            PAY_DENSITY_CALLS.keySet().forEach(source::remove);
            PAY_DENSITY_CALLS.keySet().forEach(output::remove);
            if (!source.equals(output))
                throw new IllegalStateException("Density calls changed outside the five Pay classes");
        }
        report.add("Total replaced=" + total + "; density calls outside whitelist unchanged.");
        return report;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4 || (args.length == 4 && !args[3].equals("--pay-density")))
            throw new IllegalArgumentException("Usage: PatchPlugin input.apk output.zip references.txt [--pay-density]");
        boolean fixPayDensity = args.length == 4;
        DensityCalls densityBefore = fixPayDensity ? readDensityCalls(args[0]) : null;
        if (fixPayDensity) {
            for (var entry : PAY_DENSITY_CALLS.entrySet()) {
                if (densityBefore.physical().getOrDefault(entry.getKey(), 0) != entry.getValue())
                    throw new IllegalStateException("Unexpected Pay input call count: " + entry.getKey());
            }
        }
        var payDensityMethod = new ImmutableMethodReference(CARD_UTIL, "dip2pxDefault",
            List.of("Landroid/content/Context;", "F"), "I");
        var payDensityRewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference method
                        && isDensityCall(method, "dip2px")) {
                        if (instruction instanceof Instruction35c i && i.getOpcode() == Opcode.INVOKE_STATIC
                            && i.getRegisterCount() == 2)
                            return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2,
                                i.getRegisterC(), i.getRegisterD(), 0, 0, 0, payDensityMethod);
                        if (instruction instanceof Instruction3rc i && i.getOpcode() == Opcode.INVOKE_STATIC_RANGE
                            && i.getRegisterCount() == 2)
                            return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, i.getStartRegister(), 2, payDensityMethod);
                        throw new IllegalStateException("Unexpected Pay density instruction: " + instruction.getOpcode());
                    }
                    return instruction;
                };
            }
        });
        var bridge = new ImmutableMethodReference("Ldev/local/supercardhost/UiCompat;", "setNightMode",
            List.of("Ljava/lang/Object;", "I"), "V");
        var bindBridge = new ImmutableMethodReference("Ldev/local/supercardhost/UiCompat;", "bindServiceAsUser",
            List.of("Landroid/content/Context;", "Landroid/content/Intent;", "Landroid/content/ServiceConnection;", "I", "Landroid/os/UserHandle;"), "Z");
        var displayBridge = new ImmutableMethodReference("Ldev/local/supercardhost/UiCompat;", "createVirtualDisplay",
            List.of("Landroid/hardware/display/DisplayManager;", "Ljava/lang/String;", "I", "I", "I", "Landroid/view/Surface;", "I"),
            "Landroid/hardware/display/VirtualDisplay;");
        var releaseBridge = new ImmutableMethodReference("Ldev/local/supercardhost/UiCompat;", "releaseVirtualDisplay",
            List.of("Landroid/hardware/display/VirtualDisplay;"), "V");
        Set<String> patched = new TreeSet<>();
        var rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<ClassDef> getClassDefRewriter(Rewriters rewriters) {
                var original = new ClassDefRewriter(rewriters);
                return definition -> {
                    ClassDef result = original.rewrite(definition);
                    // Only these callers change; shared CardUtil and all other card types stay intact.
                    return fixPayDensity && PAY_DENSITY_CALLS.containsKey(definition.getType())
                        ? payDensityRewriter.getClassDefRewriter().rewrite(result) : result;
                };
            }
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference m
                        && m.getDefiningClass().equals("Landroid/hardware/display/VirtualDisplay;")
                        && m.getName().equals("release") && m.getParameterTypes().isEmpty()) {
                        if (instruction instanceof Instruction35c i && i.getRegisterCount() == 1) {
                            patched.add(m.toString());
                            return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1,
                                i.getRegisterC(), 0, 0, 0, 0, releaseBridge);
                        }
                        if (instruction instanceof Instruction3rc i && i.getRegisterCount() == 1) {
                            patched.add(m.toString());
                            return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, i.getStartRegister(), 1, releaseBridge);
                        }
                    }
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference m
                        && m.getDefiningClass().equals("Landroid/hardware/display/DisplayManager;")
                        && m.getName().equals("createVirtualDisplay")
                        && m.getParameterTypes().toString().equals("[Ljava/lang/String;, I, I, I, Landroid/view/Surface;, I]")
                        && instruction instanceof Instruction3rc i && i.getRegisterCount() == 7) {
                        patched.add(m.toString());
                        return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, i.getStartRegister(), 7, displayBridge);
                    }
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference m
                        && m.getName().equals("bindServiceAsUser") && m.getReturnType().equals("Z")
                        && m.getParameterTypes().toString().equals("[Landroid/content/Intent;, Landroid/content/ServiceConnection;, I, Landroid/os/UserHandle;]")) {
                        if (instruction instanceof Instruction35c i && i.getRegisterCount() == 5) {
                            patched.add(m.toString());
                            return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 5,
                                i.getRegisterC(), i.getRegisterD(), i.getRegisterE(), i.getRegisterF(), i.getRegisterG(), bindBridge);
                        }
                        if (instruction instanceof Instruction3rc i && i.getRegisterCount() == 5) {
                            patched.add(m.toString());
                            return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, i.getStartRegister(), 5, bindBridge);
                        }
                    }
                    if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference m
                        && m.getName().equals("setNightMode") && m.getReturnType().equals("V")
                        && m.getParameterTypes().size() == 1 && m.getParameterTypes().get(0).toString().equals("I")) {
                        if (instruction instanceof Instruction35c i && i.getRegisterCount() == 2) {
                            patched.add(m.toString());
                            return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2,
                                i.getRegisterC(), i.getRegisterD(), i.getRegisterE(), i.getRegisterF(), i.getRegisterG(), bridge);
                        }
                        if (instruction instanceof Instruction3rc i && i.getRegisterCount() == 2) {
                            patched.add(m.toString());
                            return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, i.getStartRegister(), 2, bridge);
                        }
                    }
                    return instruction;
                };
            }
        });
        Path outputPath = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(outputPath.getParent());
        Path temporaryOutput = Files.createTempFile(outputPath.getParent(), "supercard-patch-", ".zip");
        try (var source = new ZipFile(args[0]); var output = new ZipOutputStream(Files.newOutputStream(temporaryOutput))) {
            for (var entry : Collections.list(source.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                var dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(36), new BufferedInputStream(source.getInputStream(entry)));
                var store = new MemoryDataStore();
                DexPool.writeTo(store, rewriter.getDexFileRewriter().rewrite(dex));
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(store.getData()); output.closeEntry();
                System.out.println("Rewritten " + entry.getName());
            }
        }
        if (patched.isEmpty()) throw new IllegalStateException("Expected OEM call sites not found");
        if (fixPayDensity) {
            List<String> report = verifyPayDensity(densityBefore, readDensityCalls(temporaryOutput.toString()));
            Files.write(Path.of(args[2]).toAbsolutePath().resolveSibling("pay-density-report.txt"), report);
            report.forEach(System.out::println);
        }
        Files.move(temporaryOutput, outputPath, StandardCopyOption.REPLACE_EXISTING);
        Files.write(Path.of(args[2]), patched);
        System.out.println("Patched method references: " + patched.size());
    }
}
