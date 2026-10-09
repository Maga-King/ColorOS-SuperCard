package dev.local.supercardhost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;
import org.luckypray.dexkit.result.FieldUsingType;

/** Read-only wallet anchors. No candidate business method is invoked during discovery. */
public final class WalletDexResolver {
    private static final String VM = "com.finshell.doubleclick.tickets.TicketOrderViewModel";
    private static final String ACCOUNT = "com.finshell.accountservice.AccountUtilsKt";
    private static final String LOCATION = "com.finshell.location.LocationInfoEntity";
    private static final String AUTH_RESPONSE = "com.finshell.doubleclick.tickets.auth.domian.MovieTicketAuthRspVO";
    private static final String ORDER_RESPONSE = "com.finshell.doubleclick.tickets.groupon.domain.QueryDouyinOrderRspVO";
    public record LoginBinding(Method method, Class<?> callback) { }
    private WalletDexResolver() { }

    static LoginBinding login(ClassLoader loader) throws Exception {
        try {
            Class<?> facade = Class.forName("com.finshell.accountservice.a", false, loader);
            Method found = null;
            for (Method method : facade.getDeclaredMethods()) {
                if (!"hasLoginAsync".equals(method.getName()) || !loginShape(method)) continue;
                if (found != null) throw new NoSuchMethodException("Ambiguous native login facade");
                found = method;
            }
            if (found == null) throw new NoSuchMethodException("Native login facade unavailable");
            found.setAccessible(true);
            return new LoginBinding(found, found.getParameterTypes()[0]);
        } catch (ReflectiveOperationException | LinkageError changed) {
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                Method found = dex.uniqueMethod("wallet login", loginMatcher(), WalletDexResolver::loginShape);
                return new LoginBinding(found, found.getParameterTypes()[0]);
            }
        }
    }

    static Method oid(ClassLoader loader) throws Exception {
        try { return named(loader, "rr.a", "j", true, String.class); }
        catch (ReflectiveOperationException | LinkageError changed) {
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                return dex.uniqueMethod("wallet account getter", oidMatcher(), method ->
                        shape(method, true, String.class));
            }
        }
    }

    static Method auth(ClassLoader loader, Class<?> owner) throws Exception {
        try { return named(loader, owner.getName(), "U", false, void.class, String.class); }
        catch (ReflectiveOperationException | LinkageError changed) {
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                return dex.uniqueMethod("wallet authorization", authMatcher(), method ->
                        method.getDeclaringClass() == owner && shape(method, false, void.class, String.class));
            }
        }
    }

    static Method query(ClassLoader loader, Class<?> owner, Class<?> location) throws Exception {
        try {
            Method found = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (!shape(method, false, void.class, location, boolean.class)) continue;
                if (found != null) throw new NoSuchMethodException("Ambiguous native order query");
                found = method;
            }
            if (found == null) throw new NoSuchMethodException("Native order query unavailable");
            found.setAccessible(true); return found;
        } catch (ReflectiveOperationException | LinkageError changed) {
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                return dex.uniqueMethod("wallet order query", queryMatcher(), method ->
                        method.getDeclaringClass() == owner
                                && shape(method, false, void.class, location, boolean.class));
            }
        }
    }

    static Method callback(ClassLoader loader, Class<?> owner, Class<?> response) throws Exception {
        try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
            return dex.uniqueMethod("wallet response callback", callbackMatcher(response.getName()), method ->
                    shape(method, false, void.class, int.class, int.class, Object.class, response)
                            && uniqueOwner(method.getDeclaringClass(), owner) != null);
        }
    }

    static Field orderIds(ClassLoader loader, Class<?> owner, Method query) throws Exception {
        try {
            Field found = null;
            for (Field field : owner.getDeclaredFields()) {
                if (field.getType() != List.class || Modifier.isStatic(field.getModifiers())
                        || Modifier.isFinal(field.getModifiers())) continue;
                if (found != null) throw new NoSuchFieldException("Ambiguous native order ID lists");
                found = field;
            }
            if (found == null) throw new NoSuchFieldException("Native order ID list unavailable");
            found.setAccessible(true); return found;
        } catch (ReflectiveOperationException | LinkageError changed) {
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                MethodData method = dex.bridge().getMethodData(query);
                if (method == null) throw new NoSuchMethodException("Native order query DEX unavailable");
                Map<String, Field> matches = new LinkedHashMap<>();
                for (UsingFieldData used : method.getUsingFields()) {
                    if (used.getUsingType() != FieldUsingType.Read) continue;
                    Field field = used.getField().getFieldInstance(loader);
                    if (field.getDeclaringClass() != owner || field.getType() != List.class
                            || Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                    matches.put(used.getField().getDescriptor(), field);
                }
                if (matches.size() != 1) throw new NoSuchFieldException("Native query order ID fields=" + matches.size());
                Field found = matches.values().iterator().next(); found.setAccessible(true); return found;
            }
        }
    }

    static Method inventory(ClassLoader loader, Class<?> owner, Class<?> result, Class<?>... parameters)
            throws Exception {
        try {
            Method found = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (!shape(method, false, result, parameters)) continue;
                if (found != null) throw new NoSuchMethodException("Ambiguous native inventory method");
                found = method;
            }
            if (found == null) throw new NoSuchMethodException("Native inventory method unavailable");
            found.setAccessible(true); return found;
        } catch (ReflectiveOperationException | LinkageError changed) {
            try (ObfuscationResolver dex = ObfuscationResolver.open(loader)) {
                MethodMatcher matcher = parameters.length == 0 ? inventoryMatcher() : inventorySaveMatcher();
                return dex.uniqueMethod("wallet inventory", matcher, method ->
                        method.getDeclaringClass() == owner && shape(method, false, result, parameters));
            }
        }
    }

    static Field uniqueOwner(Class<?> callback, Class<?> owner) throws NoSuchFieldException {
        Field found = null;
        for (Field field : callback.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != owner) continue;
            if (found != null) throw new NoSuchFieldException("Ambiguous native callback owner");
            field.setAccessible(true); found = field;
        }
        return found;
    }

    private static Method named(ClassLoader loader, String owner, String name, boolean isStatic,
            Class<?> result, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = Class.forName(owner, false, loader).getDeclaredMethod(name, parameters);
        if (!shape(method, isStatic, result, parameters)) throw new NoSuchMethodException("Native method shape changed");
        method.setAccessible(true); return method;
    }

    private static boolean shape(Method method, boolean isStatic, Class<?> result, Class<?>... parameters) {
        return Modifier.isStatic(method.getModifiers()) == isStatic && method.getReturnType() == result
                && Arrays.equals(method.getParameterTypes(), parameters);
    }

    private static boolean loginShape(Method method) throws Exception {
        if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != void.class || method.getParameterCount() != 1
                || ACCOUNT.equals(method.getDeclaringClass().getName())) return false;
        Class<?> callback = method.getParameterTypes()[0];
        if (!callback.isInterface()) return false;
        try {
            return callback.getMethod("onSuccess", Object.class).getReturnType() == void.class
                    && callback.getMethod("onFail", int.class, String.class).getReturnType() == void.class;
        } catch (NoSuchMethodException changed) { return false; }
    }

    static MethodMatcher oidMatcher() {
        // Find the account wrapper via its own logging vocabulary, and verify that
        // this exact getter directly invokes the cached SSO-hash preference reader.
        return MethodMatcher.create().modifiers(Modifier.STATIC).returnType("java.lang.String").paramTypes()
                .declaredClass(ClassMatcher.create().usingStrings("As-UtilsSp",
                        "clearOfflineSsoId: config and set empty", "getOfflineValue: cache and get "))
                .addInvoke(MethodMatcher.create().returnType("java.lang.String")
                        .paramTypes("android.content.Context").usingStrings("key_sso_id_hash"));
    }

    static MethodMatcher loginMatcher() {
        return MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC)
                .returnType("void").paramCount(1)
                .addInvoke(MethodMatcher.create().declaredClass(ACCOUNT)
                        .name("hasLoginAsync").returnType("void").paramCount(1));
    }

    static MethodMatcher authMatcher() {
        return MethodMatcher.create().declaredClass(VM).returnType("void")
                .paramTypes("java.lang.String").usingStrings("authType")
                .addInvoke("Lcom/finshell/doubleclick/tickets/auth/domian/MovieTicketAuthRequest;-><init>"
                        + "(Lcom/finshell/doubleclick/tickets/auth/domian/MovieTicketAuthReqVO;)V");
    }

    static MethodMatcher queryMatcher() {
        return MethodMatcher.create().declaredClass(VM).returnType("void")
                .paramTypes(LOCATION, "boolean")
                .addInvoke("Lcom/finshell/doubleclick/tickets/groupon/domain/QueryDouyinOrderRequest;-><init>"
                        + "(Lcom/finshell/doubleclick/tickets/groupon/domain/QueryDouyinOrderReqVO;)V");
    }

    static MethodMatcher callbackMatcher(String response) {
        return MethodMatcher.create().returnType("void")
                .paramTypes("int", "int", "java.lang.Object", response)
                .addUsingField(FieldMatcher.create().type(VM));
    }

    static MethodMatcher inventoryMatcher() {
        return MethodMatcher.create().declaredClass("com.nearme.pay.business.cardpackage.tickets.TicketsRepo")
                .returnType("io.reactivex.Observable").paramTypes().usingStrings("TICKET", "flatMap(...)");
    }

    static MethodMatcher inventorySaveMatcher() {
        return MethodMatcher.create().declaredClass("com.nearme.pay.business.cardpackage.tickets.TicketsRepo")
                .returnType("void").paramTypes("com.nearme.pay.domain.rsp.CardTabTabRspVO")
                .usingStrings("cardTabRspVO")
                .addInvoke("Lcom/nearme/common/domain/rsp/CardPackageRspVo;->setTicketList(Ljava/util/List;)V");
    }

    /** Force all DexKit fallbacks, without invoking login/getter/network/save methods. */
    public static Map<String, String> probe(ClassLoader target) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Class<?> vm = Class.forName(VM, false, target);
        Class<?> location = Class.forName(LOCATION, false, target);
        try (ObfuscationResolver dex = ObfuscationResolver.open(target)) {
            result.put("oid", dex.uniqueMethod("wallet account getter", oidMatcher(),
                    method -> shape(method, true, String.class)).toGenericString());
            result.put("login", dex.uniqueMethod("wallet login", loginMatcher(),
                    WalletDexResolver::loginShape).toGenericString());
            result.put("authorization", dex.uniqueMethod("wallet authorization", authMatcher(),
                    method -> method.getDeclaringClass() == vm
                            && shape(method, false, void.class, String.class)).toGenericString());
            result.put("orders", dex.uniqueMethod("wallet order query", queryMatcher(),
                    method -> method.getDeclaringClass() == vm
                            && shape(method, false, void.class, location, boolean.class)).toGenericString());
            Class<?> repo = Class.forName("com.nearme.pay.business.cardpackage.tickets.TicketsRepo", false, target);
            Class<?> observable = Class.forName("io.reactivex.Observable", false, target);
            Class<?> tab = Class.forName("com.nearme.pay.domain.rsp.CardTabTabRspVO", false, target);
            result.put("inventory", dex.uniqueMethod("wallet inventory", inventoryMatcher(), candidate ->
                    candidate.getDeclaringClass() == repo && shape(candidate, false, observable)).toGenericString());
            result.put("inventoryCacheBoundary", dex.uniqueMethod("wallet inventory cache", inventorySaveMatcher(), candidate ->
                    candidate.getDeclaringClass() == repo && shape(candidate, false, void.class, tab)).toGenericString());
            for (String responseName : new String[]{AUTH_RESPONSE, ORDER_RESPONSE}) {
                Class<?> response = Class.forName(responseName, false, target);
                Method method = dex.uniqueMethod("wallet callback", callbackMatcher(responseName), candidate ->
                        shape(candidate, false, void.class, int.class, int.class, Object.class, response)
                                && uniqueOwner(candidate.getDeclaringClass(), vm) != null);
                result.put(responseName, method.toGenericString());
            }
        }
        return result;
    }

    /** Desktop JNI metadata test against a real APK. Prints only static DEX descriptors. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Usage: WalletDexResolver wallet.apk");
        Map<String, MethodMatcher> matchers = new LinkedHashMap<>();
        matchers.put("oid", oidMatcher()); matchers.put("login", loginMatcher());
        matchers.put("authorization", authMatcher()); matchers.put("orders", queryMatcher());
        matchers.put("inventory", inventoryMatcher()); matchers.put("inventoryCacheBoundary", inventorySaveMatcher());
        matchers.put("authCallback", callbackMatcher(AUTH_RESPONSE));
        matchers.put("ordersCallback", callbackMatcher(ORDER_RESPONSE));
        try (ObfuscationResolver dex = ObfuscationResolver.openApk(arguments[0], null)) {
            for (Map.Entry<String, MethodMatcher> entry : matchers.entrySet()) {
                Map<String, MethodData> matches = new LinkedHashMap<>();
                for (MethodData data : dex.bridge().findMethod(FindMethod.create().matcher(entry.getValue())))
                    matches.put(data.getDescriptor(), data);
                if (matches.size() != 1) throw new IllegalStateException(entry.getKey() + " candidates=" + matches.size());
                System.out.println(entry.getKey() + "=" + matches.keySet().iterator().next());
            }
        }
    }
}
