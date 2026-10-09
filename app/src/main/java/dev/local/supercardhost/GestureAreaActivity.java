package dev.local.supercardhost;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Locale;

/** Position and size preview for the original card's own gesture range. */
public final class GestureAreaActivity extends Activity {
    private static final String TAG = "SuperCardGestureArea";
    private static final float MIN_LENGTH = .04f;
    private static final int STEPS = 1000;
    private static final int POSITION = 0, LENGTH = 1, OPACITY = 2;
    private static final int BLUE = 0xff007bff;
    private static final String[] LOCK_LABELS = {"关闭", "整条右侧", "跟随此范围", "右下部分"};
    private Context sidebarContext;
    private ClassLoader sidebarLoader;
    private Bundle confirmed;
    private float top = .76f, bottom = .98f, handleOpacity = .4f;
    private int screenWidth = 1080, screenHeight = 2400, lockStyle;
    private boolean enabled = true, unlocked = true, handleVisible = true, handleAutoHide = true;
    private boolean rendering, busy = true, destroyed;
    private int textColor, secondaryColor, cardColor, backgroundColor;
    private PhonePreview preview;
    private Slider positionSlider, lengthSlider, opacitySlider;
    private Switch unlockedSwitch, handleSwitch, autoHideSwitch;
    private TextView rangeText, positionText, lengthText, opacityText, lockText, statusText, explanation;
    private View lockRow;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        textColor = dark ? 0xffeeeeee : 0xff202124;
        secondaryColor = dark ? 0xffaeb0b5 : 0xff74777d;
        cardColor = dark ? 0xff24262a : Color.WHITE;
        backgroundColor = dark ? 0xff151619 : 0xfff5f6f8;
        getWindow().setStatusBarColor(backgroundColor);
        getWindow().setNavigationBarColor(backgroundColor);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        loadNativeContext();
        buildContent();
        setBusy(true, "正在读取设置…");
    }

    @Override protected void onResume() {
        super.onResume();
        if (confirmed == null || !busy) query();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void loadNativeContext() {
        try {
            Context raw = createPackageContext("com.coloros.smartsidebar",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            sidebarLoader = raw.getClassLoader();
            int theme = raw.getResources().getIdentifier("AppNoTitleTheme.PreferenceFragment", "style",
                    "com.coloros.smartsidebar");
            // A package context created without that package's Application can return null here.
            // Keep its theme/resources/code, but use our real Application for COUI's vibrator observer.
            sidebarContext = new NativeControlContext(raw, theme, getApplicationContext());
            initializeNativeResourceContext();
        } catch (Throwable error) {
            Log.i(TAG, "Native sidebar controls unavailable", error);
        }
    }

    private static final class NativeControlContext extends ContextThemeWrapper {
        private final Context application;

        NativeControlContext(Context resources, int theme, Context application) {
            super(resources, theme);
            this.application = application;
        }

        @Override public Context getApplicationContext() { return application; }
    }

    private void initializeNativeResourceContext() {
        try {
            // ResourceUtil reads this field directly. App has no static initializer; setting just
            // this resource context does not instantiate its Application or start sidebar services.
            Class<?> app = sidebarLoader.loadClass("com.coloros.common.App");
            Field context = app.getDeclaredField("sContext");
            context.setAccessible(true);
            if (context.get(null) == null) context.set(null, sidebarContext);
        } catch (Throwable error) {
            Log.i(TAG, "Native float-bar resource context unavailable", error);
        }
    }

    private void buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(backgroundColor);
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(6), dp(20), dp(6));
        TextView back = label("‹", 34, textColor);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(52)));
        TextView title = label("超级卡包呼出区域", 21, textColor);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), dp(28));
        preview = new PhonePreview(this);
        content.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(286)));
        rangeText = label("", 16, BLUE);
        rangeText.setGravity(Gravity.CENTER);
        content.addView(rangeText);
        explanation = label("", 14, secondaryColor);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(0, dp(10), 0, dp(18));
        content.addView(explanation);

        LinearLayout sliders = card();
        positionText = label("位置", 16, textColor);
        sliders.addView(positionText);
        positionSlider = createSlider(POSITION);
        sliders.addView(positionSlider.view, sliderParams());
        lengthText = label("条大小（触发长度）", 16, textColor);
        lengthText.setPadding(0, dp(8), 0, 0);
        sliders.addView(lengthText);
        lengthSlider = createSlider(LENGTH);
        sliders.addView(lengthSlider.view, sliderParams());
        opacityText = label("透明度", 16, textColor);
        opacityText.setPadding(0, dp(8), 0, 0);
        sliders.addView(opacityText);
        opacitySlider = createSlider(OPACITY);
        sliders.addView(opacitySlider.view, sliderParams());
        content.addView(sliders, cardParams());

        LinearLayout switches = card();
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.addView(label("非锁屏侧滑", 16, textColor),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        unlockedSwitch = new Switch(this);
        unlockedSwitch.setContentDescription("非锁屏侧滑");
        unlockedSwitch.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        unlockedSwitch.setTrackTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{BLUE, 0xffaeb2b9}));
        switchRow.addView(unlockedSwitch);
        unlockedSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (rendering || busy) return;
            unlocked = checked;
            render();
            Bundle value = new Bundle(); value.putBoolean("value", checked);
            save("unlocked", value);
        });
        switches.addView(switchRow);
        View divider = new View(this);
        divider.setBackgroundColor(0x18777777);
        switches.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout lock = new LinearLayout(this);
        lock.setGravity(Gravity.CENTER_VERTICAL);
        lock.addView(label("锁屏呼出方式", 16, textColor),
                new LinearLayout.LayoutParams(0, dp(58), 1));
        lockText = label("", 14, secondaryColor);
        lock.addView(lockText);
        lock.setOnClickListener(v -> chooseLockStyle());
        lockRow = lock;
        switches.addView(lock);
        handleSwitch = addHandleSwitch(switches, "显示呼出浮标", "handleVisible");
        autoHideSwitch = addHandleSwitch(switches, "自动隐藏浮标", "handleAutoHide");
        content.addView(switches, cardParams());
        TextView hint = label("从手机右边缘向内滑动呼出。拖动预览中的标记可移动范围，松手后自动保存。锁屏设置单独生效。", 13, secondaryColor);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(dp(4), dp(8), dp(4), dp(10));
        content.addView(hint);
        statusText = label("", 13, secondaryColor);
        statusText.setGravity(Gravity.CENTER);
        statusText.setOnClickListener(v -> { if (!busy) query(); });
        content.addView(statusText);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        render();
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(cardColor); drawable.setCornerRadius(dp(22));
        layout.setBackground(drawable);
        return layout;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1, -2);
        result.bottomMargin = dp(14); return result;
    }

    private LinearLayout.LayoutParams sliderParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void chooseLockStyle() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("锁屏呼出方式")
                .setSingleChoiceItems(LOCK_LABELS, lockStyle, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == lockStyle) return;
                    lockStyle = which; render();
                    Bundle value = new Bundle(); value.putInt("value", which);
                    save("lockStyle", value);
                }).setNegativeButton("取消", null).show();
    }

    private Switch addHandleSwitch(LinearLayout parent, String title, String operation) {
        View divider = new View(this);
        divider.setBackgroundColor(0x18777777);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title, 16, textColor), new LinearLayout.LayoutParams(0, dp(58), 1));
        Switch control = new Switch(this);
        control.setContentDescription(title);
        control.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        control.setTrackTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{BLUE, 0xffaeb2b9}));
        control.setOnCheckedChangeListener((button, checked) -> {
            if (rendering || busy) return;
            if ("handleVisible".equals(operation)) handleVisible = checked;
            else handleAutoHide = checked;
            render();
            Bundle value = new Bundle(); value.putBoolean("value", checked);
            save(operation, value);
        });
        row.addView(control); parent.addView(row);
        return control;
    }

    private void query() {
        if (destroyed) return;
        setBusy(true, "正在读取设置…");
        try {
            CardConfiguration.request(this, "query", null, (state, error) -> runOnUiThread(() -> {
                if (destroyed) return;
                if (error != null || !acceptSnapshot(state)) fail(error == null ? "设置数据不完整" : error);
                else setBusy(false, idleStatus());
            }));
        } catch (Throwable error) { fail(error.getMessage()); }
    }

    private void saveRange() {
        if (rendering || busy || confirmed == null) return;
        if (Math.abs(top - confirmed.getFloat("top")) < .0001f
                && Math.abs(bottom - confirmed.getFloat("bottom")) < .0001f) return;
        // Leave a small float margin above the server's four-percent minimum.
        if (bottom - top < MIN_LENGTH + .0001f) {
            top = Math.min(top, 1 - MIN_LENGTH - .0001f);
            bottom = top + MIN_LENGTH + .0001f;
            render();
        }
        Bundle values = new Bundle(); values.putFloat("top", top); values.putFloat("bottom", bottom);
        save("range", values);
    }

    private void saveSlider(int kind) {
        if (kind != OPACITY) { saveRange(); return; }
        if (rendering || busy || confirmed == null || destroyed) return;
        if (Math.abs(handleOpacity - confirmed.getFloat("handleOpacity", .4f)) < .0001f) return;
        Bundle value = new Bundle(); value.putFloat("value", handleOpacity);
        save("handleOpacity", value);
    }

    private void save(String operation, Bundle values) {
        if (busy || confirmed == null || destroyed) return;
        // Disable edits until both the write and its following query finish; requests stay serialized.
        setBusy(true, "正在保存…");
        try {
            CardConfiguration.request(this, operation, values, (state, error) -> runOnUiThread(() -> {
                if (destroyed) return;
                if (error != null || !acceptSnapshot(state)) {
                    fail(error == null ? "设置未保存" : error); return;
                }
                query();
            }));
        } catch (Throwable error) { fail(error.getMessage()); }
    }

    private boolean acceptSnapshot(Bundle state) {
        if (state == null || !state.containsKey("top") || !state.containsKey("bottom")
                || !state.containsKey("enabled") || !state.containsKey("unlocked")
                || !state.containsKey("lockStyle")) return false;
        float nextTop = state.getFloat("top", Float.NaN), nextBottom = state.getFloat("bottom", Float.NaN);
        if (!Float.isFinite(nextTop) || !Float.isFinite(nextBottom) || nextTop < 0
                || nextBottom > 1 || nextBottom - nextTop < MIN_LENGTH - .001f) return false;
        confirmed = new Bundle(state);
        restoreConfirmed();
        return true;
    }

    private void restoreConfirmed() {
        if (confirmed == null) return;
        top = confirmed.getFloat("top"); bottom = confirmed.getFloat("bottom");
        enabled = confirmed.getBoolean("enabled"); unlocked = confirmed.getBoolean("unlocked");
        handleVisible = confirmed.getBoolean("handleVisible", true);
        handleAutoHide = confirmed.getBoolean("handleAutoHide", true);
        float opacity = confirmed.getFloat("handleOpacity", .4f);
        handleOpacity = Float.isFinite(opacity) ? Math.max(0, Math.min(1, opacity)) : .4f;
        lockStyle = Math.max(0, Math.min(3, confirmed.getInt("lockStyle")));
        screenWidth = Math.max(1, confirmed.getInt("screenWidth", 1080));
        screenHeight = Math.max(1, confirmed.getInt("screenHeight", 2400));
        render();
    }

    private void fail(String error) {
        if (destroyed) return;
        restoreConfirmed();
        Toast.makeText(this, "未能更新设置，请重试", Toast.LENGTH_LONG).show();
        Log.w(TAG, "Configuration failed: " + error);
        setBusy(false, confirmed == null ? "卡包服务尚未连接，返回后重试" : "更新失败，已恢复上次读取的设置");
    }

    private String idleStatus() {
        return enabled ? "调整后自动保存" : "超级卡包总开关已关闭，可在智能侧边栏设置中开启";
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        boolean editable = !busy && confirmed != null;
        if (positionSlider != null) positionSlider.view.setEnabled(editable);
        if (lengthSlider != null) lengthSlider.view.setEnabled(editable);
        if (opacitySlider != null) opacitySlider.view.setEnabled(editable && enabled && handleVisible);
        if (unlockedSwitch != null) unlockedSwitch.setEnabled(editable);
        if (handleSwitch != null) handleSwitch.setEnabled(editable && enabled);
        if (autoHideSwitch != null) autoHideSwitch.setEnabled(editable && enabled && handleVisible);
        if (lockRow != null) lockRow.setEnabled(editable);
        if (preview != null) preview.setEnabled(editable);
        if (statusText != null) statusText.setText(message);
    }

    private void render() {
        if (preview == null) return;
        rendering = true;
        try {
            float length = bottom - top;
            rangeText.setText(String.format(Locale.CHINA, "右侧范围  %.0f%% — %.0f%%", top * 100, bottom * 100));
            positionText.setText(String.format(Locale.CHINA, "位置 · %.0f%%", top * 100));
            lengthText.setText(String.format(Locale.CHINA, "条大小（触发长度） · %.0f%%", length * 100));
            opacityText.setText(String.format(Locale.CHINA, "透明度 · %.0f%%", handleOpacity * 100));
            explanation.setText(enabled && unlocked ? "蓝色区域内，从右边缘向内滑动" : "灰色标记为保存的范围，非锁屏呼出当前已关闭");
            positionSlider.setProgress(Math.round((length >= 1 ? 0 : top / (1 - length)) * STEPS));
            lengthSlider.setProgress(Math.round((length - MIN_LENGTH) / (1 - MIN_LENGTH) * STEPS));
            opacitySlider.setProgress(Math.round(handleOpacity * STEPS));
            unlockedSwitch.setChecked(unlocked);
            handleSwitch.setChecked(handleVisible);
            autoHideSwitch.setChecked(handleAutoHide);
            lockText.setText(LOCK_LABELS[lockStyle] + "  ›");
            preview.setContentDescription(String.format(Locale.CHINA,
                    "右侧呼出范围，屏幕顶部向下百分之%.0f到百分之%.0f，拖动可移动", top * 100, bottom * 100));
            preview.invalidate(); preview.requestLayout();
        } finally { rendering = false; }
    }

    private void sliderChanged(int kind, int progress) {
        if (rendering || busy) return;
        float fraction = Math.max(0, Math.min(STEPS, progress)) / (float) STEPS;
        if (kind == OPACITY) {
            handleOpacity = fraction;
        } else if (kind == POSITION) {
            float length = bottom - top;
            top = fraction * (1 - length); bottom = top + length;
        } else {
            float length = MIN_LENGTH + fraction * (1 - MIN_LENGTH);
            float center = (top + bottom) / 2;
            top = Math.max(0, Math.min(1 - length, center - length / 2)); bottom = top + length;
        }
        render();
    }

    private Slider createSlider(int kind) {
        if (sidebarLoader != null) try {
            Class<?> type = sidebarLoader.loadClass("com.coui.appcompat.seekbar.COUISeekBar");
            View control = (View) type.getConstructor(Context.class, AttributeSet.class)
                    .newInstance(sidebarContext, null);
            // Exercise the exact attach dependency while fallback is still possible.
            Class<?> vibration = sidebarLoader.loadClass("d6.a");
            Method initialize = vibration.getDeclaredMethod("f", Context.class);
            initialize.setAccessible(true);
            initialize.invoke(null, sidebarContext);
            Method setter = null;
            for (Method method : type.getMethods()) {
                if (method.getName().equals("setOnSeekBarChangeListener")
                        && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInterface()) {
                    setter = method; break;
                }
            }
            if (setter == null) throw new NoSuchMethodException("Native seekbar listener");
            Object listener = Proxy.newProxyInstance(sidebarLoader, new Class<?>[]{setter.getParameterTypes()[0]},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("toString")) return "SuperCardRangeListener";
                        if (name.equals("hashCode")) return System.identityHashCode(proxy);
                        if (name.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                        // Target COUISeekBar.b uses k=progress, j=stop, l=start.
                        if ((name.equals("k") || name.equals("onProgressChanged")) && args != null
                                && args.length >= 3 && Boolean.TRUE.equals(args[2])) {
                            sliderChanged(kind, ((Number) args[1]).intValue());
                        } else if (name.equals("j") || name.equals("onStopTrackingTouch")) saveSlider(kind);
                        return null;
                    });
            setter.invoke(control, listener);
            type.getMethod("setMax", int.class).invoke(control, STEPS);
            control.setContentDescription(sliderLabel(kind));
            return new Slider(control, type.getMethod("setProgress", int.class));
        } catch (Throwable error) { Log.i(TAG, "Using platform range slider", error); }
        SeekBar control = new SeekBar(this);
        control.setMax(STEPS); control.setProgressTintList(ColorStateList.valueOf(BLUE));
        control.setThumbTintList(ColorStateList.valueOf(BLUE));
        control.setContentDescription(sliderLabel(kind));
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) sliderChanged(kind, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { saveSlider(kind); }
        });
        return new Slider(control, null);
    }

    private String sliderLabel(int kind) {
        return kind == POSITION ? "位置" : kind == LENGTH ? "条大小（触发长度）" : "透明度";
    }

    private final class Slider {
        final View view;
        final Method progressMethod;
        Slider(View view, Method progressMethod) { this.view = view; this.progressMethod = progressMethod; }
        void setProgress(int progress) {
            progress = Math.max(0, Math.min(STEPS, progress));
            try {
                if (progressMethod == null) ((SeekBar) view).setProgress(progress);
                else progressMethod.invoke(view, progress);
            } catch (Throwable error) { Log.w(TAG, "Unable to update slider", error); }
        }
    }

    private final class PhonePreview extends FrameLayout {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF phone = new RectF(), screen = new RectF();
        View nativeBar;
        boolean dragging;
        float dragY, startTop, dragLength;

        PhonePreview(Context context) {
            super(context); setWillNotDraw(false); setClipChildren(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            if (sidebarLoader != null) try {
                Class<?> type = sidebarLoader.loadClass("com.oplus.smartsidebar.permanent.floatbar.FloatBarView");
                nativeBar = (View) type.getConstructor(Context.class, AttributeSet.class)
                        .newInstance(sidebarContext, null);
                // The original paints at 0.4 internally; keep one alpha layer for accurate preview.
                type.getMethod("setMCurrentAlpha", float.class).invoke(nativeBar, 1f);
                type.getMethod("setMFillColor", int.class).invoke(nativeBar, BLUE);
                nativeBar.setClickable(false); nativeBar.setEnabled(false);
                nativeBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                nativeBar.setAlpha(handleOpacity);
                addView(nativeBar, new FrameLayout.LayoutParams(dp(4), dp(50)));
            } catch (Throwable error) { nativeBar = null; Log.i(TAG, "Using drawn float bar", error); }
        }

        private void geometry() {
            float height = getHeight() - dp(28);
            float aspect = Math.max(.35f, Math.min(.8f, screenWidth / (float) screenHeight));
            float width = Math.min(getWidth() * .48f, height * aspect);
            height = width / aspect;
            float left = (getWidth() - width) / 2;
            float upper = (getHeight() - height) / 2;
            phone.set(left, upper, left + width, upper + height);
            screen.set(phone.left + dp(5), phone.top + dp(5), phone.right - dp(5), phone.bottom - dp(5));
        }

        @Override protected void onLayout(boolean changed, int left, int upper, int right, int lower) {
            super.onLayout(changed, left, upper, right, lower);
            geometry();
            if (nativeBar != null) {
                int barTop = Math.round(screen.top + top * screen.height());
                int barBottom = Math.round(screen.top + bottom * screen.height());
                nativeBar.layout(Math.round(screen.right - dp(4)), barTop, Math.round(screen.right), barBottom);
                nativeBar.setVisibility(handleVisible ? View.VISIBLE : View.INVISIBLE);
                nativeBar.setAlpha(handleOpacity);
            }
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            try { super.onMeasure(widthSpec, heightSpec); }
            catch (Throwable error) {
                if (nativeBar == null) throw error;
                View failed = nativeBar; nativeBar = null; removeView(failed);
                Log.i(TAG, "Native preview measurement unavailable", error);
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); geometry();
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xff454a52);
            canvas.drawRoundRect(phone, dp(23), dp(23), paint);
            paint.setColor(cardColor); canvas.drawRoundRect(screen, dp(19), dp(19), paint);
            paint.setColor(0x18777777);
            float padding = screen.width() * .12f;
            for (int row = 0; row < 3; row++) {
                float y = screen.top + screen.height() * (.26f + row * .16f);
                canvas.drawRoundRect(screen.left + padding, y, screen.right - padding,
                        y + screen.height() * .11f, dp(8), dp(8), paint);
            }
            paint.setColor(0xff454a52);
            canvas.drawCircle(screen.centerX(), screen.top + dp(9), dp(3), paint);
            float areaTop = screen.top + top * screen.height();
            float areaBottom = screen.top + bottom * screen.height();
            paint.setColor(enabled && unlocked ? 0x40007bff : 0x30909090);
            canvas.drawRoundRect(screen.right - dp(17), areaTop, screen.right + dp(2), areaBottom, dp(7), dp(7), paint);
            paint.setColor(enabled && unlocked ? BLUE : 0xffa4a8af);
            if (handleVisible && nativeBar == null) {
                paint.setAlpha(Math.round(handleOpacity * 255));
                canvas.drawRoundRect(screen.right - dp(4), areaTop, screen.right, areaBottom, dp(2), dp(2), paint);
                paint.setAlpha(255);
            }
            float arrowY = (areaTop + areaBottom) / 2;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
            canvas.drawLine(screen.right - dp(10), arrowY, screen.right - dp(39), arrowY, paint);
            canvas.drawLine(screen.right - dp(39), arrowY, screen.right - dp(31), arrowY - dp(7), paint);
            canvas.drawLine(screen.right - dp(39), arrowY, screen.right - dp(31), arrowY + dp(7), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            try { super.dispatchDraw(canvas); }
            catch (Throwable error) {
                if (nativeBar != null) {
                    View failed = nativeBar; nativeBar = null;
                    post(() -> removeView(failed));
                    Log.i(TAG, "Native preview unavailable; keeping drawn range", error);
                }
            }
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            return beginDrag(event) || dragging;
        }

        private boolean beginDrag(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN || !isEnabled() || busy) return false;
            geometry();
            float x = event.getX(), y = event.getY();
            float areaTop = screen.top + top * screen.height(), areaBottom = screen.top + bottom * screen.height();
            if (x < screen.right - dp(32) || x > screen.right + dp(24)
                    || y < areaTop - dp(16) || y > areaBottom + dp(16)) return false;
            dragging = true; dragY = y; startTop = top; dragLength = bottom - top;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !dragging) beginDrag(event);
            if (!dragging) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                top = Math.max(0, Math.min(1 - dragLength, startTop + (event.getY() - dragY) / screen.height()));
                bottom = top + dragLength; render();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false);
                performClick(); saveRange();
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false); restoreConfirmed();
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
