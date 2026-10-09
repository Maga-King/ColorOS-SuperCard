package dev.local.supercardhost;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/** Reads the original account-scoped TICKET inventory, without updating the wallet cache. */
public final class WalletTicketDiscovery {
    private static final String TAG = "SuperCardWalletDiscovery";
    private static final long TIMEOUT_MS = 3_500;
    private static final Map<Object, Call> CALLS = Collections.synchronizedMap(new IdentityHashMap<>());
    // Cancelling the Rx subscription does not necessarily cancel its HTTP request. A late
    // callback must still skip TicketsRepo's database/preferences writes, until its repo is GC'd.
    private static final ReferenceQueue<Object> OWNED_QUEUE = new ReferenceQueue<>();
    private static final Set<IdentityReference> OWNED = new HashSet<>();

    private static XposedModule module;
    private static ClassLoader loader;
    private static Constructor<?> constructor;
    private static Method loadTickets, timeout, toFuture, tabType, tabParents, parentType, parentTickets;
    private static Class<?> parentClass;
    private static boolean resolved;
    private static Exception resolveFailure;

    private WalletTicketDiscovery() { }

    public static synchronized void install(XposedModule source, ClassLoader target) {
        if (source == null || target == null) throw new IllegalArgumentException("Missing wallet loader");
        if (loader != null && loader != target) {
            throw new IllegalStateException("Wallet discovery loader already configured");
        }
        module = source;
        loader = target;
    }

    /**
     * Worker-thread only. Returns native CardPackageRspVo objects with their original
     * cardType/bizId and complete TicketInfoDetail lists. No database fallback is performed.
     */
    public static List<?> discover(Application application, AtomicBoolean cancelled,
            WalletNativeTicketRefresh.AccountVerifier account) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Worker required");
        if (application == null || !"com.finshell.wallet".equals(application.getPackageName())) {
            throw new IllegalArgumentException("Wallet application required");
        }
        if (cancelled == null || account == null) throw new IllegalArgumentException("Account guard required");
        resolve();
        Call call = new Call(cancelled, account);
        call.requireCurrent();
        Object repository = invokeConstructor();
        rememberOwned(repository);
        CALLS.put(repository, call);
        Future<?> future = null;
        long started = SystemClock.elapsedRealtime();
        try {
            call.requireCurrent();
            // This is the native f() pipeline: token synchronization, native NFC/device
            // parameters, original authenticated CardPackageV4Request, and original response.
            Object observable = invoke(loadTickets, repository);
            Object bounded = invoke(timeout, observable, TIMEOUT_MS, TimeUnit.MILLISECONDS);
            call.requireCurrent();
            Object pending = invoke(toFuture, bounded);
            if (!(pending instanceof Future<?>)) throw new IllegalStateException("Native Rx future changed");
            future = (Future<?>) pending;
            Object value;
            for (;;) {
                call.requireCurrent();
                long remaining = TIMEOUT_MS - (SystemClock.elapsedRealtime() - started);
                if (remaining <= 0) throw new TimeoutException("原钱包票券列表读取超时");
                try {
                    value = future.get(Math.min(remaining, 100), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException waitAgain) {
                    // Check cancellation/account changes while waiting. Finally cancels the
                    // Future, which disposes the original Rx subscription upstream.
                }
            }
            call.requireCurrent();
            if (call.invalidResponse.get() || !call.sawTicketTab.get()) {
                // The original pipeline emits an empty list even when TICKET was missing.
                // Do not turn a malformed/unexpected response into a confirmed empty account.
                throw new IllegalStateException("原钱包未返回完整票券分组");
            }
            if (!(value instanceof List<?>)) throw new IllegalStateException("Native ticket inventory changed");
            List<?> result = new ArrayList<>((List<?>) value);
            int tickets = 0, movieGroups = 0, douyinGroups = 0, otherGroups = 0;
            for (Object parent : result) {
                if (!parentClass.isInstance(parent)) throw new IllegalStateException("Native ticket parent changed");
                Object type = invoke(parentType, parent);
                if ("31".equals(type)) movieGroups++;
                else if ("32".equals(type)) douyinGroups++;
                else otherGroups++;
                Object details = invoke(parentTickets, parent);
                if (details != null && !(details instanceof List<?>)) {
                    throw new IllegalStateException("Native ticket details changed");
                }
                if (details instanceof List<?>) tickets += ((List<?>) details).size();
            }
            call.requireCurrent();
            Log.i(TAG, "Native inventory completed: groups=" + result.size() + "; tickets=" + tickets
                    + "; movieGroups=" + movieGroups + "; douyinGroups=" + douyinGroups
                    + "; otherGroups=" + otherGroups);
            return result;
        } finally {
            call.ended.set(true);
            CALLS.remove(repository);
            if (future != null) future.cancel(true);
            // Keep only the weak identity tombstone; never retain account data after completion.
        }
    }

    private static synchronized void resolve() throws Exception {
        if (resolved) return;
        if (resolveFailure != null) throw resolveFailure;
        try {
            if (module == null || loader == null) throw new IllegalStateException("Discovery bootstrap missing");
            Class<?> repository = load("com.nearme.pay.business.cardpackage.tickets.TicketsRepo");
            Class<?> observable = load("io.reactivex.Observable");
            Class<?> tab = load("com.nearme.pay.domain.rsp.CardTabTabRspVO");
            parentClass = load("com.nearme.common.domain.rsp.CardPackageRspVo");
            constructor = repository.getDeclaredConstructor();
            constructor.setAccessible(true);
            loadTickets = WalletDexResolver.inventory(loader, repository, observable);
            Method save = WalletDexResolver.inventory(loader, repository, void.class, tab);
            timeout = observable.getMethod("timeout", long.class, TimeUnit.class);
            toFuture = observable.getMethod("toFuture");
            tabType = tab.getDeclaredMethod("getTabType");
            tabParents = tab.getDeclaredMethod("getCardPackageRspVoList");
            parentType = parentClass.getDeclaredMethod("getCardType");
            parentTickets = parentClass.getDeclaredMethod("getTicketList");
            if (timeout.getReturnType() != observable || toFuture.getReturnType() != Future.class
                    || tabType.getReturnType() != String.class || tabParents.getReturnType() != List.class
                    || parentType.getReturnType() != String.class || parentTickets.getReturnType() != List.class) {
                throw new NoSuchMethodException("Native discovery signatures changed");
            }
            // Only our private repositories skip j(). Wallet UI repositories always proceed.
            // Install last so a failed reflection pass cannot leave a partial set of hooks.
            module.hook(save).intercept(chain -> {
                Object owner = chain.getThisObject();
                if (!isOwned(owner)) return chain.proceed();
                Call call = CALLS.get(owner);
                if (call != null && !call.ended.get() && !call.cancelled.get()) {
                    try {
                        Object responseTab = chain.getArg(0);
                        boolean valid = "TICKET".equals(tabType.invoke(responseTab))
                                && tabParents.invoke(responseTab) instanceof List<?>;
                        if (valid) call.sawTicketTab.set(true);
                        else call.invalidResponse.set(true);
                    } catch (Throwable ignored) {
                        call.invalidResponse.set(true);
                    }
                }
                // Also suppress late calls after timeout/account changes. In particular, do
                // not clear parent.ticketList, or write detached rows with nullable cardType.
                return null;
            });
            resolved = true;
            Log.i(TAG, "Original ticket inventory pipeline resolved");
        } catch (Exception failure) {
            resolveFailure = failure;
            throw failure;
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Object invokeConstructor() throws Exception {
        try { return constructor.newInstance(); }
        catch (InvocationTargetException failure) { throw nativeFailure(failure); }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) { throw nativeFailure(failure); }
    }

    private static Exception nativeFailure(InvocationTargetException failure) {
        // No response body, URL, account, QR payload, or raw native message is logged here.
        return new Exception("原钱包票券列表调用失败", failure.getCause());
    }

    private static synchronized void rememberOwned(Object repository) {
        purgeOwned();
        OWNED.add(new IdentityReference(repository, OWNED_QUEUE));
    }

    private static synchronized boolean isOwned(Object repository) {
        purgeOwned();
        return repository != null && OWNED.contains(new IdentityReference(repository, null));
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
        final AtomicBoolean cancelled;
        final AtomicBoolean ended = new AtomicBoolean();
        final AtomicBoolean sawTicketTab = new AtomicBoolean();
        final AtomicBoolean invalidResponse = new AtomicBoolean();
        final WalletNativeTicketRefresh.AccountVerifier account;

        Call(AtomicBoolean cancelled, WalletNativeTicketRefresh.AccountVerifier account) {
            this.cancelled = cancelled;
            this.account = account;
        }

        void requireCurrent() throws Exception {
            if (ended.get() || cancelled.get() || !account.isCurrent()) {
                throw new IllegalStateException("钱包账号已变化或票券查询已结束");
            }
        }
    }
}
