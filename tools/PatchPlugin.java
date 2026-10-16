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
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.rewriter.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Adapts OEM UI/keyguard/display boundaries, preserving resources and payment logic. */
public final class PatchPlugin {
    private static final String SELF_BRIDGE = "Ldev/local/supercardhost/SelfSettingsCompat;";
    private static final Map<String, Set<String>> SELF_METHODS = Map.ofEntries(
        Map.entry("Lcom/vivo/card/ui/view/CameraCardViewForEdit;", Set.of("setInitData", "setRefreshData")),
        Map.entry("Lcom/vivo/card/preference/BasePreferenceActivity;", Set.of("onCreate")),
        Map.entry("Lcom/vivo/card/setting/CardSettingsPreferenceFragment;", Set.of("initPreference", "updateAllPreferenceVisibility")),
        Map.entry("Lcom/vivo/card/setting/CardEditAdapter;", Set.of("onBindViewHolder")),
        Map.entry("Lcom/vivo/card/adapter/CardManagerAdapter$CardManagerHorizontalAdapter$CardListViewHolder;", Set.of("bind4UsingHolderItem")),
        Map.entry("Lcom/vivo/card/adapter/CardManagerAdapter;", Set.of("bindImageCardView", "setupCardViewScale", "handleStagingCardClick")),
        Map.entry("Lcom/vivo/card/utils/ExtensionsKt;", Set.of("setSpanSummaryExText")),
        Map.entry("Lcom/vivo/card/utils/SettingsUtils$Secure;", Set.of("getString")),
        Map.entry("Lcom/vivo/card/cards/commondata/local/CardSettingsManager;", Set.of("getCardSetBean")),
        Map.entry("Lcom/vivo/card/data/CardDataInitializer;", Set.of("handleSwitchStateOnFirstBoot", "getOrLoadDefaultJson", "getCameraFunType", "queryCameraFunctions", "queryCameraFuncNameChanged", "processCameraFunctionFormat", "isStagingCardPluginAvailable", "init")),
        Map.entry("Lcom/vivo/card/cards/cardcamera/utils/CameraCardUtils;", Set.of("getResIdByName", "getStringByResName")),
        Map.entry("Lcom/vivo/card/utils/CardUtil;", Set.of("getStringByResName", "createViewToBitmap")));

    private static boolean selfMethod(Method method) {
        if (!SELF_METHODS.getOrDefault(method.getDefiningClass(), Set.of()).contains(method.getName())) return false;
        if (method.getName().equals("getString")) return method.getParameterTypes().size() == 4;
        if (method.getName().equals("onBindViewHolder")) return method.getParameterTypes().toString()
                .equals("[Landroidx/recyclerview/widget/RecyclerView$ViewHolder;, I]");
        return true;
    }

    private static ClassDef patchSelf(ClassDef definition, Set<String> observed) {
        if (!SELF_METHODS.containsKey(definition.getType())) return definition;
        List<Method> methods = new ArrayList<>();
        for (Method method : definition.getMethods()) {
            if (!selfMethod(method)) { methods.add(method); continue; }
            String key = definition.getType() + "->" + method.getName();
            if (!observed.add(key)) throw new IllegalStateException("Ambiguous self adapter method: " + key);
            if (method.getImplementation() == null) throw new IllegalStateException("Missing self adapter body: " + key);
            methods.add(new ImmutableMethod(method.getDefiningClass(), "supercardOriginal$" + method.getName(),
                    method.getParameters(), method.getReturnType(), method.getAccessFlags(), Set.of(),
                    method.getHiddenApiRestrictions(), method.getImplementation()));
            methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                    method.getHiddenApiRestrictions(), selfWrapper(method)));
        }
        return new ImmutableClassDef(definition.getType(), definition.getAccessFlags(), definition.getSuperclass(),
                definition.getInterfaces(), definition.getSourceFile(), definition.getAnnotations(), definition.getFields(), methods);
    }

    private static ImmutableMethodImplementation selfWrapper(Method method) {
        boolean isStatic = AccessFlags.STATIC.isSet(method.getAccessFlags());
        int count = method.getParameterTypes().size();
        List<Instruction> code = new ArrayList<>();
        code.add(new ImmutableInstruction21c(Opcode.CONST_CLASS, 0, new ImmutableTypeReference(method.getDefiningClass())));
        code.add(isStatic ? new ImmutableInstruction11n(Opcode.CONST_4, 1, 0)
                : new ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 6));
        code.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 2, new ImmutableStringReference(method.getName())));
        code.add(new ImmutableInstruction11n(Opcode.CONST_4, 4, count));
        code.add(new ImmutableInstruction22c(Opcode.NEW_ARRAY, 3, 4, new ImmutableTypeReference("[Ljava/lang/Object;")));
        int register = isStatic ? 6 : 7;
        for (int i = 0; i < count; i++, register++) {
            String type = method.getParameterTypes().get(i).toString();
            if (type.startsWith("L") || type.startsWith("["))
                code.add(new ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 5, register));
            else if (type.equals("I") || type.equals("Z")) {
                String box = type.equals("I") ? "Ljava/lang/Integer;" : "Ljava/lang/Boolean;";
                code.add(new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1,
                        new ImmutableMethodReference(box, "valueOf", List.of(type), box)));
                code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5));
            } else throw new IllegalStateException("Unsupported self adapter argument: " + method);
            code.add(new ImmutableInstruction11n(Opcode.CONST_4, 4, i));
            code.add(new ImmutableInstruction23x(Opcode.APUT_OBJECT, 5, 3, 4));
        }
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 1, 2, 3, 0,
                new ImmutableMethodReference(SELF_BRIDGE, "invoke", List.of("Ljava/lang/Class;", "Ljava/lang/Object;",
                "Ljava/lang/String;", "[Ljava/lang/Object;"), "Ljava/lang/Object;")));
        String result = method.getReturnType();
        if (result.equals("V")) code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        else {
            code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            if (result.equals("I") || result.equals("Z")) {
                String box = result.equals("I") ? "Ljava/lang/Integer;" : "Ljava/lang/Boolean;";
                code.add(new ImmutableInstruction21c(Opcode.CHECK_CAST, 0, new ImmutableTypeReference(box)));
                code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                        new ImmutableMethodReference(box, result.equals("I") ? "intValue" : "booleanValue", List.of(), result)));
                code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0));
                code.add(new ImmutableInstruction11x(Opcode.RETURN, 0));
            } else if (result.startsWith("L") || result.startsWith("[")) {
                code.add(new ImmutableInstruction21c(Opcode.CHECK_CAST, 0, new ImmutableTypeReference(result)));
                code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
            } else throw new IllegalStateException("Unsupported self adapter result: " + method);
        }
        return new ImmutableMethodImplementation(register, code, List.of(), List.of());
    }
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
        Set<String> selfPatched = new TreeSet<>();
        var rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<ClassDef> getClassDefRewriter(Rewriters rewriters) {
                var original = new ClassDefRewriter(rewriters);
                return definition -> {
                    ClassDef result = original.rewrite(definition);
                    // Only these callers change; shared CardUtil and all other card types stay intact.
                    if (fixPayDensity && PAY_DENSITY_CALLS.containsKey(definition.getType()))
                        result = payDensityRewriter.getClassDefRewriter().rewrite(result);
                    return fixPayDensity ? patchSelf(result, selfPatched) : result;
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
            Set<String> expected = new TreeSet<>();
            SELF_METHODS.forEach((owner, names) -> names.forEach(name -> expected.add(owner + "->" + name)));
            if (!expected.equals(selfPatched)) {
                expected.removeAll(selfPatched);
                throw new IllegalStateException("Missing self adapter methods: " + expected);
            }
            Files.write(Path.of(args[2]).toAbsolutePath().resolveSibling("self-settings-report.txt"), selfPatched);
            List<String> report = verifyPayDensity(densityBefore, readDensityCalls(temporaryOutput.toString()));
            Files.write(Path.of(args[2]).toAbsolutePath().resolveSibling("pay-density-report.txt"), report);
            report.forEach(System.out::println);
        }
        Files.move(temporaryOutput, outputPath, StandardCopyOption.REPLACE_EXISTING);
        Files.write(Path.of(args[2]), patched);
        System.out.println("Patched method references: " + patched.size());
    }
}
