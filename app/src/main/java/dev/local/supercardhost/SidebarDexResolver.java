package dev.local.supercardhost;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/** Resolves the native sidebar boundary without calling any native settings method. */
public final class SidebarDexResolver {
    private static final String SETTINGS = "com.oplus.smartsidebar.settings.";
    private static final String WIDGETS = SETTINGS + "widgets.";
    private static final String PREF = "androidx.preference.Preference";
    private static final String GROUP = "androidx.preference.PreferenceGroup";
    private static final String PROXY = "com.coloros.edgepanel.utils.EdgePanelSettingsValueProxy";
    private static final String BAR = "com.coui.appcompat.seekbar.COUISeekBar";

    private SidebarDexResolver() { }

    /** Logical aliases never select the first overload. They refer to prevalidated Methods. */
    static final class Plan {
        final ClassLoader loader;
        final Map<String, Method> methods = new LinkedHashMap<>();
        final Map<String, Field> fields = new LinkedHashMap<>();
        Class<?> mainFragment, changeListener, clickListener;
        Plan(ClassLoader loader) { this.loader = loader; }
        Method method(Class<?> type, String alias) throws NoSuchMethodException {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                Method m = methods.get(c.getName() + "#" + alias);
                if (m != null) return m;
            }
            throw new NoSuchMethodException(type.getName() + "#" + alias);
        }
        Method method(String type, String alias) throws Exception { return method(loader.loadClass(type), alias); }
        Method optional(Class<?> type, String alias) {
            try { return method(type, alias); } catch (NoSuchMethodException ignored) { return null; }
        }
        void put(Class<?> owner, String alias, Method value) {
            value.setAccessible(true); methods.put(owner.getName() + "#" + alias, value);
        }
        void field(Class<?> owner, String alias, Field value) {
            value.setAccessible(true); fields.put(owner.getName() + "#" + alias, value);
        }
        Field field(Class<?> type, String alias) throws NoSuchFieldException {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                Field f = fields.get(c.getName() + "#" + alias); if (f != null) return f;
            }
            throw new NoSuchFieldException(type.getName() + "#" + alias);
        }
    }

    /** The runtime probe only reflects metadata. It does not install hooks or invoke business code. */
    public static Bundle probe(ClassLoader loader, boolean forceDex) {
        Bundle result = new Bundle();
        try (Session s = new Session(loader, forceDex)) {
            Plan p = s.ui();
            s.nativePage(p);
            result.putString("status", "ok");
            result.putBoolean("nativeReady", true);
            result.putString("mainFragment", p.mainFragment.getName());
            result.putInt("resolvedCount", p.methods.size() + p.fields.size());
        } catch (Throwable failure) {
            result.putString("status", "unavailable");
            result.putBoolean("nativeReady", false);
            result.putString("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return result;
    }

    static Plan resolveUi(ClassLoader loader) throws Exception {
        try (Session s = new Session(loader, false)) { return s.ui(); }
    }
    static Plan resolveNative(ClassLoader loader) throws Exception {
        try (Session s = new Session(loader, false)) {
            Plan p = s.ui(); s.nativePage(p); return p;
        }
    }

    private static final class Session implements AutoCloseable {
        final ClassLoader loader;
        final boolean force;
        ObfuscationResolver dex;
        Session(ClassLoader loader, boolean force) { this.loader = loader; this.force = force; }
        ObfuscationResolver dex() throws Exception {
            if (dex == null) dex = ObfuscationResolver.open(loader);
            return dex;
        }
        Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass(name); }

        Method resolve(Class<?> owner, String alias, Class<?> result, Class<?>[] args,
                MethodMatcher fallback) throws Exception {
            if (!force) try {
                Method m = exact(owner, alias, args);
                if (m.getReturnType() == result) return m;
            } catch (NoSuchMethodException ignored) { }
            if (fallback == null) return uniqueShape(owner, result, args, null);
            return dex().uniqueMethod(owner.getName() + "#" + alias, fallback,
                    m -> m.getDeclaringClass().isAssignableFrom(owner)
                            && !m.getDeclaringClass().isInterface()
                            && m.getReturnType() == result && Arrays.equals(args, m.getParameterTypes()));
        }
        Method bind(Plan p, Class<?> owner, String alias, Class<?> result, Class<?>[] args,
                MethodMatcher fallback) throws Exception {
            Method m = resolve(owner, alias, result, args, fallback); p.put(owner, alias, m); return m;
        }
        Method noarg(Plan p, Class<?> owner, String alias, MethodMatcher fallback) throws Exception {
            return bind(p, owner, alias, void.class, new Class<?>[0], fallback);
        }
        Method linked(Method source, java.util.function.Predicate<Method> check, String purpose) throws Exception {
            Map<String, Method> found = new LinkedHashMap<>();
            for (MethodData d : dex().bridge().getMethodData(source).getInvokes()) {
                // DEX invoke lists include constructors, which are not java.lang.reflect.Method.
                if (d.getDescriptor().contains("-><")) continue;
                Method m;
                try { m = d.getMethodInstance(loader); }
                catch (ReflectiveOperationException | LinkageError unavailable) { continue; }
                if (check.test(m)) { m.setAccessible(true); found.put(d.getDescriptor(), m); }
            }
            if (found.size() != 1) throw new NoSuchMethodException(purpose + " invoke candidates=" + found.size());
            return found.values().iterator().next();
        }
        Field readField(Method getter, Class<?> owner, Class<?> fieldType, String purpose) throws Exception {
            Map<String, Field> found = new LinkedHashMap<>();
            for (UsingFieldData d : dex().bridge().getMethodData(getter).getUsingFields()) {
                Field f = d.getField().getFieldInstance(loader);
                if (f.getDeclaringClass() == owner && f.getType() == fieldType
                        && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true); found.put(f.getName(), f);
                }
            }
            if (found.size() != 1) throw new NoSuchFieldException(purpose + " fields=" + found.size());
            return found.values().iterator().next();
        }
        Plan ui() throws Exception {
            Plan p = new Plan(loader);
            Class<?> pref = type(PREF), group = type(GROUP);
            Class<?> fragment = type("androidx.fragment.app.Fragment");
            if (!force) try { p.mainFragment = type(SETTINGS + "i"); }
            catch (ClassNotFoundException ignored) { }
            if (p.mainFragment == null || !fragment.isAssignableFrom(p.mainFragment)) {
                p.mainFragment = dex().uniqueClass("sidebar main fragment",
                        ClassMatcher.create().usingStrings("EdgePanelSettingsFragment", "key_root", "key_toggle"),
                        c -> fragment.isAssignableFrom(c));
            }
            bind(p, p.mainFragment, "X", void.class, new Class<?>[]{boolean.class},
                    mainUpdate().declaredClass(p.mainFragment));
            bind(p, group, "X0", pref, new Class<?>[]{CharSequence.class}, findPreference());
            Method add = bind(p, group, "W0", boolean.class, new Class<?>[]{pref}, addPreference());
            bind(p, pref, "u", Context.class, new Class<?>[0],
                    shape(pref, Context.class));
            Method summary = bind(p, pref, "L0", void.class, new Class<?>[]{CharSequence.class},
                    summary());
            Method title;
            if (!force) try { title = exact(pref, "O0", CharSequence.class); }
            catch (NoSuchMethodException missing) { title = null; }
            else title = null;
            if (title == null || title.getReturnType() != void.class) title = uniqueShape(pref, void.class,
                    new Class<?>[]{CharSequence.class}, m -> !m.equals(summary));
            p.put(pref, "O0", title);
            Method order;
            if (!force) try { order = exact(pref, "I0", int.class); }
            catch (NoSuchMethodException missing) { order = null; }
            else order = null;
            if (order == null || order.getReturnType() != void.class) order = linked(add,
                    m -> m.getDeclaringClass() == pref && signature(m, void.class, int.class), "preference order");
            p.put(pref, "I0", order);
            bind(p, pref, "P0", void.class, new Class<?>[]{boolean.class},
                    shape(pref, void.class, boolean.class).modifiers(Modifier.FINAL));
            bind(p, pref, "D0", void.class, new Class<?>[]{Intent.class}, shape(pref, void.class, Intent.class));
            p.changeListener = listener(pref, pref, Object.class);
            p.clickListener = listener(pref, pref);
            bind(p, pref, "G0", void.class, new Class<?>[]{p.changeListener}, shape(pref, void.class, p.changeListener));
            bind(p, pref, "H0", void.class, new Class<?>[]{p.clickListener}, shape(pref, void.class, p.clickListener));
            Class<?> toggle = type("com.coui.appcompat.preference.COUISwitchPreference");
            Method checked = null;
            if (!force) try { checked = exact(toggle, "W0", boolean.class); }
            catch (NoSuchMethodException ignored) { }
            if (checked == null || checked.getReturnType() != void.class) {
                Method clickToggle = dex().uniqueMethod("sidebar native toggle callback",
                        MethodMatcher.create().declaredClass(p.mainFragment).usingStrings("onClickToggle"), m -> true);
                checked = linked(clickToggle, m -> m.getDeclaringClass().isAssignableFrom(toggle)
                        && signature(m, void.class, boolean.class), "switch checked");
            }
            p.put(toggle, "W0", checked);
            Field key = null, persistent = null;
            if (!force) {
                try { key = pref.getDeclaredField("s"); if (key.getType() != String.class) key = null; }
                catch (NoSuchFieldException ignored) { }
                try { persistent = pref.getDeclaredField("y"); if (persistent.getType() != boolean.class) persistent = null; }
                catch (NoSuchFieldException ignored) { }
            }
            if (key == null) {
                Method getKey = linked(p.method(group, "X0"),
                        m -> m.getDeclaringClass() == pref && signature(m, String.class), "preference key getter");
                key = readField(getKey, pref, String.class, "preference key");
            }
            if (persistent == null) {
                Method persistBoolean = dex().uniqueMethod("preference persist boolean",
                        shape(pref, boolean.class, boolean.class).addInvoke(
                                "Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;"),
                        m -> m.getDeclaringClass() == pref);
                Method shouldPersist = linked(persistBoolean,
                        m -> m.getDeclaringClass() == pref && signature(m, boolean.class), "preference shouldPersist");
                List<Field> candidates = new ArrayList<>();
                for (MethodData d : dex().bridge().getMethodData(shouldPersist).getInvokes()) {
                    if (!d.getDeclaredClassName().equals(PREF) || !d.getReturnTypeName().equals("boolean") || d.getParamCount() != 0) continue;
                    Method m = d.getMethodInstance(loader);
                    // hasKey calls TextUtils.isEmpty; the persistent getter is a pure field read.
                    if (d.getInvokes().isEmpty()) candidates.add(readField(m, pref, boolean.class, "persistent getter"));
                }
                if (candidates.size() != 1) throw new NoSuchFieldException("persistent candidates=" + candidates.size());
                persistent = candidates.get(0);
            }
            p.field(pref, "key", key); p.field(pref, "persistent", persistent);
            for (Class<?> f : new Class<?>[]{p.mainFragment, type(SETTINGS + "FloatBarSettingsFragment"),
                    type(SETTINGS + "FloatBarSettingsWithoutGestureFragment")}) {
                // PreferenceFragment's single CharSequence->Preference boundary may be inherited.
                bind(p, f, "a", pref, new Class<?>[]{CharSequence.class},
                        MethodMatcher.create().returnType(PREF).paramTypes("java.lang.CharSequence")
                                .declaredClass(ClassMatcher.create().className("androidx.preference", org.luckypray.dexkit.query.enums.StringMatchType.StartsWith)));
            }
            return p;
        }

        void nativePage(Plan p) throws Exception {
            Class<?> pref = type(PREF), bar = type(BAR), proxy = type(PROXY);
            // Every native write reachable from the card page must have an isolated exact hook.
            proxy(p, proxy, "getFloatBarPermanentEnable", int.class, "edge_panel_float_bar_permanent");
            proxy(p, proxy, "setFloatBarPermanentEnable", void.class, "edge_panel_float_bar_permanent", int.class);
            proxy(p, proxy, "getFloatBarAlpha", int.class, "edge_panel_os_float_bar_alpha");
            proxy(p, proxy, "setFloatBarAlpha", void.class, "edge_panel_os_float_bar_alpha", int.class);
            proxy(p, proxy, "setFloatBarSize", void.class, "key_float_bar_permanent_mode_size", int.class);
            proxy(p, proxy, "setFloatBarPosition", void.class, "key_float_bar_position", float.class);
            proxy(p, proxy, "setFloatBarLeftRight", void.class, "key_float_bar_left_right", boolean.class);
            proxy(p, proxy, "resetFloatBarPosition", void.class, "key_float_bar_position");
            proxy(p, proxy, "resetFloatBarLeftRight", void.class, "key_float_bar_left_right");
            proxy(p, proxy, "setSystemUiSlideGestureEnable", void.class, "edge_panel_system_ui_slide_gesture", int.class);
            proxy(p, proxy, "getFloatBarIsLeft", boolean.class, "key_float_bar_left_right");
            proxy(p, proxy, "isSystemUiSlideGestureEnable", boolean.class, "edge_panel_system_ui_slide_gesture");
            // The reset getter itself can write settings, so also find a renamed counterpart.
            try {
                Method m = resolve(proxy, "isSystemUiSlideGestureEnableAndResetSetting", boolean.class,
                        new Class<?>[]{Context.class}, shape(proxy, boolean.class, Context.class)
                                .usingStrings("edge_panel_system_ui_slide_gesture")
                                .addInvoke(descriptor(p.method(proxy, "setSystemUiSlideGestureEnable"))));
                if (!Modifier.isStatic(m.getModifiers())) throw new NoSuchMethodException("gesture reset not static");
                p.put(proxy, "isSystemUiSlideGestureEnableAndResetSetting", m);
            } catch (NoSuchMethodException missing) {
                // If the class still has a candidate reset method, ambiguity is unsafe.
                if (!dex().bridge().findMethod(FindMethod.create().matcher(shape(proxy, boolean.class, Context.class)
                        .usingStrings("edge_panel_system_ui_slide_gesture")
                        .addInvoke(descriptor(p.method(proxy, "setSystemUiSlideGestureEnable"))))).isEmpty()) throw missing;
            }
            bind(p, pref, "z0", void.class, new Class<?>[]{boolean.class}, enabledSetter());
            Class<?> promise = type(WIDGETS + "PromiseSeekbarPreference");
            bind(p, promise, "g1", bar, new Class<?>[0], shape(promise, bar));
            Method startBase = bind(p, promise, "j1", void.class, new Class<?>[0],
                    shape(promise, void.class).modifiers(Modifier.ABSTRACT));
            Method stopBase = bind(p, promise, "k1", void.class, new Class<?>[]{bar},
                    shape(promise, void.class, bar).modifiers(Modifier.ABSTRACT));
            Method startCallback = resolve(promise, "l", void.class, new Class<?>[]{bar},
                    shape(promise, void.class, bar).usingStrings("onStartTrackingTouch"));
            Method stopCallback = resolve(promise, "j", void.class, new Class<?>[]{bar},
                    shape(promise, void.class, bar).usingStrings("onStopTrackingTouch"));
            for (String simple : new String[]{"PositionPromiseSeekbarPreference", "SizePromiseSeekbarPreference"}) {
                Class<?> c = type(WIDGETS + simple); boolean position = simple.startsWith("Position");
                noarg(p, c, "o1", shape(c, void.class).usingStrings(position ? "init progress == " : "init floatBarSize == "));
                bind(p, c, "l1", void.class, new Class<?>[]{bar, int.class}, shape(c, void.class, bar, int.class)
                        .addInvoke(descriptor(p.method(proxy, position ? "setFloatBarPosition" : "setFloatBarSize"))));
                p.put(c, "j1", exact(c, startBase.getName()));
                p.put(c, "k1", exact(c, stopBase.getName(), bar));
            }
            Class<?> alpha = type(WIDGETS + "SeekBarPreference");
            Method alphaValue = bind(p, alpha, "g1", int.class, new Class<?>[0], shape(alpha, int.class)
                    .addInvoke(descriptor(p.method(proxy, "getFloatBarAlpha"))));
            bind(p, alpha, "h1", int.class, new Class<?>[0], shape(alpha, int.class).addInvoke(descriptor(alphaValue)));
            noarg(p, alpha, "j1", shape(alpha, void.class).addInvoke(MethodMatcher.create().name("setProgress").paramTypes("int")));
            bind(p, alpha, "k", void.class, new Class<?>[]{bar, int.class, boolean.class},
                    shape(alpha, void.class, bar, int.class, boolean.class)
                            .addInvoke(descriptor(p.method(proxy, "setFloatBarAlpha"))));
            p.put(alpha, "l", exact(alpha, startCallback.getName(), bar));
            p.put(alpha, "j", exact(alpha, stopCallback.getName(), bar));
            p.field(alpha, "bar", uniqueField(alpha, type("com.coui.appcompat.seekbar.COUISectionSeekBar")));
            for (String simple : new String[]{"FloatBarSettingsWithoutGestureFragment", "FloatBarSettingsFragment"}) {
                boolean gesture = simple.equals("FloatBarSettingsFragment");
                Class<?> c = type(SETTINGS + simple);
                for (String life : new String[]{"onCreate", "onResume", "onPause", "onDestroy"}) {
                    p.put(c, life, exact(c, life, life.equals("onCreate") ? new Class<?>[]{Bundle.class} : new Class<?>[0]));
                }
                bind(p, c, "y", String.class, new Class<?>[0], shape(c, String.class));
                MethodMatcher reset = shape(c, void.class);
                for (String setter : new String[]{"setFloatBarAlpha", "setFloatBarSize", "setFloatBarPosition", "setFloatBarLeftRight"}) {
                    reset.addInvoke(descriptor(p.method(proxy, setter)));
                }
                noarg(p, c, gesture ? "U" : "R", reset);
                noarg(p, c, gesture ? "V" : "S", shape(c, void.class)
                        .addInvoke(descriptor(p.method(proxy, "getFloatBarPermanentEnable"))));
                Method bottom = noarg(p, c, gesture ? "e0" : "Z", gesture
                        ? shape(c, void.class).usingStrings("updateBottomTips", "root_pfs")
                        : shape(c, void.class).usingStrings("root_pfs").addInvoke(MethodMatcher.create()
                                .name("isGestureNavMode").returnType("boolean").paramTypes()));
                noarg(p, c, gesture ? "g0" : "b0", shape(c, void.class)
                        .addInvoke(descriptor(bottom)).addInvoke(descriptor(p.method(type(GROUP), "W0")))
                        .addUsingField(FieldMatcher.create().declaredClass(c).type(alpha)));
                if (gesture) noarg(p, c, "h0", shape(c, void.class).usingStrings("root_pfs")
                        .addInvoke(descriptor(bottom)).addInvoke(descriptor(p.method(type(GROUP), "W0"))).addUsingField(FieldMatcher.create()
                                .declaredClass("com.coloros.edgepanel.utils.EdgePanelFeatureOption").name("IS_GLOBAL_INPUT_SUPPORTED")));
                p.field(c, "alpha", uniqueField(c, alpha));
            }
            Method cancel = null;
            if (!force) try { cancel = exact(bar, "w1"); } catch (NoSuchMethodException ignored) { }
            if (cancel == null || !signature(cancel, void.class)) {
                Method reset = p.method(type(SETTINGS + "FloatBarSettingsWithoutGestureFragment"), "R");
                cancel = linked(reset, m -> m.getDeclaringClass().isAssignableFrom(bar)
                        && signature(m, void.class), "seekbar cancel from reset");
            }
            p.put(bar, "w1", cancel);
            Class<?> activity = type(SETTINGS + "FloatBarSettingsActivity");
            Class<?> without = type(SETTINGS + "FloatBarSettingsWithoutGestureFragment");
            Method createFragment = noarg(p, activity, "P", shape(activity, void.class).modifiers(Modifier.PRIVATE)
                    .addInvoke(MethodMatcher.create().returnType(without).paramTypes()));
            Class<?> fragmentActivity = type("androidx.fragment.app.FragmentActivity");
            Method manager = null;
            if (!force) try { manager = exact(fragmentActivity, "o"); } catch (NoSuchMethodException ignored) { }
            if (manager == null) manager = linked(createFragment, m -> m.getDeclaringClass().isAssignableFrom(activity)
                    && m.getParameterCount() == 0 && m.getReturnType().getName().startsWith("androidx.fragment.app.")
                    && !m.getReturnType().getName().endsWith("Fragment"), "fragment manager");
            p.put(fragmentActivity, "o", manager);
            Class<?> managerType = manager.getReturnType();
            Method transaction = linkedOrNamed(createFragment, managerType, "k", m -> m.getDeclaringClass() == managerType
                    && m.getParameterCount() == 0 && m.getReturnType().getName().startsWith("androidx.fragment.app.")
                    && m.getReturnType() != managerType, "begin transaction");
            p.put(managerType, "k", transaction);
            Class<?> tx = transaction.getReturnType();
            Method replace = linkedOrNamed(createFragment, tx, "p", m -> m.getDeclaringClass() == tx
                    && signature(m, tx, int.class, typeUnchecked("androidx.fragment.app.Fragment")), "replace fragment", int.class, type("androidx.fragment.app.Fragment"));
            p.put(tx, "p", replace);
            Method commit = linkedOrNamed(createFragment, tx, "i", m -> m.getDeclaringClass() == tx
                    && signature(m, int.class), "commit transaction");
            p.put(tx, "i", commit);
            // All Android lifecycle methods are retained by framework contracts.
            exact(type(SETTINGS + "FloatBarSettingsActivity"), "onCreate", Bundle.class);
            exact(type(SETTINGS + "FloatBarSettingsPhoneActivity"), "onCreate", Bundle.class);
            exact(type("androidx.appcompat.app.AppCompatActivity"), "onCreate", Bundle.class);
        }
        Class<?> typeUnchecked(String name) { try { return type(name); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); } }
        Method linkedOrNamed(Method source, Class<?> owner, String name,
                java.util.function.Predicate<Method> check, String purpose, Class<?>... args) throws Exception {
            if (!force) try { Method m = exact(owner, name, args); if (check.test(m)) return m; }
            catch (NoSuchMethodException ignored) { }
            return linked(source, check, purpose);
        }
        void proxy(Plan p, Class<?> owner, String name, Class<?> ret, String key, Class<?>... rest) throws Exception {
            Class<?>[] args = new Class<?>[rest.length + 1]; args[0] = Context.class;
            System.arraycopy(rest, 0, args, 1, rest.length);
            MethodMatcher matcher = shape(owner, ret, args).modifiers(Modifier.PUBLIC | Modifier.STATIC).usingStrings(key);
            if (name.equals("resetFloatBarPosition")) matcher.usingStrings(key, "percent_y");
            if (name.equals("resetFloatBarLeftRight")) matcher.usingStrings(key, "isLeft");
            // The two boolean gesture getters differ by a reset setter call.
            if (name.equals("isSystemUiSlideGestureEnable")) {
                if (!force) try {
                    Method m = exact(owner, name, args);
                    if (m.getReturnType() == ret && Modifier.isStatic(m.getModifiers())) { p.put(owner, name, m); return; }
                } catch (NoSuchMethodException ignored) { }
                Method setter = p.method(owner, "setSystemUiSlideGestureEnable");
                Method m = dex().uniqueMethod(name, matcher, candidate -> {
                    for (MethodData invoked : dex().bridge().getMethodData(candidate).getInvokes())
                        if (invoked.getDescriptor().equals(descriptor(setter))) return false;
                    return Modifier.isStatic(candidate.getModifiers()) && signature(candidate, ret, args);
                });
                p.put(owner, name, m); return;
            }
            Method m = bind(p, owner, name, ret, args, matcher);
            if (!Modifier.isStatic(m.getModifiers())) throw new NoSuchMethodException(name + " not static");
        }
        @Override public void close() { if (dex != null) dex.close(); }
    }

    private static Class<?> listener(Class<?> preference, Class<?>... args) throws Exception {
        Map<String, Class<?>> candidates = new LinkedHashMap<>();
        for (Field f : preference.getDeclaredFields()) {
            Class<?> c = f.getType(); if (!c.isInterface()) continue;
            Method[] methods = c.getDeclaredMethods();
            if (methods.length == 1 && signature(methods[0], boolean.class, args)) candidates.put(c.getName(), c);
        }
        if (candidates.size() != 1) throw new ClassNotFoundException("preference listener candidates=" + candidates.size());
        return candidates.values().iterator().next();
    }
    private static Field uniqueField(Class<?> owner, Class<?> fieldType) throws Exception {
        List<Field> fields = new ArrayList<>();
        for (Field f : owner.getDeclaredFields()) if (f.getType() == fieldType && !Modifier.isStatic(f.getModifiers())) fields.add(f);
        if (fields.size() != 1) throw new NoSuchFieldException(owner.getName() + " fields for " + fieldType.getName() + "=" + fields.size());
        fields.get(0).setAccessible(true); return fields.get(0);
    }
    private static Method uniqueShape(Class<?> owner, Class<?> ret, Class<?>[] args,
            java.util.function.Predicate<Method> extra) throws Exception {
        List<Method> found = new ArrayList<>();
        for (Method m : owner.getDeclaredMethods()) if (signature(m, ret, args) && (extra == null || extra.test(m))) found.add(m);
        if (found.size() != 1) throw new NoSuchMethodException(owner.getName() + " signature candidates=" + found.size());
        Method m = found.get(0); m.setAccessible(true); return m;
    }
    static Method exact(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) try {
            Method m = c.getDeclaredMethod(name, args); m.setAccessible(true); return m;
        } catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }
    static boolean signature(Method m, Class<?> ret, Class<?>... args) {
        return m.getReturnType() == ret && Arrays.equals(m.getParameterTypes(), args);
    }
    private static MethodMatcher shape(Class<?> owner, Class<?> ret, Class<?>... args) {
        return MethodMatcher.create().declaredClass(owner).returnType(ret).paramTypes(args);
    }
    private static MethodMatcher mainUpdate() { return MethodMatcher.create().returnType("void").paramTypes("boolean")
            .usingStrings("updatePreferences", "key_panel_category", "key_style_category"); }
    private static MethodMatcher findPreference() { return MethodMatcher.create().declaredClass(GROUP).returnType(PREF)
            .paramTypes("java.lang.CharSequence").usingStrings("Key cannot be null"); }
    private static MethodMatcher addPreference() { return MethodMatcher.create().declaredClass(GROUP).returnType("boolean")
            .paramTypes(PREF).usingStrings("Found duplicated key: \""); }
    private static MethodMatcher summary() { return MethodMatcher.create().declaredClass(PREF).returnType("void")
            .paramTypes("java.lang.CharSequence").usingStrings("Preference already has a SummaryProvider set."); }
    private static MethodMatcher enabledSetter() {
        // setEnabled writes its own bool and notifies dependents from shouldDisableDependents().
        // setSelectable only notifies, setVisible calls its listener, notifyDependencyChange has no bool getter.
        return MethodMatcher.create().declaredClass(PREF).returnType("void").paramTypes("boolean")
                .addUsingField(FieldMatcher.create().declaredClass(PREF).type("boolean"))
                .addInvoke(MethodMatcher.create().declaredClass(PREF).returnType("boolean").paramTypes())
                .addInvoke(MethodMatcher.create().declaredClass(PREF).returnType("void").paramTypes("boolean"))
                .addInvoke(MethodMatcher.create().declaredClass(PREF).returnType("void").paramTypes());
    }
    private static String descriptor(Method method) {
        StringBuilder out = new StringBuilder(descriptor(method.getDeclaringClass())).append("->").append(method.getName()).append('(');
        for (Class<?> c : method.getParameterTypes()) out.append(descriptor(c));
        return out.append(')').append(descriptor(method.getReturnType())).toString();
    }
    private static String descriptor(Class<?> c) {
        if (c == void.class) return "V"; if (c == boolean.class) return "Z"; if (c == int.class) return "I";
        if (c == float.class) return "F"; if (c == long.class) return "J"; if (c == double.class) return "D";
        if (c == byte.class) return "B"; if (c == short.class) return "S"; if (c == char.class) return "C";
        return c.isArray() ? c.getName().replace('.', '/') : "L" + c.getName().replace('.', '/') + ";";
    }

    /** Host-side metadata probe: only reads the APK, prints method descriptors and ambiguity. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: SidebarDexResolver <SmartSideBar.apk>");
        try (ObfuscationResolver dex = ObfuscationResolver.openApk(args[0], null)) {
            Map<String, MethodMatcher> checks = new LinkedHashMap<>();
            checks.put("mainUpdate", mainUpdate()); checks.put("preferenceFind", findPreference());
            checks.put("preferenceAdd", addPreference()); checks.put("preferenceSummary", summary());
            checks.put("preferenceEnabled", enabledSetter());
            checks.put("positionRender", MethodMatcher.create().declaredClass(WIDGETS + "PositionPromiseSeekbarPreference")
                    .returnType("void").paramTypes().usingStrings("init progress == "));
            checks.put("sizeRender", MethodMatcher.create().declaredClass(WIDGETS + "SizePromiseSeekbarPreference")
                    .returnType("void").paramTypes().usingStrings("init floatBarSize == "));
            String[] proxyNames = {"getFloatBarPermanentEnable", "setFloatBarPermanentEnable", "getFloatBarAlpha",
                    "setFloatBarAlpha", "setFloatBarSize", "setFloatBarPosition", "setFloatBarLeftRight",
                    "resetFloatBarPosition", "resetFloatBarLeftRight", "setSystemUiSlideGestureEnable", "getFloatBarIsLeft"};
            String[] proxyKeys = {"edge_panel_float_bar_permanent", "edge_panel_float_bar_permanent", "edge_panel_os_float_bar_alpha",
                    "edge_panel_os_float_bar_alpha", "key_float_bar_permanent_mode_size", "key_float_bar_position", "key_float_bar_left_right",
                    "key_float_bar_position", "key_float_bar_left_right", "edge_panel_system_ui_slide_gesture", "key_float_bar_left_right"};
            String[] returns = {"int", "void", "int", "void", "void", "void", "void", "void", "void", "void", "boolean"};
            String[] values = {null, "int", null, "int", "int", "float", "boolean", null, null, "int", null};
            Map<String, String> descriptors = new LinkedHashMap<>();
            for (int i = 0; i < proxyNames.length; i++) {
                MethodMatcher matcher = MethodMatcher.create().declaredClass(PROXY).modifiers(Modifier.PUBLIC | Modifier.STATIC)
                        .returnType(returns[i]).paramTypes(values[i] == null ? new String[]{"android.content.Context"}
                                : new String[]{"android.content.Context", values[i]}).usingStrings(proxyKeys[i]);
                if (proxyNames[i].equals("resetFloatBarPosition")) matcher.usingStrings(proxyKeys[i], "percent_y");
                if (proxyNames[i].equals("resetFloatBarLeftRight")) matcher.usingStrings(proxyKeys[i], "isLeft");
                MethodData d = metadata(dex, proxyNames[i], matcher);
                descriptors.put(proxyNames[i], d.getDescriptor());
            }
            for (String simple : new String[]{"FloatBarSettingsWithoutGestureFragment", "FloatBarSettingsFragment"}) {
                String owner = SETTINGS + simple;
                MethodMatcher reset = MethodMatcher.create().declaredClass(owner).returnType("void").paramTypes();
                for (String setter : new String[]{"setFloatBarAlpha", "setFloatBarSize", "setFloatBarPosition", "setFloatBarLeftRight"}) reset.addInvoke(descriptors.get(setter));
                MethodData resetData = metadata(dex, simple + ".reset", reset);
                if (!simple.equals("FloatBarSettingsFragment")) {
                    Map<String, MethodData> stop = new LinkedHashMap<>();
                    for (MethodData invoked : resetData.getInvokes()) {
                        if (invoked.getDeclaredClassName().startsWith("com.coui.appcompat.seekbar.")
                                && invoked.getReturnTypeName().equals("void") && invoked.getParamCount() == 0
                                && !invoked.getDescriptor().contains("-><")) stop.put(invoked.getDescriptor(), invoked);
                    }
                    if (stop.size() != 1) throw new NoSuchMethodException("seekbar cancel candidates=" + stop.keySet());
                    System.out.println("seekbarCancel=" + stop.keySet().iterator().next());
                }
                checks.put(simple + ".radio", MethodMatcher.create().declaredClass(owner).returnType("void").paramTypes()
                        .addInvoke(descriptors.get("getFloatBarPermanentEnable")));
                MethodMatcher bottom = MethodMatcher.create().declaredClass(owner).returnType("void").paramTypes().usingStrings("root_pfs");
                boolean gesture = simple.equals("FloatBarSettingsFragment");
                if (gesture) bottom.usingStrings("root_pfs", "updateBottomTips");
                else bottom.addInvoke(MethodMatcher.create().name("isGestureNavMode").returnType("boolean").paramTypes());
                String bottomDescriptor = metadata(dex, simple + ".bottom", bottom).getDescriptor();
                checks.put(simple + ".alphaVisibility", MethodMatcher.create().declaredClass(owner).returnType("void").paramTypes()
                        .addInvoke(bottomDescriptor).addInvoke("Landroidx/preference/PreferenceGroup;->W0(Landroidx/preference/Preference;)Z")
                        .addUsingField(FieldMatcher.create().declaredClass(owner).type(WIDGETS + "SeekBarPreference")));
                if (gesture) checks.put(simple + ".gestureVisibility", MethodMatcher.create().declaredClass(owner).returnType("void").paramTypes()
                        .usingStrings("root_pfs").addInvoke(bottomDescriptor)
                        .addInvoke("Landroidx/preference/PreferenceGroup;->W0(Landroidx/preference/Preference;)Z").addUsingField(FieldMatcher.create()
                                .declaredClass("com.coloros.edgepanel.utils.EdgePanelFeatureOption").name("IS_GLOBAL_INPUT_SUPPORTED")));
            }
            checks.put("positionChange", MethodMatcher.create().declaredClass(WIDGETS + "PositionPromiseSeekbarPreference")
                    .returnType("void").paramTypes(BAR, "int").addInvoke(descriptors.get("setFloatBarPosition")));
            checks.put("sizeChange", MethodMatcher.create().declaredClass(WIDGETS + "SizePromiseSeekbarPreference")
                    .returnType("void").paramTypes(BAR, "int").addInvoke(descriptors.get("setFloatBarSize")));
            checks.put("alphaValue", MethodMatcher.create().declaredClass(WIDGETS + "SeekBarPreference").returnType("int").paramTypes()
                    .addInvoke(descriptors.get("getFloatBarAlpha")));
            checks.put("alphaRender", MethodMatcher.create().declaredClass(WIDGETS + "SeekBarPreference").returnType("void").paramTypes()
                    .addInvoke(MethodMatcher.create().name("setProgress").paramTypes("int")));
            checks.put("alphaChange", MethodMatcher.create().declaredClass(WIDGETS + "SeekBarPreference").returnType("void")
                    .paramTypes(BAR, "int", "boolean").addInvoke(descriptors.get("setFloatBarAlpha")));
            checks.put("sliderStart", MethodMatcher.create().declaredClass(WIDGETS + "PromiseSeekbarPreference").returnType("void")
                    .paramTypes(BAR).usingStrings("onStartTrackingTouch"));
            checks.put("sliderStop", MethodMatcher.create().declaredClass(WIDGETS + "PromiseSeekbarPreference").returnType("void")
                    .paramTypes(BAR).usingStrings("onStopTrackingTouch"));
            checks.put("createFragment", MethodMatcher.create().declaredClass(SETTINGS + "FloatBarSettingsActivity").returnType("void")
                    .paramTypes().modifiers(Modifier.PRIVATE).addInvoke(MethodMatcher.create()
                            .returnType(SETTINGS + "FloatBarSettingsWithoutGestureFragment").paramTypes()));
            for (Map.Entry<String, MethodMatcher> e : checks.entrySet()) {
                metadata(dex, e.getKey(), e.getValue());
            }
        }
    }
    private static MethodData metadata(ObfuscationResolver dex, String purpose, MethodMatcher matcher) throws Exception {
        Map<String, MethodData> found = new LinkedHashMap<>();
        for (MethodData d : dex.bridge().findMethod(FindMethod.create().matcher(matcher))) found.put(d.getDescriptor(), d);
        if (found.size() != 1) throw new NoSuchMethodException(purpose + " candidates=" + found.keySet());
        MethodData result = found.values().iterator().next();
        System.out.println(purpose + "=" + result.getDescriptor());
        return result;
    }
}
