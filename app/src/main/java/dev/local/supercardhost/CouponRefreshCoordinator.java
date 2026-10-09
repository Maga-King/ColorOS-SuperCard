package dev.local.supercardhost;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/** One account-confirmed coupon snapshot shared by the whole card-pack session. */
public final class CouponRefreshCoordinator {
    public static final String CHANGED = "dev.local.supercardhost.WALLET_COUPONS_CHANGED";
    private static final String TAG = "SuperCardCouponRefresh";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SuperCardWalletRefresh");
        thread.setDaemon(true); return thread;
    });
    private static final Object LOCK = new Object();
    private static final Map<Object, Runnable> LISTENERS = new WeakHashMap<>();
    private static final Set<ClassLoader> WINDOW_HOOKS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static Application host;
    private static boolean installed, visible;
    private static long generation;
    private static JSONObject snapshot;
    private static String accountKey;
    private static String state = "idle";
    private static boolean workerRunning;
    private static Request pending;
    private record Request(Application application, String reason, long generation) { }
    private static final Runnable CHANGED_REFRESH = () -> {
        Application app;
        synchronized (LOCK) { app = visible ? host : null; }
        if (app != null) refresh(app, "wallet_changed");
    };

    private CouponRefreshCoordinator() { }

    /** Covers real gestures and every original card-window entry. */
    public static void install(XposedModule module, Application application, ClassLoader cardLoader)
            throws Exception {
        install(application);
        synchronized (LOCK) {
            if (WINDOW_HOOKS.contains(cardLoader)) return;
            Class<?> window = cardLoader.loadClass("com.vivo.card.framework.window.CardWindowManager");
            Method stateMethod = window.getDeclaredMethod("getWindowState"); stateMethod.setAccessible(true);
            module.hook(window.getDeclaredMethod("showWindow")).intercept(chain -> {
                int before = -1;
                try { before = (Integer) stateMethod.invoke(chain.getThisObject()); }
                catch (Throwable error) { Log.w(TAG, "Cannot read card-window state", error); }
                Object result = chain.proceed();
                try {
                    if (before != 4 && (Integer) stateMethod.invoke(chain.getThisObject()) == 4)
                        onCardPackShown(application);
                } catch (Throwable error) { Log.w(TAG, "Cannot schedule card-pack wallet refresh", error); }
                return result;
            });
            module.hook(window.getDeclaredMethod("hideWindow")).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if ((Integer) stateMethod.invoke(chain.getThisObject()) == 6) onCardPackHidden();
                } catch (Throwable error) { Log.w(TAG, "Cannot release card-pack wallet refresh", error); }
                return result;
            });
            module.hook(window.getDeclaredMethod("destroyWindow")).intercept(chain -> {
                Object result = chain.proceed();
                try { onCardPackHidden(); }
                catch (Throwable error) { Log.w(TAG, "Cannot release destroyed wallet session", error); }
                return result;
            });
            WINDOW_HOOKS.add(cardLoader);
        }
    }

    public static void install(Application application) {
        synchronized (LOCK) {
            host = application;
            if (installed) return;
        }
        try {
            IntentFilter filter = new IntentFilter(CHANGED);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(USER_SWITCHED);
            application.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (CHANGED.equals(action)) {
                        try {
                            int uid = context.getPackageManager().getPackageUid(WalletCouponClient.PACKAGE, 0);
                            if (getSentFromUid() != uid || !WalletCouponClient.PACKAGE.equals(getSentFromPackage())) return;
                        } catch (Exception unavailable) { return; }
                        // Invalidate immediately, then debounce the actual read. Account
                        // changes cannot leave an older user's rendered source actionable.
                        invalidate();
                        MAIN.removeCallbacks(CHANGED_REFRESH);
                        MAIN.postDelayed(CHANGED_REFRESH, 350);
                    } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        synchronized (LOCK) { if (!visible) return; }
                        refresh(application, "unlocked");
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action) || USER_SWITCHED.equals(action)) {
                        invalidate();
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
            synchronized (LOCK) { installed = true; }
        } catch (Throwable error) { Log.e(TAG, "Cannot register wallet refresh notifications", error); }
    }

    /** Invoke once for every whole card-pack opening, even when another card is selected. */
    public static void onCardPackShown(Application application) {
        install(application);
        synchronized (LOCK) { visible = true; }
        refresh(application, "cardpack_show");
    }

    public static void onCardPackHidden() {
        synchronized (LOCK) { visible = false; }
        MAIN.removeCallbacks(CHANGED_REFRESH);
        invalidate();
    }

    /** Original coupon onShowing also works if the outer show integration is absent. */
    public static void ensureRefresh(Application application) {
        install(application);
        synchronized (LOCK) {
            // Original onShowing runs inside showWindow. Let its after-hook start
            // the only query, rather than launch then cancel one during construction.
            if (visible || !WINDOW_HOOKS.isEmpty()) return;
            visible = true;
        }
        refresh(application, "coupon_show");
    }

    public static void subscribe(Object owner, Runnable listener) {
        synchronized (LOCK) { LISTENERS.put(owner, listener); }
    }

    public static void unsubscribe(Object owner) {
        synchronized (LOCK) { LISTENERS.remove(owner); }
    }

    /** Null means pending/unavailable/locked, not a successful empty wallet. */
    public static JSONObject snapshot(Application application) {
        if (locked(application)) return null;
        synchronized (LOCK) { return visible ? snapshot : null; }
    }

    public static boolean isAccountCurrent(Application application, String expected) {
        if (expected == null || expected.isEmpty() || locked(application)) return false;
        synchronized (LOCK) {
            return visible && snapshot != null && expected.equals(accountKey);
        }
    }

    public static String state(Application application) {
        if (locked(application)) return "locked";
        synchronized (LOCK) { return state; }
    }

    public static void reportAdapterFailure(String expectedAccount) {
        synchronized (LOCK) {
            if (expectedAccount == null || !expectedAccount.equals(accountKey)) return;
            snapshot = null; accountKey = null;
            state = "unavailable";
        }
        CouponImageLoader.invalidateAll();
        notifyListeners();
    }

    private static void refresh(Application application, String reason) {
        final long token = invalidate();
        if (locked(application)) {
            Log.i(TAG, "Deferred wallet refresh while keyguard is locked"); return;
        }
        synchronized (LOCK) {
            if (!visible || generation != token) return;
            state = "loading";
            pending = new Request(application, reason, token);
            if (!workerRunning) {
                workerRunning = true;
                WORK.execute(CouponRefreshCoordinator::drainRequests);
            }
        }
    }

    private static void drainRequests() {
        while (true) {
            Request request;
            synchronized (LOCK) {
                request = pending; pending = null;
                if (request == null) { workerRunning = false; return; }
            }
            // A broadcast already sent to the wallet cannot be cancelled there.
            // Finish waiting for it before issuing the newest coalesced request.
            try { readSnapshot(request); }
            catch (Throwable error) { Log.w(TAG, "Wallet refresh worker failed", error); }
        }
    }

    private static void readSnapshot(Request request) {
        Application application = request.application();
        long token = request.generation();
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (!current(application, token)) return;
            try {
                Bundle response = WalletCouponClient.querySnapshot(application);
                if (!current(application, token)) return;
                String account = response.getString("accountKey", "");
                if (!account.matches("[0-9a-fA-F]{64}"))
                    throw new IllegalStateException("Wallet account confirmation unavailable");
                JSONObject data = new JSONObject(response.getString("data"));
                data.put("_walletAccountKey", account);
                data.put("_unsupportedCount", response.getInt("unsupportedCount", 0));
                boolean incomplete = response.getBoolean("refreshIncomplete", false);
                synchronized (LOCK) {
                    if (!visible || generation != token) return;
                    snapshot = data; accountKey = account;
                    state = incomplete ? (attempt < 3 ? "updating_codes" : "codes_unavailable")
                            : response.getBoolean("partial", false) ? "partial" : "ready";
                }
                Log.i(TAG, "Confirmed wallet snapshot reason=" + request.reason() + " attempt=" + attempt
                        + " incomplete=" + incomplete);
                notifyListeners();
                if (!incomplete || attempt == 3) return;
            } catch (Throwable error) {
                if (!current(application, token)) return;
                if (error instanceof WalletCouponClient.QueryFailure failure && failure.invalidatesAccount()) {
                    synchronized (LOCK) {
                        if (!visible || generation != token) return;
                        snapshot = null; accountKey = null;
                        state = "unavailable";
                    }
                    CouponImageLoader.invalidateAll();
                    notifyListeners();
                }
                Log.w(TAG, "Read-only wallet refresh attempt=" + attempt + " failed="
                        + error.getClass().getSimpleName());
            }
            if (attempt < 3) {
                try { Thread.sleep(attempt * 400L); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return; }
            }
        }
        synchronized (LOCK) {
            if (generation != token || !visible) return;
            // A later failed query must not erase tickets already confirmed during
            // this opening. Lock/account changes still invalidate the whole session.
            state = snapshot == null ? "unavailable" : "codes_unavailable";
        }
        notifyListeners();
    }

    private static long invalidate() {
        long token;
        synchronized (LOCK) {
            token = ++generation;
            snapshot = null; accountKey = null;
            state = "loading";
            pending = null;
        }
        CouponImageLoader.invalidateAll();
        notifyListeners();
        return token;
    }

    private static boolean current(Application application, long token) {
        if (Thread.currentThread().isInterrupted() || locked(application)) return false;
        synchronized (LOCK) { return visible && generation == token; }
    }

    private static boolean locked(Application application) {
        try {
            KeyguardManager manager = application.getSystemService(KeyguardManager.class);
            return manager == null || manager.isDeviceLocked() || manager.isKeyguardLocked();
        } catch (Throwable unavailable) { return true; }
    }

    private static void notifyListeners() {
        MAIN.post(() -> {
            ArrayList<Runnable> callbacks;
            synchronized (LOCK) {
                if (!visible) return;
                callbacks = new ArrayList<>(LISTENERS.values());
            }
            for (Runnable callback : callbacks) {
                try { callback.run(); }
                catch (Throwable error) { Log.w(TAG, "Coupon UI refresh unavailable", error); }
            }
        });
    }
}
