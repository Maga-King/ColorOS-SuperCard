package dev.local.supercardhost;

import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Queries the wallet process under its own account and database identity. */
public final class WalletCouponClient {
    public static final String PACKAGE = "com.finshell.wallet";
    public static final String ACTION = "dev.local.supercardhost.WALLET_COUPONS";
    private static final String BOOTSTRAP = "com.finshell.wallet.card.event.provider";
    private static final Handler RESPONSES;
    static {
        HandlerThread thread = new HandlerThread("SuperCardWalletResults");
        thread.start();
        RESPONSES = new Handler(thread.getLooper());
    }

    private WalletCouponClient() { }

    static final class QueryFailure extends Exception {
        final String reason;
        QueryFailure(String reason, String message) { super(message); this.reason = reason; }
        boolean invalidatesAccount() {
            return java.util.Set.of("not_logged_in", "account_changed", "account_unavailable",
                    "service_disabled", "locked").contains(reason);
        }
    }

    /** Official exported wallet card-package management entry, also used by settings. */
    public static Intent managementIntent(Context host) {
        try {
            ComponentName component = new ComponentName(PACKAGE,
                    "com.nearme.pay.business.cardpackage.systemsearchindex.SearchRouterActivity");
            ActivityInfo info = host.getPackageManager().getActivityInfo(component, 0);
            if (!info.enabled || !info.exported || !info.applicationInfo.enabled) return null;
            if (info.permission != null && host.checkSelfPermission(info.permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
            return new Intent("com.finshell.wallet.CARD_PACKAGE").setComponent(component)
                    .setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception unavailable) { return null; }
    }

    /** Called on the original coupon loader's worker, never on the UI thread. */
    public static JSONObject query(Context host) throws Exception {
        return new JSONObject(querySnapshot(host).getString("data"));
    }

    /** Includes the wallet's account hash, needed before cached data can be displayed. */
    public static Bundle querySnapshot(Context host) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("钱包查询必须在后台执行");
        requireUnlocked(host);
        ProviderInfo info = host.getPackageManager().resolveContentProvider(BOOTSTRAP, 0);
        if (info == null || !PACKAGE.equals(info.packageName) || !info.enabled || !info.exported)
            throw new IllegalStateException("钱包后台入口不可用");
        // The public Seedling provider's onCreate only returns true. Acquire it to
        // start the actual wallet process, without querying cards or sending events.
        // Hold the client through the handshake; providers can publish before the
        // Application lifecycle hook registers our dynamic receiver.
        try (ContentProviderClient provider = host.getContentResolver()
                .acquireContentProviderClient(BOOTSTRAP)) {
            if (provider == null) throw new IllegalStateException("钱包后台暂时无法启动");
            // Native inventory plus per-order QR reads can use 15.5s; the receiver
            // finishes by 18s. Each coordinator attempt includes this warm-up.
            // Allow process bootstrap/broadcast dispatch margin without overlapping
            // another request while the wallet's goAsync operation is still alive.
            long deadline = SystemClock.elapsedRealtime() + 22_000;
            while (true) {
                requireUnlocked(host);
                Bundle result = request(host, Math.max(1, deadline - SystemClock.elapsedRealtime()));
                if (result != null && !result.isEmpty()) {
                    if (!"ok".equals(result.getString("status")))
                        throw new QueryFailure(result.getString("reason", "native_unavailable"),
                                result.getString("error", "钱包券码暂不可用"));
                    String data = result.getString("data");
                    if (data == null || data.length() > 800_000)
                        throw new IllegalStateException("钱包券码响应无效");
                    JSONObject parsed = new JSONObject(data);
                    for (String key : new String[]{"films", "performances", "coupons"})
                        if (parsed.optJSONArray(key) == null)
                            throw new IllegalStateException("钱包券码响应格式无效");
                    return result;
                }
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new IllegalStateException("钱包券码接入尚未就绪");
                Thread.sleep(150);
            }
        }
    }

    private static void requireUnlocked(Context host) {
        KeyguardManager manager = host.getSystemService(KeyguardManager.class);
        if (manager == null || manager.isDeviceLocked() || manager.isKeyguardLocked())
            throw new IllegalStateException("解锁后才能读取钱包券码");
    }

    private static Bundle request(Context host, long timeout) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bundle> reply = new AtomicReference<>();
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        Intent intent = new Intent(ACTION).setPackage(PACKAGE).putExtra("op", "query");
        host.sendOrderedBroadcast(intent, null, options.toBundle(), new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent original) {
                Bundle data = getResultExtras(false);
                reply.set(data == null ? null : new Bundle(data));
                latch.countDown();
            }
        }, RESPONSES, 0, null, null);
        if (!latch.await(timeout, TimeUnit.MILLISECONDS))
            throw new IllegalStateException("等待钱包券码超时");
        return reply.get();
    }
}
