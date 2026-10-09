package dev.local.supercardhost;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;

/** Uses the wallet's original read-only authorization/query requests without an Activity. */
public final class WalletNativeTicketRefresh {
    private static final String TAG = "SuperCardWalletRefresh";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Call> CALLS = Collections.synchronizedMap(new IdentityHashMap<>());
    // The original network request can outlive onCleared(). Keep identity tombstones until the
    // private VM is collected so a late callback can never run its original preference writes.
    private static final ReferenceQueue<Object> OWNED_QUEUE = new ReferenceQueue<>();
    private static final Set<IdentityReference> OWNED = new HashSet<>();
    private static volatile XposedModule module;
    private static volatile ClassLoader loader;
    private static volatile Class<?> viewModel, response;
    private static volatile Method auth, query, cleanup, responseOrders, orderId;
    private static volatile Method ticketCode, ticketCodeData, qrCodeUrl, extraInfo, orderStatus;
    private static volatile Field orderIds;
    private static final long REFRESH_BUDGET_MS = 12_000;
    private static final int SINGLE_CONCURRENCY = 4;
    private static boolean resolved;
    private static Exception resolveFailure;

    private WalletNativeTicketRefresh() { }

    public interface AccountVerifier { boolean isCurrent() throws Exception; }

    public record Result(Map<String, Object> details, Set<String> confirmedNoQr) { }

    public static void install(XposedModule source, ClassLoader target) {
        module = source;
        loader = target;
    }

    /** One authorized round: a batch read, then at most one individual read per missing image. */
    public static Result refresh(List<String> requested, AtomicBoolean cancelled,
            AccountVerifier account) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Worker required");
        if (requested.isEmpty()) return new Result(Collections.emptyMap(), Collections.emptySet());
        if (requested.size() > 100) throw new IllegalArgumentException("Too many native orders");
        resolve();
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(requested));
        for (String id : unique) if (id == null || id.trim().isEmpty())
            throw new IllegalArgumentException("Empty native order ID");
        long deadline = SystemClock.elapsedRealtime() + REFRESH_BUDGET_MS;
        Call call = new Call(cancelled, account, deadline);
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> confirmedNoQr = new HashSet<>();
        try {
            final Object ownModel = await(createModel(call, unique), call, 900);
            // U("2") is exactly the original Douyin bind-status read. False never opens login/auth UI.
            MAIN.post(() -> {
                try { call.requireCurrent(); auth.invoke(ownModel, "2"); }
                catch (Throwable error) { call.authorized.completeExceptionally(error); }
            });
            if (!await(call.authorized, call, 2_500)) {
                throw new IllegalStateException("原钱包抖音票券尚未授权");
            }
            Log.i(TAG, "Native ticket authorization accepted");
            try {
                startQuery(call, ownModel);
                mergeMatched(result, await(call.orders, call, 3_500), unique, "batch");
            } catch (Exception error) {
                call.requireCurrent();
                Log.w(TAG, "Batch details unavailable; individual reads remain, failure="
                        + error.getClass().getSimpleName());
            }
        } finally {
            finish(call);
        }
        List<String> missing = new ArrayList<>();
        for (String id : unique) {
            Object detail = result.get(id);
            if (detail == null || needsQrImage(detail)) missing.add(id);
        }
        for (int offset = 0; offset < missing.size(); offset += SINGLE_CONCURRENCY) {
            // All child requests have distinct VMs/callback identities. A late batch or singleton
            // response cannot complete another order's future, even after cleanup/onCleared.
            Call guard = new Call(cancelled, account, deadline);
            guard.requireCurrent();
            if (deadline - SystemClock.elapsedRealtime() < 250) break;
            List<Call> singles = new ArrayList<>();
            List<CompletableFuture<Object>> creations = new ArrayList<>();
            int end = Math.min(missing.size(), offset + SINGLE_CONCURRENCY);
            try {
                for (int i = offset; i < end; i++) {
                    Call single = new Call(cancelled, account, deadline);
                    singles.add(single);
                    creations.add(createModel(single, List.of(missing.get(i))));
                }
                for (int i = 0; i < singles.size(); i++) {
                    Call single = singles.get(i);
                    try { startQuery(single, await(creations.get(i), single, 900)); }
                    catch (Exception error) {
                        guard.requireCurrent();
                        single.fail(error);
                    }
                }
                long waveEnd = Math.min(deadline, SystemClock.elapsedRealtime() + 3_500);
                for (int i = 0; i < singles.size(); i++) {
                    Call single = singles.get(i);
                    String id = missing.get(offset + i);
                    try {
                        List<?> orders = await(single.orders, single,
                                Math.max(1, waveEnd - SystemClock.elapsedRealtime()));
                        Map<String, Object> matched = new LinkedHashMap<>();
                        mergeMatched(matched, orders, List.of(id), "single");
                        Object fresh = matched.get(id);
                        if (fresh != null) {
                            result.put(id, fresh);
                            // Original wallet treats a matched single-order response
                            // with no image as a valid "use in Douyin" ticket.
                            if (needsQrImage(fresh)) confirmedNoQr.add(id);
                        }
                    } catch (Exception error) {
                        guard.requireCurrent();
                        Log.w(TAG, "Individual details incomplete, slot=" + (offset + i)
                                + ", failure=" + error.getClass().getSimpleName());
                    }
                }
            } finally {
                for (Call single : singles) finish(single);
            }
        }
        new Call(cancelled, account, deadline).requireCurrent();
        Log.i(TAG, "Native authorized order round completed: requested=" + unique.size()
                + ", matched=" + result.size() + ", sourceOnly=" + confirmedNoQr.size()
                + ", unresolvedQr=" + (countMissingQrImages(result) - confirmedNoQr.size()));
        return new Result(result, confirmedNoQr);
    }

    /** Pending Douyin needs the native image, never a fabricated QR from verification text. */
    public static int countMissingQrImages(Map<String, ?> details) throws Exception {
        resolve();
        int count = 0;
        for (Object detail : details.values()) if (needsQrImage(detail)) count++;
        return count;
    }

    private static boolean needsQrImage(Object detail) throws Exception {
        Object status = orderStatus.invoke(detail);
        return Integer.valueOf(1).equals(status) && qrBase64(detail).isEmpty();
    }

    private static String qrBase64(Object detail) throws Exception {
        String encoded = readString(extraInfo, detail);
        if (encoded.isEmpty()) return "";
        try {
            Object qr = new JSONObject(encoded).opt("qr_base64");
            return qr instanceof String ? ((String) qr).trim() : "";
        } catch (org.json.JSONException ignored) { return ""; }
    }

    private static String readString(Method method, Object detail) throws Exception {
        Object value = method.invoke(detail);
        return value instanceof String ? (String) value : "";
    }

    private static void mergeMatched(Map<String, Object> result, List<?> orders,
            List<String> requested, String phase) throws Exception {
        int matched = 0, ignored = 0;
        Set<String> seen = new HashSet<>();
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (Object detail : orders) {
            if (detail == null || !orderId.getDeclaringClass().isInstance(detail))
                throw new IllegalStateException("Native order model changed");
            Object id = orderId.invoke(detail);
            if (!(id instanceof String) || !requested.contains(id)) { ignored++; continue; }
            if (!seen.add((String) id)) throw new IllegalStateException("Duplicate native order response");
            accepted.put((String) id, detail);
            String code = readString(ticketCode, detail), data = readString(ticketCodeData, detail);
            String url = readString(qrCodeUrl, detail), image = qrBase64(detail);
            Log.i(TAG, "Native code fields phase=" + phase + ", slot=" + matched
                    + ", status=" + orderStatus.invoke(detail)
                    + ", ticketCodePresent=" + !code.isEmpty() + ", ticketCodeLength=" + code.length()
                    + ", ticketCodeDataPresent=" + !data.isEmpty() + ", ticketCodeDataLength=" + data.length()
                    + ", qrUrlPresent=" + !url.isEmpty() + ", qrUrlLength=" + url.length()
                    + ", qrBase64Present=" + !image.isEmpty() + ", qrBase64Length=" + image.length());
            matched++;
        }
        // Do not publish a partially validated batch containing ambiguous duplicate IDs.
        result.putAll(accepted);
        Log.i(TAG, "Native code response phase=" + phase + ", matched=" + matched + ", ignored=" + ignored);
    }

    private static CompletableFuture<Object> createModel(Call call, List<String> ids) {
        CompletableFuture<Object> created = new CompletableFuture<>();
        MAIN.post(() -> {
            Object pendingModel = null;
            try {
                call.requireCurrent();
                pendingModel = viewModel.getDeclaredConstructor().newInstance();
                rememberOwned(pendingModel);
                synchronized (call) {
                    call.requireCurrent();
                    orderIds.set(pendingModel, new ArrayList<>(ids));
                    call.model = pendingModel;
                    CALLS.put(pendingModel, call);
                    created.complete(pendingModel);
                }
            } catch (Throwable error) {
                if (pendingModel != null) cleanModel(pendingModel);
                created.completeExceptionally(error);
            }
        });
        return created;
    }

    private static void startQuery(Call call, Object model) {
        MAIN.post(() -> {
            try {
                call.requireCurrent();
                // Identical read-only request to the original per-order poll; empty coordinates.
                query.invoke(model, null, false);
            } catch (Throwable error) { call.orders.completeExceptionally(error); }
        });
    }

    private static <T> T await(CompletableFuture<T> future, Call call, long limit) throws Exception {
        long deadline = Math.min(call.deadline, SystemClock.elapsedRealtime() + limit);
        try {
            for (;;) {
                call.requireCurrent();
                // A previous slot may consume the wave's wait budget. Preserve other slots
                // which have already completed instead of losing them to that slot's timeout.
                if (future.isDone()) return future.get(0, TimeUnit.MILLISECONDS);
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new TimeoutException("Native ticket read deadline");
                try { return future.get(Math.min(remaining, 100), TimeUnit.MILLISECONDS); }
                catch (TimeoutException timeout) { if (SystemClock.elapsedRealtime() >= deadline) throw timeout; }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void finish(Call call) {
        final Object model;
        synchronized (call) {
            call.ended.set(true);
            model = call.model;
            call.model = null;
            if (model != null) CALLS.remove(model);
        }
        if (model != null) MAIN.post(() -> cleanModel(model));
    }

    private static synchronized void resolve() throws Exception {
        if (resolved) return;
        if (resolveFailure != null) throw resolveFailure;
        try { resolveNative(); }
        catch (Exception error) {
            // A callback hook may already have been installed. Fail closed for this process
            // rather than registering it a second time on each client retry.
            resolveFailure = error;
            throw error;
        }
    }

    private static void resolveNative() throws Exception {
        if (module == null || loader == null) throw new IllegalStateException("Native refresh bootstrap missing");
        viewModel = load("com.finshell.doubleclick.tickets.TicketOrderViewModel");
        Class<?> authResponse = load("com.finshell.doubleclick.tickets.auth.domian.MovieTicketAuthRspVO");
        response = load("com.finshell.doubleclick.tickets.groupon.domain.QueryDouyinOrderRspVO");
        Class<?> location = load("com.finshell.location.LocationInfoEntity");
        auth = WalletDexResolver.auth(loader, viewModel);
        query = WalletDexResolver.query(loader, viewModel, location);
        cleanup = viewModel.getDeclaredMethod("onCleared"); cleanup.setAccessible(true);
        if (cleanup.getReturnType() != void.class) throw new NoSuchMethodException("Native cleanup changed");
        orderIds = WalletDexResolver.orderIds(loader, viewModel, query);
        responseOrders = response.getDeclaredMethod("getTicketInfoDetailList");
        if (responseOrders.getReturnType() != List.class) throw new NoSuchMethodException("Native orders changed");
        Class<?> detail = load("com.nearme.wallet.dcp.TicketInfoDetail");
        orderId = detail.getDeclaredMethod("getOrderId");
        if (orderId.getReturnType() != String.class) throw new NoSuchMethodException("Native order ID changed");
        ticketCode = detail.getDeclaredMethod("getTicketCode");
        ticketCodeData = detail.getDeclaredMethod("getTicketCodeData");
        qrCodeUrl = detail.getDeclaredMethod("getQrcodeUrl");
        extraInfo = detail.getDeclaredMethod("getExtraInfo");
        orderStatus = detail.getDeclaredMethod("getStatus");
        for (Method method : new Method[]{ticketCode, ticketCodeData, qrCodeUrl, extraInfo})
            if (method.getReturnType() != String.class || Modifier.isStatic(method.getModifiers()))
                throw new NoSuchMethodException("Native code field changed");
        if (orderStatus.getReturnType() != Integer.class)
            throw new NoSuchMethodException("Native order status changed");
        Method bindStatus = authResponse.getDeclaredMethod("getBindStatus");
        if (bindStatus.getReturnType() != Boolean.class) throw new NoSuchMethodException("Native binding changed");
        observeCallback(authResponse, (call, value) -> {
            call.requireCurrent();
            call.authorized.complete(value != null && Boolean.TRUE.equals(bindStatus.invoke(value)));
        }, true);
        observeCallback(response, (call, value) -> {
            call.requireCurrent();
            Object data = value == null ? null : responseOrders.invoke(value);
            if (!(data instanceof List)) throw new IllegalStateException("原钱包未返回有效票券列表");
            call.orders.complete(new ArrayList<>((List<?>) data));
        }, false);
        resolved = true;
    }

    private interface ResponseHandler { void handle(Call call, Object response) throws Exception; }

    private static void observeCallback(Class<?> resultType, ResponseHandler handler, boolean isAuth)
            throws Exception {
        Method success = null;
        Field owner = null;
        Set<Class<?>> candidates = Collections.emptySet();
        int ownerCandidates = 0;
        try {
            candidates = callbackCandidates();
            for (Class<?> nested : candidates) {
                Field candidateOwner = null;
                for (Field field : nested.getDeclaredFields()) {
                    if (field.getType() == viewModel && !Modifier.isStatic(field.getModifiers())) {
                        if (candidateOwner != null) throw new NoSuchFieldException("Ambiguous native callback owner");
                        field.setAccessible(true); candidateOwner = field;
                    }
                }
                if (candidateOwner == null) continue;
                ownerCandidates++;
                for (Method method : nested.getDeclaredMethods()) {
                    if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())
                            || !Arrays.equals(method.getParameterTypes(), new Class<?>[]{int.class, int.class,
                            Object.class, resultType})) continue;
                    if (success != null) throw new NoSuchMethodException("Ambiguous native response callback");
                    owner = candidateOwner;
                    success = method;
                }
            }
        } catch (Exception | LinkageError changed) {
            success = null; owner = null;
        }
        if (success == null || owner == null) {
            Log.w(TAG, "Callback signature missing for " + resultType.getName()
                    + "; dexCandidates=" + candidates.size() + "; vmOwners=" + ownerCandidates);
            success = WalletDexResolver.callback(loader, viewModel, resultType);
            owner = WalletDexResolver.uniqueOwner(success.getDeclaringClass(), viewModel);
            if (owner == null) throw new NoSuchFieldException("Native response callback owner unavailable");
        }
        Method failure = success.getDeclaringClass().getDeclaredMethod("onTransactionFailedUI",
                int.class, int.class, Object.class, Object.class);
        if (failure.getReturnType() != void.class || Modifier.isStatic(failure.getModifiers()))
            throw new NoSuchMethodException("Native response failure callback changed");
        Log.i(TAG, "Native callback resolved: " + success.toGenericString());
        final Field outer = owner;
        module.hook(success).intercept(chain -> {
            Object ownerModel = outer.get(chain.getThisObject());
            Call call = CALLS.get(ownerModel);
            if (call == null) return isOwned(ownerModel) ? null : chain.proceed();
            try { handler.handle(call, chain.getArg(3)); }
            catch (Throwable error) { call.fail(error); }
            // This callback belongs only to our private VM. No app UI or database/cache write is needed.
            // Original UI instances always take chain.proceed() above.
            return null;
        });
        module.hook(failure).intercept(chain -> {
            Object ownerModel = outer.get(chain.getThisObject());
            Call call = CALLS.get(ownerModel);
            if (call == null) return isOwned(ownerModel) ? null : chain.proceed();
            call.fail(new IllegalStateException(isAuth ? "原钱包授权状态查询失败" : "原钱包实时票券查询失败"));
            return null;
        });
    }

    private static Set<Class<?>> callbackCandidates() throws Exception {
        Set<Class<?>> candidates = new LinkedHashSet<>(Arrays.asList(viewModel.getDeclaredClasses()));
        // Shrunk APKs may omit InnerClasses/EnclosingClass annotations. Enumerate the actual
        // loaded wallet DEX instead; class names are candidates only, method/owner signatures decide.
        Field pathList = BaseDexClassLoader.class.getDeclaredField("pathList");
        pathList.setAccessible(true);
        String packagePrefix = viewModel.getPackage().getName() + ".";
        int dexCount = 0;
        for (ClassLoader current = viewModel.getClassLoader(); current != null; current = current.getParent()) {
            if (!(current instanceof BaseDexClassLoader)) continue;
            Object paths = pathList.get(current);
            Field elementsField = paths.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(paths);
            for (Object element : elements) {
                Field dexField = element.getClass().getDeclaredField("dexFile");
                dexField.setAccessible(true);
                Object dex = dexField.get(element);
                if (!(dex instanceof DexFile)) continue;
                dexCount++;
                Enumeration<String> entries = ((DexFile) dex).entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    if (!name.startsWith(packagePrefix)) continue;
                    try { candidates.add(Class.forName(name, false, viewModel.getClassLoader())); }
                    catch (ClassNotFoundException | LinkageError ignored) { }
                }
            }
        }
        Log.i(TAG, "Native callback DEX scan: dex=" + dexCount + "; candidates=" + candidates.size());
        return candidates;
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static void cleanModel(Object model) {
        try { cleanup.invoke(model); } catch (Throwable ignored) { }
    }

    private static synchronized void rememberOwned(Object model) {
        purgeOwned();
        OWNED.add(new IdentityReference(model, OWNED_QUEUE));
    }

    private static synchronized boolean isOwned(Object model) {
        purgeOwned();
        return model != null && OWNED.contains(new IdentityReference(model, null));
    }

    private static void purgeOwned() {
        IdentityReference collected;
        while ((collected = (IdentityReference) OWNED_QUEUE.poll()) != null) OWNED.remove(collected);
    }

    private static final class IdentityReference extends WeakReference<Object> {
        private final int hash;
        IdentityReference(Object value, ReferenceQueue<Object> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityReference)) return false;
            Object value = get();
            return value != null && value == ((IdentityReference) other).get();
        }
    }

    private static final class Call {
        final CompletableFuture<Boolean> authorized = new CompletableFuture<>();
        final CompletableFuture<List<?>> orders = new CompletableFuture<>();
        final AtomicBoolean ended = new AtomicBoolean();
        final AtomicBoolean cancelled;
        final AccountVerifier account;
        final long deadline;
        volatile Object model;
        Call(AtomicBoolean cancelled, AccountVerifier account, long deadline) {
            this.cancelled = cancelled; this.account = account; this.deadline = deadline;
        }
        void requireCurrent() throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Native read interrupted");
            if (ended.get() || cancelled.get() || !account.isCurrent()) {
                throw new IllegalStateException("钱包账号已变化或查询已结束");
            }
        }
        void fail(Throwable error) { authorized.completeExceptionally(error); orders.completeExceptionally(error); }
    }
}
