package dev.local.supercardhost;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Keeps existing launcher shortcuts pointing at the native card settings. */
public final class HostActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent settings = new Intent().setClassName(getPackageName(),
                "com.vivo.card.setting.CardSettingActivity");
        startActivity(settings);
        finish();
    }
}
