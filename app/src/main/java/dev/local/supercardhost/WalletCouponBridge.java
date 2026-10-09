package dev.local.supercardhost;

import android.app.Activity;
import android.app.Application;
import android.app.BroadcastOptions;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedModule;

/** Read-only adapter for the wallet's native authenticated ticket inventory and details. */
public final class WalletCouponBridge {
    public static final String ACTION = "dev.local.supercardhost.WALLET_COUPONS";
    public static final String PACKAGE = "com.finshell.wallet";
    public static final String ACTION_CHANGED = "dev.local.supercardhost.WALLET_COUPONS_CHANGED";
    private static final String MODULE = "dev.local.supercardhost";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ROUTER = "com.nearme.common.router.RouterActivity";
    private static final String TAG = "SuperCardWalletCoupons";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SuperCard-wallet-read");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final int MAX_ITEMS = 100;
    // Keep the ordered-broadcast result well below Binder's transaction budget.
    private static final int MAX_JSON_CHARS = 180_000;
    private static volatile ClassLoader loader;
    private static volatile NativeReader reader;
    private static volatile XposedModule module;
    private static volatile Application application;
    private static final List<Object> ACCOUNT_OBSERVERS = new ArrayList<>();
    private static final Runnable NOTIFY_CHANGED = () -> {
        Application app = application;
        if (app == null) return;
        try {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            app.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(SYSTEM_UI), null, options.toBundle());
        } catch (Throwable error) {
            Log.w(TAG, "Wallet change notification unavailable: " + error.getClass().getSimpleName());
        }
    };

    private WalletCouponBridge() { }

    public static void install(XposedModule module, ClassLoader target) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        loader = target;
        WalletCouponBridge.module = module;
        WalletNativeTicketRefresh.install(module, target);
        WalletTicketDiscovery.install(module, target);
        try {
            Method onCreate = Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) instanceof Application) register((Application) chain.getArg(0));
                return result;
            });
            try {
                Class<?> thread = Class.forName("android.app.ActivityThread");
                Method current = thread.getDeclaredMethod("currentApplication");
                current.setAccessible(true);
                Object app = current.invoke(null);
                if (app instanceof Application) MAIN.post(() -> register((Application) app));
            } catch (Throwable ignored) { }
        } catch (Throwable error) {
            INSTALLED.set(false);
            Log.e(TAG, "Wallet receiver bootstrap failed: " + error.getClass().getSimpleName());
        }
    }

    private static void register(Application app) {
        // :temp/:nfchost and other wallet processes must not answer the same broadcast.
        if (!PACKAGE.equals(app.getPackageName()) || !PACKAGE.equals(Application.getProcessName())
                || !REGISTERED.compareAndSet(false, true)) return;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    boolean authorized = false;
                    try {
                        int uid = getSentFromUid();
                        String sender = getSentFromPackage();
                        authorized = SYSTEM_UI.equals(sender)
                                && uid == app.getPackageManager().getPackageUid(SYSTEM_UI, 0)
                                || MODULE.equals(sender)
                                && uid == app.getPackageManager().getPackageUid(MODULE, 0);
                    } catch (Throwable ignored) { }
                    if (!authorized || !isOrderedBroadcast() || intent == null
                            || !ACTION.equals(intent.getAction()) || !PACKAGE.equals(intent.getPackage())) {
                        Log.w(TAG, "Rejected unauthenticated wallet request");
                        return;
                    }
                    String op = intent.getStringExtra("op");
                    if (op != null && !"query".equals(op) && !"check".equals(op)) {
                        setResultCode(Activity.RESULT_CANCELED);
                        setResultExtras(unavailable("unsupported_operation", "仅支持读取真实票券"));
                        return;
                    }
                    if (!BUSY.compareAndSet(false, true)) {
                        setResultCode(Activity.RESULT_CANCELED);
                        setResultExtras(unavailable("busy", "钱包查询仍在进行"));
                        return;
                    }
                    Request request = new Request(goAsync());
                    // Background ordered broadcast: one inventory read followed by
                    // bounded per-order native QR reads, never a visible Activity.
                    MAIN.postDelayed(() -> request.finish(unavailable("timeout", "钱包查询未及时完成")), 18_000);
                    WORK.execute(() -> begin(app, request, "check".equals(op)));
                }
            }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
            application = app;
            observeNativeChanges();
            Log.i(TAG, "Authenticated current-account wallet receiver registered");
        } catch (Throwable error) {
            REGISTERED.set(false);
            Log.e(TAG, "Wallet receiver registration failed: " + error.getClass().getSimpleName());
        }
    }

    private static void begin(Application app, Request request, boolean checkOnly) {
        try {
            requireUnlocked(app);
            NativeReader nativeReader = reader;
            if (nativeReader == null) reader = nativeReader = new NativeReader();
            final NativeReader resolved = nativeReader;
            resolved.requireAgreement();
            checkLogin(resolved, request, () -> WORK.execute(() -> {
                try {
                    if (request.finished.get()) return;
                    requireUnlocked(app);
                    resolved.requireAgreement();
                    String oid = resolved.currentOid();
                    if (oid.isEmpty()) throw new NotReady("not_logged_in", "钱包当前账号未就绪");
                    Bundle result = checkOnly ? ok(emptyData(), 0, 0, false)
                            : resolved.query(app, oid, request);
                    result.putString("accountKey", sha256(oid));
                    if (!oid.equals(resolved.currentOid())) {
                        throw new NotReady("account_changed", "钱包账号已变化，请重新查询");
                    }
                    // Reconfirm native login before returning any account-bound data.
                    checkLogin(resolved, request, () -> {
                        try {
                            requireUnlocked(app);
                            resolved.requireAgreement();
                            if (!oid.equals(resolved.currentOid())) {
                                throw new NotReady("account_changed", "钱包账号已变化，请重新查询");
                            }
                            request.finish(result);
                        } catch (Throwable error) { fail(request, error); }
                    });
                } catch (Throwable error) { fail(request, error); }
            }));
        } catch (Throwable error) { fail(request, error); }
    }

    private static void changed() {
        MAIN.removeCallbacks(NOTIFY_CHANGED);
        MAIN.postDelayed(NOTIFY_CHANGED, 600);
    }

    private static void observeNativeChanges() {
        try {
            Class<?> dao = load("com.nearme.wallet.room.dao.DbTicketOrderInfoDao_Impl");
            Set<String> mutations = new HashSet<>(Arrays.asList("insertOrReplace", "insertOrReplaceInTx",
                    "update", "delete", "deleteAll", "deleteByBelongBizId", "deleteByOidHashAndBelongBizId",
                    "deleteByOrderId", "deleteInTx"));
            int installed = 0;
            for (Method method : dao.getDeclaredMethods()) {
                if (!mutations.contains(method.getName()) || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class) continue;
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    changed();
                    return result;
                });
                installed++;
            }
            Log.i(TAG, "Native ticket mutation observers installed: " + installed);
        } catch (Throwable error) {
            Log.w(TAG, "Native ticket changes unavailable: " + error.getClass().getSimpleName());
        }
        try {
            Class<?> account = WalletDexResolver.login(loader).method().getDeclaringClass();
            for (Method method : account.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.equals("addOnAsAccountInfoChangedListener")
                        && !name.equals("addOnAsLoginNotifyListener")
                        && !name.equals("addOnAsLogoutNotifyListener")) continue;
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                        || method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInterface()) continue;
                Class<?> callbackType = method.getParameterTypes()[0];
                Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, callback, args) -> {
                    if (callback.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(callback.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(callback.getName())) return proxy == args[0];
                        if ("toString".equals(callback.getName())) return "SuperCardWalletAccountObserver";
                    }
                    if (callback.getReturnType() == void.class) changed();
                    return null;
                });
                method.invoke(null, observer);
                ACCOUNT_OBSERVERS.add(observer);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Native account changes unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void checkLogin(NativeReader nativeReader, Request request, Runnable success) {
        MAIN.post(() -> {
            if (request.finished.get()) return;
            try {
                AtomicBoolean answered = new AtomicBoolean();
                Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{nativeReader.loginCallback},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                if ("toString".equals(method.getName())) return "SuperCardWalletLoginCheck";
                                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                                if ("equals".equals(method.getName())) return proxy == args[0];
                            }
                            if ("onSuccess".equals(method.getName()) && answered.compareAndSet(false, true)) {
                                if (args != null && args.length == 1 && Boolean.TRUE.equals(args[0])) success.run();
                                else request.finish(unavailable("not_logged_in", "请先在钱包中登录当前账号"));
                            } else if ("onFail".equals(method.getName()) && answered.compareAndSet(false, true)) {
                                request.finish(unavailable("account_unavailable", "钱包账号服务不可用"));
                            }
                            return null;
                        });
                nativeReader.hasLogin.invoke(null, callback);
            } catch (Throwable error) { fail(request, error); }
        });
    }

    private static void requireUnlocked(Context context) throws NotReady {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()) {
            throw new NotReady("locked", "解锁后可读取钱包票券");
        }
    }

    private static final class NativeReader {
        final Class<?> loginCallback, detailClass;
        final Method hasLogin, oidMethod, agreement;
        final Map<String, Method> detailGetters = new LinkedHashMap<>();

        NativeReader() throws Exception {
            WalletDexResolver.LoginBinding login = WalletDexResolver.login(loader);
            loginCallback = login.callback(); hasLogin = login.method();
            oidMethod = WalletDexResolver.oid(loader);
            agreement = checked(load("com.nearme.common.lib.sp.SPreferenceCommonHelper"),
                    "getCtaPass", boolean.class, true);
            // The authenticated network response already is a @Keep model. Copy
            // only getters used by the card; no DAO, Room entity or companion
            // conversion is required, and no native database model is mutated.
            detailClass = load("com.nearme.wallet.dcp.TicketInfoDetail");
            for (Field destination : DetachedTicketRow.class.getFields()) {
                String name = destination.getName();
                if (Set.of("cardType", "belongBizId", "oidHash", "pageId").contains(name)) continue;
                String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                detailGetters.put(name, checked(detailClass, getter, destination.getType(), false));
            }
        }

        DetachedTicketRow detached(Object detail) throws Exception {
            if (!detailClass.isInstance(detail)) throw new IllegalStateException("Native ticket detail changed");
            DetachedTicketRow row = new DetachedTicketRow();
            for (Map.Entry<String, Method> getter : detailGetters.entrySet())
                DetachedTicketRow.class.getField(getter.getKey()).set(row, getter.getValue().invoke(detail));
            return row;
        }

        String currentOid() throws Exception {
            Object value = oidMethod.invoke(null);
            return value instanceof String ? (String) value : "";
        }

        void requireAgreement() throws Exception {
            // Same agreement gate used by the wallet's native service switch controller.
            // getCtaPass itself includes the application's in-memory agreement flag.
            if (!Boolean.TRUE.equals(agreement.invoke(null))) {
                throw new NotReady("service_disabled", "请先在钱包中开启并同意原生服务");
            }
        }

        Bundle query(Application app, String oid, Request request) throws Exception {
            WalletNativeTicketRefresh.AccountVerifier account = () -> {
                requireUnlocked(app); requireAgreement();
                return oid.equals(currentOid());
            };
            // The official TICKET inventory discovers new purchases and supplies the
            // parent card type. Normal wallet DB refresh intentionally omits CARD_TYPE;
            // its cache is therefore neither complete nor a reliable type registry.
            List<?> groups = WalletTicketDiscovery.discover(app, request.finished, account);
            Class<?> parent = load("com.nearme.common.domain.rsp.CardPackageRspVo");
            Method groupType = checked(parent, "getCardType", String.class, false);
            Method groupBiz = checked(parent, "getBizId", String.class, false);
            Method groupTickets = checked(parent, "getTicketList", List.class, false);
            List<Object> rows = new ArrayList<>();
            List<String> liveIds = new ArrayList<>();
            Set<String> inventoryIds = new HashSet<>();
            int unsupported = 0;
            boolean truncated = false;
            for (Object group : groups) {
                if (!parent.isInstance(group)) throw new IllegalStateException("Native ticket group changed");
                String declaredType = (String) groupType.invoke(group);
                String bizId = (String) groupBiz.invoke(group);
                Object details = groupTickets.invoke(group);
                if (details == null) continue;
                for (Object detail : requireList(details)) {
                    if (request.finished.get() || !account.isCurrent())
                        throw new NotReady("account_changed", "钱包账号已变化，请重新打开卡包");
                    DetachedTicketRow row = detached(detail);
                    row.cardType = declaredType;
                    int type = ticketType(row);
                    if (type == 0) { unsupported++; continue; }
                    if (bizId == null || bizId.isEmpty())
                        throw new NotReady("inventory_incomplete", "钱包票券来源信息不完整");
                    String order = string(row, "orderId");
                    if (order.isEmpty())
                        throw new NotReady("inventory_incomplete", "钱包票券订单信息不完整");
                    // These are detached native models from an account-authenticated
                    // response. Do not mutate wallet cache or save QR codes there.
                    row.cardType = Integer.toString(type);
                    row.oidHash = oid;
                    row.belongBizId = bizId;
                    if (!inventoryIds.add(type + ":" + bizId + ":" + order)) continue;
                    if (rows.size() >= MAX_ITEMS) { truncated = true; continue; }
                    rows.add(row);
                    if (type == 32 && !liveIds.contains(order)) liveIds.add(order);
                }
            }
            java.util.Map<String, Object> live;
            Set<String> confirmedNoQr = java.util.Collections.emptySet();
            boolean liveReadFailed = false;
            try {
                WalletNativeTicketRefresh.Result refreshed =
                        WalletNativeTicketRefresh.refresh(liveIds, request.finished, account);
                live = refreshed.details();
                confirmedNoQr = refreshed.confirmedNoQr();
            } catch (Exception error) {
                Log.w(TAG, "Native live ticket read failed: " + safeError(error));
                live = java.util.Collections.emptyMap();
                liveReadFailed = true;
            }
            JSONArray films = new JSONArray(), coupons = new JSONArray();
            Set<String> ids = new HashSet<>();
            int count = 0, size = 0, incomplete = 0, qrCount = 0;
            for (Object row : rows) {
                if (request.finished.get() || !account.isCurrent())
                    throw new NotReady("account_changed", "钱包账号已变化，请重新打开卡包");
                int type = ticketType(row);
                String bizId = string(row, "belongBizId");
                Object liveDetail = live.get(string(row, "orderId"));
                if (type == 32 && liveDetail != null) {
                    DetachedTicketRow fresh = detached(liveDetail);
                    for (String fieldName : new String[]{"title", "venueName", "venueAddress", "startTime",
                            "endTime", "ticketCode", "ticketCodeData", "qrcodeUrl", "extraInfo", "status", "source"}) {
                        DetachedTicketRow.class.getField(fieldName).set(row,
                                DetachedTicketRow.class.getField(fieldName).get(fresh));
                    }
                }
                // Even if a QR read fails, the freshly synchronized inventory is
                // authoritative for metadata. Publish it and retry missing codes.
                JSONObject item = convert(app, oid, bizId, type, row);
                if (item == null) { unsupported++; continue; }
                boolean hasCode = !item.optString("qrCode").isEmpty()
                        || !item.optString("_qrImageBase64").isEmpty()
                        || !item.optString("_qrImageUrl").isEmpty();
                if (hasCode) qrCount++;
                boolean sourceOnly = type == 32 && !hasCode
                        && confirmedNoQr.contains(string(row, "orderId"));
                if (sourceOnly) item.put("_codeMode", "douyin");
                if (type == 32 && item.optInt("orderStatus") == 1 && !sourceOnly
                        && (!hasCode || liveDetail == null)) {
                    incomplete++;
                    item.put("_codePending", true);
                }
                String id = item.getString(type == 31 ? "card_id" : "entityId");
                if (!ids.add(id)) continue;
                int length = item.toString().length();
                if (count >= MAX_ITEMS || size + length > MAX_JSON_CHARS) {
                    truncated = true; break;
                }
                (type == 31 ? films : coupons).put(item);
                count++; size += length;
            }
            String json = new JSONObject().put("films", films).put("performances", new JSONArray())
                    .put("coupons", coupons).toString();
            Bundle result = ok(json, count, unsupported, truncated);
            result.putBoolean("cacheOnly", false);
            result.putBoolean("partial", unsupported > 0 || truncated || incomplete > 0 || liveReadFailed);
            result.putBoolean("refreshIncomplete", incomplete > 0 || liveReadFailed);
            result.putInt("incompleteCount", incomplete);
            if (incomplete > 0 || liveReadFailed) {
                result.putString("reason", "native_code_incomplete");
                result.putString("error", "票券已同步，正在补全钱包券码");
            }
            Log.i(TAG, "Native ticket snapshot: inventory=" + rows.size() + "; visible=" + count
                    + "; qr=" + qrCount + "; pending=" + incomplete + "; unsupported=" + unsupported);
            return result;
        }
    }

    /** Detached read-only response projection; never inserted into a wallet database. */
    private static final class DetachedTicketRow {
        public String cardType, belongBizId, oidHash;
        // Original cover2DbTicketOrderInfo does not copy pageId; preserve that null.
        public String pageId;
        public String orderId, title, venueName, venueAddress, ticketCode, ticketCodeData,
                qrcodeUrl, extraInfo, bizItemId, source;
        public Integer status, bizType;
        public Long startTime, endTime;
    }

    /** Same BIZ_TYPE fallback as the wallet's original movie/Douyin view models. */
    static int ticketType(Object row) throws Exception {
        String raw = string(row, "cardType");
        int declared = 0;
        if (!raw.isEmpty()) {
            try { declared = Integer.parseInt(raw); }
            catch (NumberFormatException unknown) { return 0; }
            if (declared != 31 && declared != 32) return 0;
        }
        Object nativeBiz = field(row, "bizType");
        int biz = nativeBiz instanceof Number ? ((Number) nativeBiz).intValue() : 0;
        int inferred = biz == 1 ? 31 : biz == 2 ? 32 : 0;
        if (declared != 0 && inferred != 0 && declared != inferred) return 0;
        return declared != 0 ? declared : inferred;
    }

    private static JSONObject convert(Context app, String oid, String bizId, int type, Object row)
            throws Exception {
        String extra = string(row, "extraInfo");
        JSONObject ext;
        try { ext = extra.isEmpty() ? new JSONObject() : new JSONObject(extra); }
        catch (org.json.JSONException ignored) { ext = new JSONObject(); }
        Long start = (Long) field(row, "startTime"), end = (Long) field(row, "endTime");
        Integer nativeStatus = (Integer) field(row, "status");
        int status = status(type, nativeStatus, end, ext);
        // There is no UNKNOWN in the original vivo enum. Do not silently call an unknown state expired.
        if (status == 0) return null;
        String order = string(row, "orderId"), page = string(row, "pageId"), item = string(row, "bizItemId");
        if (order.isEmpty() && page.isEmpty() && item.isEmpty()) return null;
        String id = "finshell:" + sha256(oid + '\u0000' + type + '\u0000' + bizId + '\u0000'
                + order + '\u0000' + page + '\u0000' + item);
        JSONObject result = new JSONObject().put(type == 31 ? "card_id" : "entityId", id)
                .put(type == 31 ? "movieTitle" : "couponName", string(row, "title"))
                .put("sourceAppPkg", PACKAGE).put("orderStatus", status).put("qrCode", "")
                .put("_nativeCardType", type).put("_nativeOrderId", order)
                .put("_nativeBizId", bizId).put("_nativeStatus", nativeStatus);
        if (type == 31) {
            result.put("cinemaName", string(row, "venueName"));
        } else {
            JSONObject poi = ext.optJSONObject("poi_info");
            result.put("merchantName", poi == null ? "" : poi.optString("poi_name", ""));
        }
        if (start != null && start > 0) result.put(type == 31 ? "startTime" : "useValidStartTime", start);
        if (end != null && end > 0) result.put(type == 31 ? "endTime" : "useValidEndTime", end);
        String url = string(row, "qrcodeUrl");
        if (type == 31) {
            // This matches MovieTicketCardQrCodeView: URL image has priority over ticketCode.
            if (url.isEmpty()) result.put("qrCode", string(row, "ticketCode"));
            else result.put("_qrImageUrl", url);
        } else {
            String encoded = ext.optString("qr_base64", "");
            if (!encoded.isEmpty()) putActualQrImage(result, encoded);
            // Douyin's ticketCode is verification text, not the QR payload.
        }
        result.put("_verificationCode", string(row, "ticketCode"));
        result.put("_nativeSource", string(row, "source"));
        String source = sourceIntent(app, type, bizId, order);
        if (!source.isEmpty()) result.put("_sourceIntent", source).put("_openIntent", source);
        return result;
    }

    private static int status(int type, Integer status, Long end, JSONObject ext) {
        if (status == null) return 0;
        // Native Douyin view explicitly marks status 2, or completed+verified_count>0, used.
        if (type == 32 && (status == 2 || status == 6 && ext.optInt("verified_count", 0) > 0)) return 2;
        if (status != 1) return 0;
        return end != null && end > 0 && end < System.currentTimeMillis() ? 4 : 1;
    }

    private static void putActualQrImage(JSONObject result, String encoded) throws Exception {
        if (encoded.length() > 2_800_000) return;
        int comma = encoded.indexOf(',');
        if (encoded.startsWith("data:") && comma >= 0) encoded = encoded.substring(comma + 1);
        byte[] bytes;
        try { bytes = Base64.decode(encoded, Base64.DEFAULT); }
        catch (IllegalArgumentException ignored) { return; }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > 4_000_000) return;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) return;
        try {
            String text = decodeNativeQr(bitmap);
            if (!text.isEmpty()) result.put("qrCode", text);
            // Keep the actual native image available to the original card view. Do not resize it.
            if (bytes.length <= 65_536) result.put("_qrImageBase64", Base64.encodeToString(bytes, Base64.NO_WRAP));
        } finally { bitmap.recycle(); }
    }

    private static String decodeNativeQr(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            Class<?> luminance = load("com.google.zxing.LuminanceSource");
            Class<?> binarizer = load("com.google.zxing.Binarizer");
            Class<?> binary = load("com.google.zxing.BinaryBitmap");
            Object source = load("com.google.zxing.RGBLuminanceSource")
                    .getConstructor(int.class, int.class, int[].class).newInstance(width, height, pixels);
            Object hybrid = load("com.google.zxing.common.HybridBinarizer")
                    .getConstructor(luminance).newInstance(source);
            Object bits = binary.getConstructor(binarizer).newInstance(hybrid);
            Object decoder = load("com.google.zxing.qrcode.QRCodeReader").getConstructor().newInstance();
            Object decoded = decoder.getClass().getMethod("decode", binary).invoke(decoder, bits);
            Object text = decoded.getClass().getMethod("getText").invoke(decoded);
            return text instanceof String && ((String) text).length() <= 16_384 ? (String) text : "";
        } catch (Throwable ignored) { return ""; }
    }

    private static String sourceIntent(Context context, int type, String bizId, String order) {
        try {
            ComponentName component = new ComponentName(PACKAGE, ROUTER);
            ActivityInfo info = context.getPackageManager().getActivityInfo(component, 0);
            if (!info.exported || !info.enabled || !info.applicationInfo.enabled || info.permission != null) return "";
            Uri.Builder route = new Uri.Builder().scheme("wallet").authority("fintech")
                    .path("/ticket/unusedList").appendQueryParameter("cardType", String.valueOf(type))
                    .appendQueryParameter("data", bizId);
            if (!order.isEmpty()) route.appendQueryParameter("orderId", order);
            return new Intent(Intent.ACTION_VIEW, route.build()).setComponent(component).setPackage(PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).toUri(Intent.URI_INTENT_SCHEME);
        } catch (Throwable ignored) { return ""; }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Method checked(Class<?> type, String name, Class<?> result, boolean isStatic,
            Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException("Native signature changed: " + name);
        }
        method.setAccessible(true);
        return method;
    }

    private static Object field(Object value, String name) throws Exception {
        return value.getClass().getField(name).get(value);
    }

    private static String string(Object value, String name) throws Exception {
        Object field = field(value, name);
        return field instanceof String ? (String) field : "";
    }

    private static List<?> requireList(Object value) {
        if (!(value instanceof List)) throw new IllegalStateException("Native query did not return a list");
        return (List<?>) value;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }

    private static String emptyData() { return "{\"films\":[],\"performances\":[],\"coupons\":[]}"; }

    private static Bundle ok(String data, int count, int skipped, boolean truncated) {
        Bundle result = new Bundle();
        result.putString("status", "ok"); result.putString("data", data); result.putString("error", "");
        result.putBoolean("ready", true); result.putInt("count", count);
        result.putInt("unsupportedCount", skipped); result.putBoolean("truncated", truncated);
        result.putBoolean("cacheOnly", true);
        return result;
    }

    private static Bundle unavailable(String reason, String message) {
        Bundle result = new Bundle();
        result.putString("status", "unavailable"); result.putString("data", emptyData());
        result.putString("reason", reason); result.putString("error", message); result.putBoolean("ready", false);
        return result;
    }

    private static void fail(Request request, Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof NotReady) {
            NotReady nativeError = (NotReady) error;
            request.finish(unavailable(nativeError.reason, nativeError.getMessage()));
        } else {
            // Never log ticket fields, QR payloads, image bytes, account IDs, or URI query data.
            Log.w(TAG, "Native wallet read unavailable: " + safeError(error));
            request.finish(unavailable("native_unavailable", "当前钱包版本的原生票券读取不可用"));
        }
    }

    // Stack frames identify reflection mismatches without exposing an exception's data-bearing message.
    private static String safeError(Throwable error) {
        StringBuilder detail = new StringBuilder();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int causes = 0; error != null && causes < 4 && seen.add(error); causes++, error = error.getCause()) {
            if (causes != 0) detail.append(" caused by ");
            detail.append(error.getClass().getName());
            StackTraceElement[] frames = error.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 8); i++) detail.append("\n at ").append(frames[i]);
        }
        return detail.toString();
    }

    private static final class NotReady extends Exception {
        final String reason;
        NotReady(String reason, String message) { super(message); this.reason = reason; }
    }

    private static final class Request {
        final BroadcastReceiver.PendingResult pending;
        final AtomicBoolean finished = new AtomicBoolean();
        Request(BroadcastReceiver.PendingResult pending) { this.pending = pending; }
        void finish(Bundle result) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                pending.setResultCode("ok".equals(result.getString("status"))
                        ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
                pending.setResultExtras(result);
            } finally { pending.finish(); BUSY.set(false); }
        }
    }
}
