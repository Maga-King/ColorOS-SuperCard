package dev.local.supercardhost;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone settings that do not depend on hooks in this application's process. */
public final class HostToolsActivity extends Activity {
    private static final String[] FUNCTIONS = {"wx_scan", "alipay_scan", "wx_pay", "alipay_pay",
            "alipay_ride", "wx_ride", "wx2_scan", "wx2_pay", "alipay_health_code"};
    private static final String[] LABELS = {"微信扫一扫", "支付宝扫一扫", "微信付款码", "支付宝付款码",
            "支付宝乘车码", "微信乘车码", "微信分身扫一扫", "微信分身付款码", "支付宝医保码"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, Button> userButtons = new LinkedHashMap<>();
    private Bundle confirmed = new Bundle();
    private Bundle confirmedUsers = new Bundle();
    private TextView rootStatus, frameworkStatus, hookStatus, displayStatus, setupStatus, message;
    private TextView userHint;
    private Button refresh;
    private boolean rendering, connected, busy, rootBusy;
    private long requestGeneration;
    private int textColor, secondaryColor;

    @Override public void onCreate(Bundle state) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        setTitle("运行状态与 PAY 设置");
        textColor = dark ? Color.rgb(238, 238, 238) : Color.rgb(28, 28, 28);
        secondaryColor = dark ? Color.rgb(190, 190, 190) : Color.rgb(85, 85, 85);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(dark ? Color.rgb(20, 20, 20) : Color.rgb(250, 250, 250));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(32));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(dp(24) + bars.left, dp(24) + bars.top,
                    dp(24) + bars.right, dp(32) + bars.bottom);
            return insets;
        });
        scroll.addView(body);
        body.addView(text("运行状态与 PAY 设置", 24, false));
        rootStatus = text("Root 授权：正在检测…", 16, false);
        frameworkStatus = text("框架：正在连接…", 16, false);
        hookStatus = text("SystemUI 激活：正在连接…", 16, false);
        displayStatus = text("虚拟屏后端：正在连接…", 16, false);
        for (TextView status : new TextView[]{rootStatus, frameworkStatus, hookStatus, displayStatus})
            body.addView(status);
        setupStatus = text("设置保存与后台权限：正在检查…", 15, true);
        body.addView(setupStatus);
        refresh = new Button(this);
        refresh.setText("刷新运行状态");
        refresh.setOnClickListener(view -> { checkRoot(true); refreshSetup(true); query(); });
        body.addView(refresh);
        body.addView(text("Root 仅在首次打开或点击刷新时检测；授权检测最长等待 12 秒。", 14, true));
        message = text("正在读取设置…", 14, true);
        body.addView(message);
        body.addView(text("PAY 启动用户", 20, false));
        userHint = text("微信和支付宝分别选择，默认主用户。创建分身后点刷新；保存后下次打开卡片生效。原有微信分身专用项始终使用分身。", 15, true);
        body.addView(userHint);
        for (String app : PayUserSettings.APPS) {
            Button button = new Button(this);
            button.setText(appLabel(app) + "：正在读取…");
            button.setEnabled(false);
            button.setOnClickListener(view -> chooseUser(app));
            userButtons.put(app, button);
            body.addView(button);
        }
        body.addView(text("PAY 页面触摸", 20, false));
        body.addView(text("默认关闭时沿用原版触摸名单。开启后，允许对应功能的目标应用页面接收触摸。关闭不会禁用原版已允许的页面。", 15, true));
        for (int i = 0; i < FUNCTIONS.length; i++) {
            String function = FUNCTIONS[i];
            Switch toggle = new Switch(this);
            toggle.setText(LABELS[i]);
            toggle.setTextColor(textColor);
            toggle.setTextSize(16);
            toggle.setPadding(0, dp(14), 0, dp(14));
            toggle.setMinHeight(dp(56));
            toggle.setEnabled(false);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (!rendering) save(function, checked);
            });
            switches.put(function, toggle);
            body.addView(toggle);
        }
        setContentView(scroll);
        scroll.requestApplyInsets();
        checkRoot(false);
        refreshSetup(false);
        query();
    }

    private void refreshSetup(boolean retry) {
        RootAppSetup.request(this, retry, () -> {
            if (!alive()) return;
            android.content.SharedPreferences policy = getSharedPreferences("app_setup", 0);
            boolean idle = getSystemService(android.os.PowerManager.class)
                    .isIgnoringBatteryOptimizations(getPackageName());
            setupStatus.setText("设置保存：" + (RootAppSetup.canSave(this) ? "已授权" : "未授权")
                    + "；可授予权限：" + AppPermissionGrants.summary(this)
                    + "；悬浮窗：" + (android.provider.Settings.canDrawOverlays(this) ? "已允许" : "未确认")
                    + "；电池优化豁免：" + (idle ? "已加入" : "未确认")
                    + "\nColorOS 自启动：" + (policy.getBoolean("autoStart", false) ? "已允许" : "未确认")
                    + "；关联启动：" + (policy.getBoolean("associate", false) ? "已允许" : "未确认")
                    + "；后台运行：" + (policy.getBoolean("background", false) ? "已允许" : "未确认"));
        });
    }

    private void checkRoot(boolean refreshRoot) {
        if (rootBusy) return;
        rootBusy = true;
        rootStatus.setText("Root 授权：正在检测…");
        updateEnabled();
        RootAccessCheck.check(refreshRoot, result -> {
            if (!alive()) return;
            rootBusy = false;
            String status;
            switch (result) {
                case AUTHORIZED: status = "已授权（已确认 uid 0）"; break;
                case NOT_AUTHORIZED: status = "未获授权"; break;
                case UNAVAILABLE: status = "无法运行 su，尚未确认 Root"; break;
                case TIMED_OUT: status = "检测超时，请确认授权后刷新"; break;
                default: status = "检测未完成，请刷新重试";
            }
            rootStatus.setText("Root 授权：" + status);
            updateEnabled();
        });
    }

    private void query() {
        if (busy) return;
        message.setText("正在读取设置…");
        request("diagnostics", null, false);
    }

    private void save(String function, boolean value) {
        if (!connected || busy) { renderSwitches(); return; }
        Bundle values = new Bundle();
        values.putString("function", function);
        values.putBoolean("value", value);
        message.setText("正在保存…");
        request("payTouch", values, true);
    }

    private void chooseUser(String app) {
        if (!connected || busy) return;
        Bundle state = confirmedUsers.getBundle(app);
        if (state == null) return;
        int[] users = state.getIntArray("users");
        if (users == null || users.length == 0) return;
        String[] labels = new String[users.length];
        int selected = state.getInt("selected", 0), checked = -1;
        for (int i = 0; i < users.length; ++i) {
            labels[i] = userLabel(users[i]);
            if (users[i] == selected) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(appLabel(app) + "启动用户")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (!connected || busy || users[which] == selected) return;
                    Bundle values = new Bundle();
                    values.putString("app", app);
                    values.putInt("user", users[which]);
                    message.setText("正在保存启动用户…");
                    request("payUser", values, true);
                }).setNegativeButton("取消", null).show();
    }

    private void request(String operation, Bundle values, boolean saving) {
        busy = true;
        updateEnabled();
        long generation = ++requestGeneration;
        Runnable timeout = () -> finishRequest(generation, null, "timeout", saving);
        main.postDelayed(timeout, 6000);
        try {
            CardConfiguration.request(this, operation, values, (state, error) -> main.post(() -> {
                if (!alive() || generation != requestGeneration || !busy) return;
                main.removeCallbacks(timeout);
                String failure = error;
                if (saving && failure == null && validDiagnostics(state)) {
                    if ("payTouch".equals(operation)
                            && state.getBundle("payTouchOverrides").getBoolean(values.getString("function"), false)
                            != values.getBoolean("value")) failure = "unconfirmed";
                    if ("payUser".equals(operation)) {
                        Bundle users = state.getBundle("payUsers");
                        Bundle app = users == null ? null : users.getBundle(values.getString("app"));
                        if (!validUsers(users) || app == null || app.getInt("selected", -1) != values.getInt("user"))
                            failure = "unconfirmed";
                    }
                }
                finishRequest(generation, state, failure, saving);
            }));
        } catch (RuntimeException error) {
            main.removeCallbacks(timeout);
            finishRequest(generation, null, "request", saving);
        }
    }

    private void finishRequest(long generation, Bundle state, String error, boolean saving) {
        if (!alive() || generation != requestGeneration || !busy) return;
        busy = false;
        if (error != null || !validDiagnostics(state)) {
            connected = false;
            renderSwitches();
            frameworkStatus.setText("框架：未连接");
            hookStatus.setText("SystemUI 激活：未连接");
            displayStatus.setText("虚拟屏后端：状态未知");
            message.setText(saving ? "保存未确认，已恢复上次读取的显示。请刷新核对实际设置。"
                    : "卡包服务未连接或状态读取失败，请刷新重试。未连接不代表未安装框架。");
        } else {
            connected = true;
            confirmed = new Bundle(state.getBundle("payTouchOverrides"));
            confirmedUsers = validUsers(state.getBundle("payUsers"))
                    ? new Bundle(state.getBundle("payUsers")) : new Bundle();
            frameworkStatus.setText("框架：" + state.getString("framework") + " "
                    + String.valueOf(state.get("frameworkVersion")));
            hookStatus.setText("SystemUI 激活：已连接并确认激活");
            displayStatus.setText("虚拟屏后端：" + (state.getBoolean("rootDisplayReady") ? "已就绪" : "尚未就绪"));
            renderSwitches();
            message.setText(saving ? "已保存。启动用户在下次打开卡片时生效。" : "设置已读取。");
        }
        updateEnabled();
    }

    private boolean validDiagnostics(Bundle state) {
        if (state == null || !Boolean.TRUE.equals(state.get("systemUiHook"))
                || !(state.get("rootDisplayReady") instanceof Boolean)
                || !(state.get("framework") instanceof String)
                || state.getString("framework").isEmpty()
                || state.get("frameworkVersion") == null
                || !(state.get("payTouchOverrides") instanceof Bundle)) return false;
        Bundle overrides = state.getBundle("payTouchOverrides");
        for (String function : FUNCTIONS)
            if (overrides.containsKey(function) && !(overrides.get(function) instanceof Boolean)) return false;
        return true;
    }

    private boolean validUsers(Bundle users) {
        if (users == null) return false;
        for (String app : PayUserSettings.APPS) {
            Bundle state = users.getBundle(app);
            if (state == null || !(state.get("selected") instanceof Integer)
                    || !(state.get("available") instanceof Boolean)
                    || !(state.get("users") instanceof int[])) return false;
            int[] ids = state.getIntArray("users");
            if (ids.length == 0 || ids[0] != 0) return false;
            for (int i = 1; i < ids.length; ++i) if (ids[i] <= ids[i - 1]) return false;
        }
        return true;
    }

    private static String appLabel(String app) {
        return PayUserSettings.WECHAT.equals(app) ? "微信" : "支付宝";
    }

    private static String userLabel(int id) {
        return id == 0 ? "主用户" : "多开用户（" + id + "）";
    }

    private void renderSwitches() {
        rendering = true;
        try {
            for (Map.Entry<String, Switch> entry : switches.entrySet())
                entry.getValue().setChecked(confirmed.getBoolean(entry.getKey(), false));
            for (Map.Entry<String, Button> entry : userButtons.entrySet()) {
                Bundle state = confirmedUsers.getBundle(entry.getKey());
                entry.getValue().setText(appLabel(entry.getKey()) + "：" + (state == null ? "尚未读取"
                        : userLabel(state.getInt("selected", 0))
                        + (state.getBoolean("available") ? "" : "（未安装或不可用）")));
            }
            if (connected && confirmedUsers.isEmpty())
                userHint.setText("启动用户功能尚未加载；更新模块后需重启手机，再刷新此页。");
            else userHint.setText("微信和支付宝分别选择，默认主用户。创建分身后点刷新；保存后下次打开卡片生效。原有微信分身专用项始终使用分身。");
        } finally { rendering = false; }
    }

    private void updateEnabled() {
        if (refresh != null) refresh.setEnabled(!busy && !rootBusy);
        for (Switch toggle : switches.values()) toggle.setEnabled(connected && !busy);
        for (Map.Entry<String, Button> entry : userButtons.entrySet())
            entry.getValue().setEnabled(connected && !busy && confirmedUsers.containsKey(entry.getKey()));
    }

    private TextView text(String value, int size, boolean secondary) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(secondary ? secondaryColor : textColor);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    @Override protected void onDestroy() {
        ++requestGeneration;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
