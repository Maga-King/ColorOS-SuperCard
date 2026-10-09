package dev.local.supercardhost;

import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/** Keeps the original vivo coupon views, with real wallet reads and explicit actions. */
public final class CouponRuntimeBridge {
    private static final String TAG = "SuperCardCoupon";
    private static final String OBF = "com.vivo.cardplugin.obfuscated.";
    private static final String ROUTER = "com.nearme.common.router.RouterActivity";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());

    private CouponRuntimeBridge() { }

    public static synchronized void install(XposedModule module, Application host,
            ClassLoader loader) throws Exception {
        if (INSTALLED.contains(loader)) return;
        new Adapter(host, loader).install(module);
        INSTALLED.add(loader);
    }

    private static final class Adapter {
        final Application host;
        final ClassLoader loader;
        final Class<?> bean, plugin, action;
        final Method id, scene, parser, refresh, requireUnlock;
        final Object parserObject, unit;
        final Field callback, currentDialog;
        final SharedPreferences hidden;
        // Bind source/image metadata to the exact parsed bean. An older loader
        // query can finish after a hide/show generation; it must not overwrite
        // links belonging to the currently rendered, newer list.
        final Map<Object, ItemRow> itemRows = Collections.synchronizedMap(new WeakHashMap<>());
        record ItemRow(JSONObject data, String accountKey) { }
        final Map<Object, RenderedList> renderedLists = new WeakHashMap<>();
        final Map<View, Float> detailTouchStarts = new WeakHashMap<>();
        record RenderedList(List<?> items, String labelState) { }

        Adapter(Application host, ClassLoader loader) throws Exception {
            this.host = host; this.loader = loader;
            bean = loader.loadClass(OBF + "qe");
            plugin = loader.loadClass("com.vivo.cardplugin.staging.StagingCardPluginImpl");
            action = loader.loadClass(OBF + "zm");
            id = method(bean, "d"); scene = method(bean, "l");
            Class<?> parseType = loader.loadClass(OBF + "te");
            parser = method(parseType, "h", JSONObject.class);
            parserObject = parseType.getField("a").get(null);
            unit = loader.loadClass(OBF + "j90").getField("a").get(null);
            refresh = method(plugin, "loadCouponList", String.class);
            requireUnlock = method(plugin, "requireUnlock", action);
            callback = field(plugin, "mCallback");
            currentDialog = field(plugin, "mCurrentDialog");
            hidden = host.getSharedPreferences("supercard_wallet_hidden_coupons", Context.MODE_PRIVATE);
        }

        void install(XposedModule module) throws Exception {
            CouponRefreshCoordinator.install(host);
            module.hook(method(loader.loadClass(OBF + "se"), "c", Context.class)).intercept(chain -> {
                String expectedAccount = null;
                try {
                    JSONObject data = CouponRefreshCoordinator.snapshot(host);
                    if (data == null) return new ArrayList<>();
                    String account = data.getString("_walletAccountKey");
                    expectedAccount = account;
                    Map<String, JSONObject> rows = new HashMap<>();
                    index(data, "films", "film", "card_id", rows);
                    index(data, "performances", "performance", "id", rows);
                    index(data, "coupons", "coupon", "entityId", rows);
                    Object result = parser.invoke(parserObject, data);
                    List<Object> visible = new ArrayList<>();
                    if (result instanceof List<?>) for (Object item : (List<?>) result) {
                        if (bean.isInstance(item) && !hidden.getBoolean(key(item), false)) {
                            JSONObject row = rows.get(key(item));
                            if (row != null) itemRows.put(item, new ItemRow(row, account));
                            visible.add(item);
                        }
                    }
                    Log.i(TAG, "Read genuine wallet coupons count=" + visible.size());
                    return visible;
                } catch (Throwable error) {
                    Log.e(TAG, "Wallet coupon query unavailable", error);
                    CouponRefreshCoordinator.reportAdapterFailure(expectedAccount);
                    // The original loader owns empty state and generation ordering.
                    return new ArrayList<>();
                }
            });
            // Preserve the original loader and generation; replace only its observer.
            module.hook(method(plugin, "registerDataObserver")).intercept(chain -> {
                Object owner = chain.getThisObject();
                WeakReference<Object> weakOwner = new WeakReference<>(owner);
                CouponRefreshCoordinator.subscribe(owner, () -> {
                    Object target = weakOwner.get();
                    if (target != null) {
                        try {
                            // Remove account-invalid views before the original
                            // background loader posts its next generation.
                            if (CouponRefreshCoordinator.snapshot(host) == null) {
                                Object card = field(plugin, "mCardView").get(target);
                                if (card != null) method(card.getClass(), "setCardDataList", List.class)
                                        .invoke(card, Collections.emptyList());
                            }
                            refresh.invoke(target, new Object[]{null});
                        }
                        catch (Exception error) { Log.w(TAG, "Original coupon refresh failed", error); }
                    }
                });
                CouponRefreshCoordinator.ensureRefresh(host);
                return null;
            });
            module.hook(method(plugin, "unregisterDataObserver")).intercept(chain -> {
                CouponRefreshCoordinator.unsubscribe(chain.getThisObject());
                return chain.proceed();
            });
            module.hook(method(loader.loadClass(OBF + "ne"), "a", bean, Context.class))
                    .intercept(chain -> source(chain.getArg(0)));
            module.hook(method(plugin, "onOpenAppClick", bean, int.class)).intercept(chain -> {
                Object owner = chain.getThisObject(), item = chain.getArg(0);
                unlock(owner, () -> open(owner, source(item), "此券暂没有可用的来源入口"));
                return null;
            });
            module.hook(method(plugin, "onViewOriginalImageClick", bean, int.class)).intercept(chain -> {
                Object owner = chain.getThisObject(), item = chain.getArg(0);
                unlock(owner, () -> originalImage(owner, item));
                return null;
            });
            module.hook(method(plugin, "onMarkUsedClick", bean, int.class)).intercept(chain -> {
                Object owner = chain.getThisObject(), item = chain.getArg(0);
                // No verified native write API: do not alter wallet state or emit
                // the original unconditional "used" success toast.
                unlock(owner, () -> {
                    toast("请在钱包中查看或管理券的使用状态");
                    open(owner, source(item), "此券暂没有可用的钱包管理入口");
                });
                return null;
            });
            module.hook(method(plugin, "executeUsedAction", bean, int.class, int.class)).intercept(chain -> {
                try {
                    Object owner = chain.getThisObject(), item = chain.getArg(0);
                    int origin = (Integer) chain.getArg(2);
                    if (row(item) == null) { toast("券码正在更新，请稍后重试"); return null; }
                    if (origin == 4) {
                        if (hidden.edit().putBoolean(key(item), true).commit()) {
                            refresh.invoke(owner, new Object[]{null});
                            toast("已从超级卡包移除，钱包原券仍保留");
                        } else toast("未能保存移除状态，请重试");
                    } else {
                        toast("请在钱包中查看或管理券的使用状态");
                        open(owner, source(item), "此券暂没有可用的钱包管理入口");
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "Cannot finish coupon action", error); toast("券码操作暂不可用");
                }
                return null;
            });
            module.hook(method(plugin, "showClickDialog", bean, int.class, int.class)).intercept(chain -> {
                Object result = chain.proceed();
                if ((Integer) chain.getArg(2) == 4) adjustRemoveDialog(chain.getThisObject());
                return result;
            });
            // Force the exact original confirmation handler's checkbox off as well
            // as hiding it, so it can never send SmartShot's screenshot-delete intent.
            module.hook(method(plugin, "showClickDialog$lambda$4", CheckBox.class,
                    bean, plugin, int.class, int.class, View.class)).intercept(chain -> {
                CheckBox check = (CheckBox) chain.getArg(0);
                if (check != null) { check.setChecked(false); check.setEnabled(false); }
                return chain.proceed();
            });
            module.hook(method(plugin, "onSettingClick$lambda$11", plugin)).intercept(chain -> {
                try {
                    Object owner = chain.getArg(0);
                    open(owner, management(), "钱包券管理入口暂不可用");
                } catch (Throwable error) {
                    Log.w(TAG, "Cannot open wallet coupon settings", error); toast("钱包券管理入口暂不可用");
                }
                return unit;
            });
            Class<?> stack = loader.loadClass("com.vivo.cardplugin.staging.ui.view.TicketStackView");
            installStackCompat(module, stack);
            module.hook(method(stack, "e0", bean, int.class, int.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (result instanceof View) {
                        View view = (View) result;
                        View used = find(view, "btn_mark_used");
                        View remove = find(view, "btn_remove_coupon");
                        View image = find(view, "btn_view_image");
                        View open = find(view, "btn_open_app");
                        if (used instanceof TextView) ((TextView) used).setText("钱包中管理");
                        if (remove instanceof TextView) ((TextView) remove).setText("从卡包移除");
                        if (open instanceof TextView) ((TextView) open).setText("钱包中查看");
                        JSONObject row = row(chain.getArg(0));
                        if (image != null && (row == null || row.optString("_originalImageUri").isEmpty()))
                            image.setVisibility(View.GONE);
                        applyOfficialCode(view, chain.getArg(0));
                    }
                } catch (Throwable error) { Log.w(TAG, "Official coupon image unavailable", error); }
                return result;
            });
            Class<?> card = loader.loadClass("com.vivo.cardplugin.staging.ui.CouponCardView");
            module.hook(method(card, "c")).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof View) applyEmptyState((View) result);
                return result;
            });
            module.hook(method(card, "o")).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof View) applyEmptyState((View) chain.getThisObject());
                return result;
            });
            // Block the obsolete vivo mutation edge even if a future original click
            // path reaches it. Original native coupon data is owned by the wallet.
            module.hook(method(loader.loadClass(OBF + "se"), "b", Context.class,
                    String.class, String.class)).intercept(chain -> {
                toast("请在钱包中管理原券"); return null;
            });
            Log.i(TAG, "Installed genuine coupon UI wallet adapter");
        }

        private void installStackCompat(XposedModule module, Class<?> stack) throws Exception {
            Field data = field(stack, "p"), views = field(stack, "q");
            Field detailed = field(stack, "i"), selected = field(stack, "j");
            Method gesture = method(stack, "G0", MotionEvent.class, View.class);
            // CouponCardView.n() always calls I0(), including state-only coordinator
            // notifications. The original I0 -> L0 unconditionally destroys the
            // stack, its detail selection and scroll position. Keep the original
            // views when the actual native beans have not changed.
            module.hook(method(stack, "I0")).intercept(chain -> {
                Object owner = chain.getThisObject();
                List<?> incoming = Collections.emptyList();
                try {
                    Object value = data.get(owner);
                    if (value instanceof List<?>) incoming = (List<?>) value;
                    RenderedList rendered = renderedLists.get(owner);
                    boolean populated = owner instanceof ViewGroup
                            && ((ViewGroup) owner).getChildCount() > 0;
                    if (rendered != null && rendered.items().equals(incoming)
                            && (incoming.isEmpty() || populated)
                            && reuseRenderedRows(owner, views, rendered, incoming)) {
                        // Original click listeners close over these exact beans.
                        // Keep p aligned with those listeners and their refreshed
                        // metadata rather than orphaning the original instances.
                        data.set(owner, rendered.items());
                        renderedLists.put(owner, new RenderedList(rendered.items(),
                                CouponRefreshCoordinator.state(host)));
                        return null;
                    }
                } catch (Throwable error) { Log.w(TAG, "Cannot retain unchanged coupon stack", error); }
                Object result = chain.proceed();
                try {
                    renderedLists.put(owner, new RenderedList(new ArrayList<>(incoming),
                            CouponRefreshCoordinator.state(host)));
                } catch (Throwable error) { Log.w(TAG, "Cannot remember rendered coupon stack", error); }
                return result;
            });
            module.hook(method(stack, "d0")).intercept(chain -> {
                renderedLists.remove(chain.getThisObject());
                return chain.proceed();
            });
            // Original G0 collapses a detail on UP when translation-t >= v.
            // v is clamped to zero when a card fills the viewport, so even a
            // stationary tap in the empty no-QR area can immediately collapse it.
            // Require an actual drag for this path; original buttons and real
            // downward drags continue through the original gesture implementation.
            module.hook(gesture).intercept(chain -> {
                Object owner = chain.getThisObject();
                MotionEvent event = (MotionEvent) chain.getArg(0);
                View view = (View) chain.getArg(1);
                try {
                    int eventAction = event.getActionMasked();
                    if (eventAction == MotionEvent.ACTION_DOWN) {
                        if (isDouyinDetail(owner, data, detailed, selected))
                            detailTouchStarts.put(view, event.getRawY());
                        else detailTouchStarts.remove(view);
                    } else if (eventAction == MotionEvent.ACTION_UP) {
                        Float start = detailTouchStarts.remove(view);
                        // v0 installs the detail touch listener during the click;
                        // it can therefore see an UP without seeing its DOWN.
                        if (isDouyinDetail(owner, data, detailed, selected) && start == null)
                            return null; // No gesture baseline: preserve v0's running expansion.
                        if (start != null && isDouyinDetail(owner, data, detailed, selected)
                                && event.getRawY() - start
                                <= ViewConfiguration.get(view.getContext()).getScaledTouchSlop()) {
                            MotionEvent cancel = MotionEvent.obtain(event);
                            try {
                                cancel.setAction(MotionEvent.ACTION_CANCEL);
                                gesture.invoke(owner, cancel, view);
                            } finally { cancel.recycle(); }
                            return null;
                        }
                    } else if (eventAction == MotionEvent.ACTION_CANCEL) {
                        detailTouchStarts.remove(view);
                    }
                } catch (Throwable error) { Log.w(TAG, "Cannot guard coupon detail tap", error); }
                return chain.proceed();
            });
        }

        private boolean isDouyinDetail(Object owner, Field data, Field detailed, Field selected)
                throws Exception {
            if (!detailed.getBoolean(owner)) return false;
            Object value = data.get(owner);
            int position = selected.getInt(owner);
            if (!(value instanceof List<?>)) return false;
            List<?> items = (List<?>) value;
            if (position < 0 || position >= items.size()) return false;
            JSONObject row = row(items.get(position));
            return row != null && "douyin".equals(row.optString("_codeMode"));
        }

        private boolean reuseRenderedRows(Object owner, Field views, RenderedList rendered,
                List<?> incoming) throws Exception {
            // An account transition must always rebuild/clear; equal UI text is
            // insufficient authorization to reuse another account's actions.
            for (int i = 0; i < incoming.size(); i++) {
                ItemRow before = itemRows.get(rendered.items().get(i));
                ItemRow after = itemRows.get(incoming.get(i));
                if (before == null || after == null
                        || !Objects.equals(before.accountKey(), after.accountKey())
                        || !CouponRefreshCoordinator.isAccountCurrent(host, after.accountKey())) return false;
            }
            Object value = views.get(owner);
            List<?> cardViews = value instanceof List<?> ? (List<?>) value : Collections.emptyList();
            if (!incoming.isEmpty() && cardViews.size() != incoming.size()) return false;
            boolean labelChanged = !Objects.equals(rendered.labelState(), CouponRefreshCoordinator.state(host));
            for (int i = 0; i < incoming.size(); i++) {
                Object bound = rendered.items().get(i);
                ItemRow before = itemRows.get(bound), after = itemRows.get(incoming.get(i));
                boolean changed = labelChanged || !before.data().toString().equals(after.data().toString());
                itemRows.put(bound, after);
                if (changed && cardViews.get(i) instanceof View) {
                    View view = (View) cardViews.get(i);
                    View image = find(view, "btn_view_image");
                    if (image != null) image.setVisibility(after.data().optString("_originalImageUri").isEmpty()
                            ? View.GONE : View.VISIBLE);
                    applyOfficialCode(view, bound);
                }
            }
            return true;
        }

        private void index(JSONObject data, String array, String scene, String id,
                Map<String, JSONObject> result) {
            JSONArray list = data.optJSONArray(array);
            if (list == null) return;
            for (int i = 0; i < list.length(); i++) {
                JSONObject row = list.optJSONObject(i);
                if (row != null && !row.optString(id).isEmpty())
                    result.put(scene + "___" + row.optString(id), row);
            }
        }

        private void applyEmptyState(View view) {
            try {
                String title, description;
                String state = CouponRefreshCoordinator.state(host);
                switch (state) {
                    case "locked" -> {
                        title = "解锁后查看券码";
                        description = "解锁后会从钱包更新当前账号的券码";
                    }
                    case "unavailable", "codes_unavailable" -> {
                        title = "券码暂不可用";
                        description = "暂时无法连接钱包，可打开钱包查看，或下次展开卡包时重试";
                    }
                    case "partial" -> {
                        title = "部分券暂不支持";
                        description = "钱包中有暂未接入的券，可在钱包券管理中查看";
                    }
                    case "ready" -> {
                        title = "暂无可显示的券码";
                        description = "券码来自当前账号的钱包；已从超级卡包移除的券仍保留在钱包中";
                    }
                    default -> {
                        title = "正在更新券码";
                        description = "正在从钱包读取当前账号的券码";
                    }
                }
                View heading = find(view, "empty_title"), details = find(view, "empty_desc");
                if (heading instanceof TextView) ((TextView) heading).setText(title);
                if (details instanceof TextView) ((TextView) details).setText(description);
            } catch (Throwable error) { Log.w(TAG, "Cannot update coupon state label", error); }
        }

        private String key(Object item) throws Exception {
            return scene.invoke(item) + "___" + id.invoke(item);
        }

        private JSONObject row(Object item) {
            ItemRow row = itemRows.get(item);
            return row != null && CouponRefreshCoordinator.isAccountCurrent(host, row.accountKey())
                    ? row.data() : null;
        }

        private Intent source(Object item) {
            JSONObject row = row(item);
            if (row == null) return null;
            String raw = row.optString("_openIntent", row.optString("_sourceIntent"));
            try {
                Intent intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
                // Only the proven official wallet router, from the wallet process's
                // query. Never execute arbitrary recognized intents or source text.
                if (!new ComponentName(WalletCouponClient.PACKAGE, ROUTER).equals(intent.getComponent())
                        || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null
                        || !"wallet".equals(intent.getData().getScheme())
                        || !"fintech".equals(intent.getData().getHost())
                        || !"/ticket/unusedList".equals(intent.getData().getPath())
                        || !Set.of("31", "32").contains(intent.getData().getQueryParameter("cardType"))
                        || intent.getData().getQueryParameter("data") == null
                        || intent.getData().getQueryParameter("data").isEmpty()) return management();
                intent.setSelector(null); intent.setClipData(null);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return verified(intent);
            } catch (Exception error) { return management(); }
        }

        private Intent management() {
            return WalletCouponClient.managementIntent(host);
        }

        private Intent verified(Intent intent) {
            ResolveInfo resolved = host.getPackageManager().resolveActivity(intent, 0);
            ActivityInfo info = resolved == null ? null : resolved.activityInfo;
            if (info == null || !info.enabled || !info.exported
                    || !WalletCouponClient.PACKAGE.equals(info.packageName)) return null;
            if (info.permission != null && host.checkSelfPermission(info.permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
            return intent;
        }

        private void originalImage(Object owner, Object item) {
            JSONObject row = row(item);
            String raw = row == null ? "" : row.optString("_originalImageUri");
            Uri uri = raw.isEmpty() ? null : Uri.parse(raw);
            if (uri == null || !"content".equals(uri.getScheme())) {
                toast("钱包未提供此券的原始截图，可在来源应用查看"); return;
            }
            try (var input = host.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Image unavailable");
                open(owner, new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "券的原始截图暂不可用");
            } catch (Exception error) { toast("券的原始截图暂不可用"); }
        }

        private void applyOfficialCode(View view, Object item) throws Exception {
            JSONObject row = row(item);
            if (row == null) return;
            View hintView = find(view, "card_qr_hint");
            TextView hint = hintView instanceof TextView ? (TextView) hintView : null;
            if ("douyin".equals(row.optString("_codeMode"))) {
                View target = find(view, "card_qr_code");
                if (target instanceof ImageView) ((ImageView) target).setImageDrawable(null);
                if (target != null) target.setVisibility(View.GONE);
                if (hint != null) hint.setText("抖音未提供券码，需在抖音内使用");
                return;
            }
            String raw = row.optString("_qrImageBase64");
            if (raw.isEmpty()) {
                String url = row.optString("_qrImageUrl");
                View target = find(view, "card_qr_code");
                if (!url.isEmpty() && target instanceof ImageView) {
                    try { CouponImageLoader.bind((ImageView) target, key(item), url, hint); }
                    catch (Exception error) { Log.w(TAG, "Cannot bind wallet image", error); }
                } else if (hint != null) {
                    String qr = (String) method(bean, "k").invoke(item);
                    String missing = row.optBoolean("_codePending")
                            ? ("updating_codes".equals(CouponRefreshCoordinator.state(host))
                                    ? "正在从钱包补全二维码"
                                    : "券码暂未读取成功，可打开钱包查看")
                            : "此券暂未提供二维码，可打开钱包查看";
                    hint.setText(qr.isEmpty() ? missing : "二维码来自钱包，请向商家出示");
                }
                return;
            }
            if (raw.length() > 700_000) return;
            int prefix = raw.indexOf(',');
            if (raw.startsWith("data:") && prefix >= 0) raw = raw.substring(prefix + 1);
            byte[] bytes = Base64.decode(raw, Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096
                    || bounds.outHeight > 4096 || (long) bounds.outWidth * bounds.outHeight > 4_194_304) return;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return;
            View target = find(view, "card_qr_code");
            if (target instanceof ImageView) {
                ((ImageView) target).setImageBitmap(bitmap);
                target.setVisibility(View.VISIBLE);
                if (hint != null) hint.setText("二维码来自钱包，请向商家出示");
            }
        }

        private void adjustRemoveDialog(Object owner) {
            try {
                Object current = currentDialog.get(owner);
                if (!(current instanceof Dialog)) return;
                Dialog dialog = (Dialog) current;
                if (dialog.getWindow() != null) fixDialogViews(dialog.getWindow().getDecorView());
                Object button = method(current.getClass(), "g", int.class).invoke(current, -1);
                if (button instanceof TextView) ((TextView) button).setText("从卡包移除");
            } catch (Exception error) { Log.w(TAG, "Cannot adjust coupon remove confirmation", error); }
        }

        private void fixDialogViews(View view) {
            if (view instanceof CheckBox) {
                CheckBox check = (CheckBox) view;
                check.setChecked(false); check.setEnabled(false); check.setVisibility(View.GONE);
            } else if (view instanceof TextView) {
                TextView text = (TextView) view;
                Context context = view.getContext();
                for (String name : new String[]{"coupon_card_confirm_remove_title", "coupon_card_dialog_remove_title"}) {
                    int id = context.getResources().getIdentifier(name, "string", "com.vivo.cardplugin.coupon");
                    if (id != 0 && context.getString(id).contentEquals(text.getText()))
                        text.setText("从超级卡包移除此券？钱包中的原券会保留。");
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) fixDialogViews(group.getChildAt(i));
            }
        }

        private void unlock(Object owner, Runnable task) {
            MAIN.post(() -> {
                try {
                    Object run = Proxy.newProxyInstance(loader, new Class<?>[]{action}, (proxy, method, args) -> {
                        if ("a".equals(method.getName())) { task.run(); return unit; }
                        if ("toString".equals(method.getName())) return "WalletCouponAction";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        throw new UnsupportedOperationException(method.getName());
                    });
                    requireUnlock.invoke(owner, run);
                } catch (Throwable error) {
                    Log.e(TAG, "Coupon action failed", error); toast("券码操作暂不可用");
                }
            });
        }

        private void open(Object owner, Intent intent, String failure) {
            if (intent == null) { toast(failure); return; }
            try {
                host.startActivity(intent);
                Object target = callback.get(owner);
                if (target != null) method(target.getClass(), "requestHideCardPack").invoke(target);
            } catch (Throwable error) { Log.w(TAG, "Cannot open genuine coupon destination", error); toast(failure); }
        }

        private void toast(String message) {
            MAIN.post(() -> Toast.makeText(host, message, Toast.LENGTH_SHORT).show());
        }
    }

    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.vivo.cardplugin.coupon");
        return id == 0 ? null : root.findViewById(id);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, args); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) { }
        }
        Method method = type.getMethod(name, args); method.setAccessible(true); return method;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
