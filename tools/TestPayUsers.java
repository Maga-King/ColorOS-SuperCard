package dev.local.supercardhost;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import com.oplus.multiapp.OplusMultiAppManager;
import com.vivo.card.cards.cardpay.virtual.PayCardSDKLauncher;
import io.github.libxposed.api.XposedModule;
import java.util.Arrays;

/** Execute through tools/test_pay_users.py, which supplies isolated fake Android services. */
public final class TestPayUsers {
    private static int checks;
    private static final String WX = "com.tencent.mm", ALI = "com.eg.android.AlipayGphone";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }
    private interface Action { void run() throws Throwable; }
    private static void rejected(Action action, String message) throws Throwable {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) {
            ++checks; return;
        }
        throw new AssertionError(message);
    }
    private static int[] users(Context host, String app) {
        return PayUserSettings.read(host).getBundle(app).getIntArray("users");
    }
    private static Intent wx() {
        return new Intent("com.tencent.mm.ui.ShortCutDispatchAction").setPackage(WX);
    }

    public static void main(String[] args) throws Throwable {
        Application host = new Application();
        for (String pkg : new String[]{WX, ALI}) PackageManager.install(0, pkg);
        PackageManager.install(991, WX); PackageManager.install(998, WX); PackageManager.install(998, ALI);
        PackageManager.install(10, WX); // Work profile is installed but is not a clone candidate.
        check(PayUserSettings.selected(host, "wechat") == 0, "WeChat default must be main");
        check(PayUserSettings.selected(host, "alipay") == 0, "Alipay default must be main");
        check(Arrays.equals(users(host, "wechat"), new int[]{0, 991, 998}), "discover real clone IDs");
        check(Arrays.equals(users(host, "alipay"), new int[]{0, 998}), "per-package installation");
        PayUserSettings.save(host, "wechat", 991);
        check(PayUserSettings.resolve(host, "wx_ride") == 991, "ride follows WeChat setting");
        check(PayUserSettings.resolve(host, "alipay_pay") == 0, "apps stay independent");
        check(PayUserSettings.resolve(host, "wx2_scan") == 991, "explicit clone follows selected clone");
        PayUserSettings.save(host, "wechat", 0);
        check(PayUserSettings.resolve(host, "wx2_pay") == 991, "explicit clone never silently uses main");
        rejected(() -> PayUserSettings.save(host, "wechat", 10), "work profile was accepted");
        rejected(() -> PayUserSettings.save(host, "wechat", -2), "USER_CURRENT was accepted");
        rejected(() -> PayUserSettings.save(host, "other", 0), "arbitrary package accepted");
        Settings.Secure.writable = false;
        rejected(() -> PayUserSettings.save(host, "wechat", 998), "failed persistence was reported saved");
        Settings.Secure.writable = true;
        check(PayUserSettings.selected(host, "wechat") == 0, "failed write changed account");
        OplusMultiAppManager.legacy = true;
        check(Arrays.equals(users(host, "wechat"), new int[]{0, 991, 998}), "older ROM clone discovery");
        OplusMultiAppManager.legacy = false;
        PackageManager.apps.get("991:" + WX).enabled = false;
        check(Arrays.equals(users(host, "wechat"), new int[]{0, 998}), "disabled clone listed");
        PackageManager.apps.get("991:" + WX).enabled = true;
        PayUserSettings.save(host, "wechat", 991);
        PackageManager.apps.remove("991:" + WX);
        Bundle missing = PayUserSettings.read(host).getBundle("wechat");
        check(missing.getInt("selected") == 991 && !missing.getBoolean("available"), "deleted clone was silently reset");
        rejected(() -> PayUserSettings.resolve(host, "wx_scan"), "deleted clone fell back to main");
        int previousLaunches = Context.launches;
        check(PayUserRouting.tryStart(host, wx(), null), "failed routing must consume the launch");
        check(Context.launches == previousLaunches, "deleted clone opened another account");
        PackageManager.install(991, WX);

        check("wechat".equals(PayUserRouting.appForIntent(wx(), false)), "shortcut matching");
        check(PayUserRouting.appForIntent(new Intent(Intent.ACTION_MAIN).setPackage(WX), false) == null, "unrelated launcher matched");
        check("wechat".equals(PayUserRouting.appForIntent(new Intent(Intent.ACTION_MAIN).setPackage(WX), true)), "ride launcher matching");
        check(PayUserRouting.appForIntent(new Intent("camera"), true) == null, "camera rerouted");
        check(PayUserRouting.appForIntent(wx().setPackage("other"), true) == null, "foreign shortcut rerouted");
        for (String id : new String[]{"10000007", "20000056", "20002047", "77700284"})
            check("alipay".equals(PayUserRouting.appForIntent(new Intent().setData(
                    Uri.parse("alipays://platformapi/startapp?appId=" + id)), false)), "Alipay entry " + id);
        check(PayUserRouting.appForIntent(new Intent().setData(Uri.parse("alipays://platformapi/startapp?appId=123")), false) == null, "unrelated Alipay link rerouted");
        check(PayUserRouting.appForIntent(new Intent().setPackage("other").setData(
                Uri.parse("alipays://platformapi/startapp?appId=20000056")), false) == null, "foreign explicit target rerouted");
        Intent original = wx(); original.flags = 0x14000000; original.oplusFlags = 128;
        original.extras.put("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode");
        PayUserRouting.start(host, original, null, "wechat", 991, 10);
        check(Context.launchUser == 991 && Context.options.getInt("display") == 10, "user and display not routed together");
        check(Context.launched.flags == original.flags && Context.launched.extras.equals(original.extras), "original SDK parameters changed");
        check(Context.launched.oplusFlags == (128 | 2048), "skip chooser lost other Oplus flags");
        check(original.oplusFlags == 128 && Context.launched != original, "source intent mutated");
        Bundle full = new Bundle(); full.putInt("display", 0); full.putInt("animation", 7);
        PayUserRouting.start(host, wx(), full, "wechat", 0, 10);
        check(Context.launchUser == 0 && Context.options.getInt("display") == 0, "explicit fullscreen options lost");
        check(Context.options.getInt("animation") == 7 && Context.options != full, "options not preserved/copied");

        XposedModule module = new XposedModule();
        PayUserRouting.install(module, host, TestPayUsers.class.getClassLoader());
        var sdk = new PayCardSDKLauncher();
        var launch = sdk.getClass().getMethod("launchWeChatPay", Context.class, String.class);
        var bus = sdk.getClass().getMethod("launchWeChatBusCode$lambda$6", Context.class);
        module.call(launch, sdk, host, "wx_ride");
        Context cloneContext = PayCardSDKLauncher.captured;
        check(cloneContext.getContentResolver().user == 991, "clone SDK provider still points at main");
        check(cloneContext.getApplicationContext().getContentResolver().user == 991, "SDK global context escaped user");
        check(host.getContentResolver().user == 0, "global card settings resolver changed");
        check(cloneContext.getPackageManager().getApplicationInfo(WX, 0).uid / 100000 == 991, "SDK package lookup wrong user");
        cloneContext.startActivity(new Intent(Intent.ACTION_MAIN).setPackage(WX));
        check(Context.launchUser == 991 && Context.options.getInt("display") == 10, "clone ride launcher route");
        module.call(bus, null, cloneContext);
        check(PayCardSDKLauncher.providerUser == 991 && PayCardSDKLauncher.wxBusApi == null, "clone bus cache lifetime");
        PayUserSettings.save(host, "wechat", 0);
        module.call(launch, sdk, host, "wx_ride");
        Context mainContext = PayCardSDKLauncher.captured;
        PayCardSDKLauncher.wxBusApi = cloneContext; // Simulate an old cached SDK object.
        module.call(bus, null, mainContext);
        check(PayCardSDKLauncher.providerUser == 0, "switch to main reused old clone provider");
        var mainBus = sdk.getClass().getMethod("launchWeChatBusCodeOnMain", Context.class);
        PayCardSDKLauncher.wxBusApi = mainContext;
        module.call(mainBus, sdk, cloneContext);
        check(PayCardSDKLauncher.providerUser == 991 && PayCardSDKLauncher.wxBusApi == null,
                "main-thread SDK fallback reused other user's cache");
        check(cloneContext.getContentResolver().user == 991, "an in-flight snapshot changed account");
        module.call(launch, sdk, host, "wx2_scan");
        check(PayCardSDKLauncher.function.equals("wx_scan")
                && PayCardSDKLauncher.captured.getContentResolver().user == 991, "legacy wx2 route used vivo-only API/main");
        var switcher = com.vivo.card.cards.cardpay.switcher.SwitcherUtil.class.getMethod(
                "openPayCardScene", Context.class, String.class, boolean.class, int.class);
        module.call(switcher, null, host, "wx2_scan", true, 0);
        check(PayCardSDKLauncher.captured.getContentResolver().user == 991, "old wx2 fallback lost selected clone");
        PayCardSDKLauncher.captured.startActivity(wx());
        check(Context.launchUser == 991 && Context.options.getInt("display") == 0,
                "old explicit fullscreen fallback sent to active card");
        PayUserSettings.save(host, "alipay", 998);
        module.call(switcher, null, host, "alipay_pay", true, 0);
        check(Context.launchUser == 998 && Context.options.getInt("display") == 0, "old Alipay fallback lost user/display");
        module.call(switcher, null, host, "settings", false, 0);
        check(com.vivo.card.cards.cardpay.switcher.SwitcherUtil.calls == 1, "unrelated old scene changed");
        // A context already prepared for fullscreen must retain display 0 through the SDK hook.
        Context fullContext = PayCardSDKLauncher.captured;
        module.call(launch, sdk, fullContext, "wx_scan");
        PayCardSDKLauncher.captured.startActivity(wx());
        check(Context.options.getInt("display") == 0 && Context.launchUser == 991, "SDK hook overwrote fullscreen snapshot");
        PayRuntimeBridge.generation++;
        rejected(() -> cloneContext.getContentResolver(), "stale provider request survived new launch");
        rejected(() -> mainContext.startActivity(wx()), "stale SDK launch survived new launch");
        module.call(launch, sdk, host, "wx_ride");
        Context released = PayCardSDKLauncher.captured;
        PayRuntimeBridge.display = -1;
        rejected(() -> released.getContentResolver(), "released card started provider request");
        OplusMultiAppManager.fail = true;
        check(Arrays.equals(users(host, "wechat"), new int[]{0}), "discovery failure broke diagnostics");
        rejected(() -> PayUserSettings.resolve(host, "wx2_pay"), "no clone silently fell back to main");
        check(PayUserSettings.resolve(host, "wx_pay") == 0, "main requires clone service");
        System.out.println("PAY user routing: " + checks + " checks passed (offline fake services).");
    }
}
