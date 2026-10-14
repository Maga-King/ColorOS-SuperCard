package dev.local.supercardhost;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Root setup is fixed to this package. No command or permission comes from an Intent. */
final class RootAppSetup {
    private static final String PACKAGE = "dev.local.supercardhost";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<Runnable> WAITERS = new ArrayList<>();
    private static boolean running, completed;

    static boolean canSave(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static void request(Context context, boolean refresh, Runnable finished) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (!PACKAGE.equals(app.getPackageName())) return;
            if (!running && completed && !refresh) { if (finished != null) finished.run(); return; }
            if (finished != null) WAITERS.add(finished);
            if (running) return;
            running = true;
            Thread thread = new Thread(() -> {
                try { apply(app); }
                catch (Exception error) { Log.w("SuperCardSetup", "Automatic app setup incomplete", error); }
                finally { MAIN.post(() -> {
                    running = false; completed = true;
                    ArrayList<Runnable> callbacks = new ArrayList<>(WAITERS); WAITERS.clear();
                    for (Runnable callback : callbacks) callback.run();
                }); }
            }, "SuperCardAppSetup");
            thread.setDaemon(true); thread.start();
        });
    }

    private static void apply(Context context) throws Exception {
        int user = android.os.Process.myUid() / 100000;
        // All changes apply only to the card host; business apps retain their own policies.
        String command = "pm grant --user " + user + " " + PACKAGE + " android.permission.WRITE_SECURE_SETTINGS"
                + "; cmd appops set --user " + user + " " + PACKAGE + " SYSTEM_ALERT_WINDOW allow"
                + "; cmd appops set --user " + user + " " + PACKAGE + " WRITE_SETTINGS allow"
                + "; cmd appops set --user " + user + " " + PACKAGE + " RUN_IN_BACKGROUND allow"
                + "; cmd appops set --user " + user + " " + PACKAGE + " RUN_ANY_IN_BACKGROUND allow"
                + "; cmd deviceidle whitelist +" + PACKAGE
                + "; am set-standby-bucket --user " + user + " " + PACKAGE + " active";
        for (String permission : AppPermissionGrants.grantable(context)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                command += "; pm grant --user " + user + " " + PACKAGE + " " + quote(permission);
        }
        // Commands can be unsupported on a ported ROM. Read back grants independently below.
        command += "; exit 0";
        run(new String[]{"su", "-c", command}, 15);
        // Read back actual grants; a shell exit code alone is not proof of setup.
        Log.i("SuperCardSetup", "Settings write=" + canSave(context)
                + ", overlay=" + Settings.canDrawOverlays(context)
                + ", battery exemption=" + context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(PACKAGE));
        if (!canSave(context)) throw new IllegalStateException("Settings write permission was not granted");
        String policy = "CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin dev.local.supercardhost.AppPlatformPolicy " + user;
        String output = run(new String[]{"su", "1000", "-c", policy}, 15);
        for (String line : output.split("\n")) if (line.startsWith("SUPERCARD_POLICY ")) {
            org.json.JSONObject result = new org.json.JSONObject(line.substring(17));
            context.getSharedPreferences("app_setup", 0).edit()
                    .putBoolean("associate", result.optBoolean("associate"))
                    .putBoolean("autoStart", result.optBoolean("autoStart"))
                    .putBoolean("background", result.optBoolean("background")).apply();
            Log.i("SuperCardSetup", "ColorOS app policy: " + result);
            return;
        }
        throw new IllegalStateException("ColorOS app policy did not return a result");
    }

    private static String run(String[] command, int seconds) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = new byte[1024]; int count;
                while ((count = input.read(bytes)) != -1) synchronized (output) {
                    if (output.length() < 8192) output.append(new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) { }
        }, "SuperCardSetupOutput");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IllegalStateException("Root app setup timed out");
            reader.join(500);
            if (process.exitValue() != 0) throw new IllegalStateException("Root app setup denied: " + process.exitValue());
            synchronized (output) { return output.toString(); }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close(); process.getOutputStream().close(); process.getErrorStream().close();
        }
    }

    /** Prevent edits being silently lost during first authorization. Never blocks the main thread. */
    static void guardSettings(Activity activity) {
        if (canSave(activity)) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER);
        panel.setPadding(48, 48, 48, 48); panel.setBackgroundColor(Color.rgb(245, 245, 245));
        panel.setClickable(true); panel.setFocusable(true);
        TextView label = new TextView(activity); label.setTextColor(Color.BLACK); label.setTextSize(18);
        label.setText("正在通过 Root 准备设置保存权限…"); panel.addView(label);
        Button retry = new Button(activity); retry.setText("重试授权"); retry.setVisibility(android.view.View.GONE); panel.addView(retry);
        content.addView(panel, new ViewGroup.LayoutParams(-1, -1));
        Runnable done = () -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (canSave(activity)) activity.recreate();
            else { label.setText("尚未取得设置保存权限，请允许超级卡包使用 Root 后重试。"); retry.setVisibility(android.view.View.VISIBLE); }
        };
        retry.setOnClickListener(view -> { retry.setVisibility(android.view.View.GONE); label.setText("正在准备权限…"); request(activity, true, done); });
        request(activity, false, done);
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
