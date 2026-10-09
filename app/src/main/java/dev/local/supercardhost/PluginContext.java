package dev.local.supercardhost;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.app.ActivityOptions;
import java.io.File;

/** Original package resources/code, with writable state owned by this host. */
final class PluginContext extends ContextWrapper {
    private final Context host;
    private final ClassLoader loader;
    PluginContext(Context resources, Context host, ClassLoader loader) {
        super(resources); this.host = host; this.loader = loader;
    }
    @Override public ClassLoader getClassLoader() { return loader; }
    @Override public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            return android.view.LayoutInflater.from(getBaseContext()).cloneInContext(this);
        }
        if (WINDOW_SERVICE.equals(name)) return host.getSystemService(name);
        return super.getSystemService(name);
    }
    @Override public Context getApplicationContext() { return host.getApplicationContext(); }
    @Override public void startActivity(Intent intent) { startActivity(intent, null); }
    @Override public void startActivity(Intent intent, Bundle options) {
        if ("com.tencent.mm.ui.ShortCutDispatchAction".equals(intent.getAction())
                || "com.tencent.mm.action.BIZSHORTCUT".equals(intent.getAction())) {
            int display = PayRuntimeBridge.getActiveDisplayId();
            if (display > 0 && options == null) {
                ActivityOptions routed = ActivityOptions.makeBasic(); routed.setLaunchDisplayId(display);
                options = routed.toBundle();
                android.util.Log.i("SuperCardRuntime", "Routing WeChat shortcut to card display=" + display);
            }
        }
        // The host is the actual window/display owner and the real calling package.
        host.startActivity(intent, options);
    }
    @Override public Context createConfigurationContext(Configuration config) {
        return new PluginContext(getBaseContext().createConfigurationContext(config), host, loader);
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return host.getSharedPreferences("vivo_plugin_" + name, mode);
    }
    @Override public File getFilesDir() { return host.getFilesDir(); }
    @Override public File getCacheDir() { return host.getCacheDir(); }
    @Override public File getCodeCacheDir() { return host.getCodeCacheDir(); }
}
