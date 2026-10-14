package dev.local.supercardprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** No accounts, network or payment data; exercises a real secure Activity. */
public class ProbeActivity extends Activity {
    private TextView status;
    private int taps;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        body.setBackgroundColor(Color.rgb(233, 244, 238));
        status = new TextView(this);
        status.setTextSize(22);
        status.setTextColor(Color.BLACK);
        body.addView(status);
        Button touch = new Button(this);
        touch.setText("点击测试：0");
        touch.setOnClickListener(v -> {
            touch.setText("点击测试：" + ++taps);
            Log.i("SuperCardProbe", "touch count=" + taps + " display=" + getDisplay().getDisplayId());
        });
        body.addView(touch);
        Button next = new Button(this);
        next.setText(this instanceof NextActivity ? "返回测试首页" : "打开第二页");
        next.setOnClickListener(v -> {
            if (this instanceof NextActivity) finish();
            else startActivity(new Intent(this, NextActivity.class));
        });
        body.addView(next);
        setContentView(body);
    }

    @Override public void onResume() {
        super.onResume();
        int display = getDisplay().getDisplayId();
        status.setText("PAY 屏幕测试" + (this instanceof NextActivity ? " · 第二页" : "")
                + "\n\nDisplay ID: " + display
                + (display == 0 ? "\n当前在主屏" : "\n当前在副屏")
                + "\n\n安全窗口已开启\n页面不含支付信息");
        Log.i("SuperCardProbe", getClass().getSimpleName() + " resumed display=" + display
                + " task=" + getTaskId() + " secure=true");
    }
}
