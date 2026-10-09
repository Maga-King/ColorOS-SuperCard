package dev.local.supercardhost;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Non-interactive edge hint for the original vivo gesture monitor.
 *
 * Styling comes from the target SmartSideBar FloatBarView and its resources:
 * coloros_ep_floatbar_width=4dp, coloros_ep_float_bar_fill=#CACACA,
 * default paint alpha=.4, appear/disappear=300/200ms. We draw only that primitive;
 * loading its MainView/InputUtils would also initialize the native sidebar's
 * preferences and input regions. The 2dp radius and full trigger-range height
 * make the hint match the user's selected SuperCard position and length.
 */
public final class GestureHandle {
    private static final String TAG = "SuperCardGestureHandle";
    private static final String VISIBLE = "supercard_handle_visible";
    private static final String AUTO_HIDE = "supercard_handle_auto_hide";
    private static final String OPACITY = "supercard_handle_opacity";
    private static final String TOTAL = "card_service_total_switch";
    private static final String SIDE = "navigation_gesture_right_side";
    private static final String RANGE = "navigation_slide_card_range";
    private static final String LOCK = "bbk_lock_disable_card_slide_setting";
    private static final String LOCK_STYLE = "bbk_lock_disable_card_slide_open_style";
    private static final long HIDE_DELAY_MS = 3000;
    private static final long PREVIEW_LEASE_MS = 10000;
    private static final long ERROR_INTERVAL_MS = 30000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final PathInterpolator ANIMATION = new PathInterpolator(.2f, 0f, .1f, 1f);
    private static final Runnable HIDE_TIMER = GestureHandle::refresh;
    private static final Runnable PREVIEW_TIMEOUT = GestureHandle::expirePreview;

    // All mutable state belongs to the main thread.
    private static Application application;
    private static Object originalPlugin;
    private static WindowManager windows;
    private static Field regionInstance;
    private static Method regionCardShown;
    private static Method windowShown;
    private static HandleView view;
    private static WindowManager.LayoutParams layout;
    private static Snapshot previous;
    private static long visibleUntil;
    private static long scheduledHide;
    private static long lastError;
    private static int animationGeneration;
    private static boolean revealRequested;
    private static boolean fading;
    private static boolean installed;
    private static Preview preview;

    private GestureHandle() {}

    public static void install(Application host, ClassLoader loader, Object plugin) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> install(host, loader, plugin));
            return;
        }
        if (installed || !"com.android.systemui".equals(host.getPackageName())) return;
        try {
            WindowManager manager = host.getSystemService(WindowManager.class);
            if (manager == null) throw new IllegalStateException("WindowManager unavailable");
            Class<?> region = loader.loadClass("com.vivo.card.framework.gesture.CardGestureRegionHelper");
            regionInstance = region.getDeclaredField("instance");
            regionInstance.setAccessible(true);
            regionCardShown = region.getDeclaredMethod("getCardShow");
            regionCardShown.setAccessible(true);
            application = host;
            originalPlugin = plugin;
            windows = manager;

            ContentObserver observer = new ContentObserver(MAIN) {
                @Override public void onChange(boolean selfChange) { reveal(); }
                @Override public void onChange(boolean selfChange, Uri uri) { reveal(); }
            };
            for (String key : new String[]{VISIBLE, AUTO_HIDE, OPACITY, TOTAL, SIDE, RANGE, LOCK, LOCK_STYLE}) {
                host.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, observer);
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            host.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        visibleUntil = 0;
                        revealRequested = false;
                        removeHandle();
                    } else {
                        reveal();
                    }
                }
            }, filter, null, MAIN, Context.RECEIVER_NOT_EXPORTED);
            installed = true;
            reveal();
            Log.i(TAG, "Installed non-touchable edge hint with SmartSideBar drawing style");
        } catch (Throwable error) {
            report("install", error);
            removeHandle();
        }
    }

    /** Safe to call every 750ms. Unchanged refreshes never restart the hide timer. */
    public static void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(GestureHandle::refresh);
            return;
        }
        if (!installed || application == null) return;
        try {
            Snapshot next = readSnapshot();
            boolean changed = !next.samePresentation(previous);
            previous = next;
            if (!next.eligible) {
                revealRequested = false;
                visibleUntil = 0;
                removeHandle();
                return;
            }
            boolean showAgain = changed || revealRequested;
            revealRequested = false;
            long now = SystemClock.uptimeMillis();
            if (showAgain) visibleUntil = next.autoHide ? now + HIDE_DELAY_MS : Long.MAX_VALUE;
            if (next.autoHide && now >= visibleUntil) {
                beginAutoHide();
                return;
            }
            ensureHandle(next, showAgain);
            if (next.autoHide) {
                if (scheduledHide != visibleUntil) {
                    MAIN.removeCallbacks(HIDE_TIMER);
                    scheduledHide = visibleUntil;
                    MAIN.postAtTime(HIDE_TIMER, visibleUntil);
                }
            } else {
                MAIN.removeCallbacks(HIDE_TIMER);
                scheduledHide = 0;
            }
        } catch (Throwable error) {
            removeHandle();
            report("refresh", error);
        }
    }

    /** Reveal after waking, changing position/settings, or navigating to another page. */
    public static void reveal() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(GestureHandle::reveal);
            return;
        }
        revealRequested = true;
        refresh();
    }

    public static void onTopActivityChanged() {
        reveal();
    }

    /**
     * Draw an unsaved settings preview. It changes presentation only, leaving
     * the real gesture region and all Secure settings untouched. Repeating this
     * call renews the lease without restarting the appearance animation.
     */
    public static void setPreview(float top, float bottom, float opacity, boolean visible) {
        if (!Float.isFinite(top) || !Float.isFinite(bottom) || top < 0 || bottom > 1
                || top >= bottom || bottom - top + .000001f < .04f) {
            throw new IllegalArgumentException("Invalid gesture preview range");
        }
        if (!Float.isFinite(opacity) || opacity < 0 || opacity > 1) {
            throw new IllegalArgumentException("Invalid gesture preview opacity");
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> setPreview(top, bottom, opacity, visible));
            return;
        }
        preview = new Preview(top, bottom, opacity, visible,
                SystemClock.elapsedRealtime() + PREVIEW_LEASE_MS);
        MAIN.removeCallbacks(PREVIEW_TIMEOUT);
        MAIN.postDelayed(PREVIEW_TIMEOUT, PREVIEW_LEASE_MS);
        refresh();
    }

    /** End a preview or recover automatically when its UI client stops renewing. */
    public static void clearPreview() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(GestureHandle::clearPreview);
            return;
        }
        boolean wasPreviewing = preview != null;
        preview = null;
        MAIN.removeCallbacks(PREVIEW_TIMEOUT);
        if (wasPreviewing) reveal();
    }

    private static void expirePreview() {
        if (preview == null) return;
        long remaining = preview.expiresAt - SystemClock.elapsedRealtime();
        if (remaining <= 0) clearPreview();
        else MAIN.postDelayed(PREVIEW_TIMEOUT, remaining);
    }

    private static Snapshot readSnapshot() throws Exception {
        ContentResolver resolver = application.getContentResolver();
        PowerManager power = application.getSystemService(PowerManager.class);
        KeyguardManager keyguard = application.getSystemService(KeyguardManager.class);
        DisplayManager displays = application.getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (power == null || keyguard == null || display == null) {
            throw new IllegalStateException("Screen state unavailable");
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0
                || !Float.isFinite(metrics.density) || metrics.density <= 0) {
            throw new IllegalStateException("Invalid display metrics");
        }
        boolean locked = keyguard.isKeyguardLocked();
        int width = Math.min(metrics.widthPixels, Math.max(1, Math.round(4 * metrics.density)));
        Preview activePreview = preview;
        if (activePreview != null && SystemClock.elapsedRealtime() >= activePreview.expiresAt) {
            preview = null;
            MAIN.removeCallbacks(PREVIEW_TIMEOUT);
            activePreview = null;
        }
        if (activePreview != null) {
            int start = Math.round(activePreview.top * metrics.heightPixels);
            int end = Math.round(activePreview.bottom * metrics.heightPixels);
            // A settings preview may be adjusted while the saved feature is
            // disabled. It is never displayed over keyguard or an open card.
            boolean eligible = activePreview.visible && power.isInteractive() && !locked
                    && end > start && !isCardShown();
            return new Snapshot(eligible, false, locked, true, activePreview.opacity,
                    width, end - start, start, metrics.widthPixels, metrics.heightPixels, metrics.density);
        }
        boolean total = Settings.Secure.getInt(resolver, TOTAL, 1) == 1;
        boolean visible = Settings.Secure.getInt(resolver, VISIBLE, 1) == 1;
        boolean autoHide = Settings.Secure.getInt(resolver, AUTO_HIDE, 1) == 1;
        float opacity = Math.max(0, Math.min(100, Settings.Secure.getInt(resolver, OPACITY, 40))) / 100f;
        Rect navigation = navigationRegion(Settings.Secure.getString(resolver, RANGE), metrics);
        String side = Settings.Secure.getString(resolver, SIDE);
        if (side == null || side.isEmpty()) side = "side_back;side_card";
        boolean sideEnabled = false;
        for (String token : side.split(";")) {
            if ("side_card".equals(token.trim())) sideEnabled = true;
        }
        boolean navigationEnabled = sideEnabled && !navigation.isEmpty();
        Rect selected = new Rect();
        if (total && visible && power.isInteractive()) {
            if (!locked) {
                if (navigationEnabled) selected.set(navigation);
            } else if (Settings.Secure.getInt(resolver, LOCK, 0) == 1) {
                // Match GestureConfigBridge: style 1/3 are independent of the
                // unlocked switch; style 2 follows the unlocked active region.
                int style = Settings.Secure.getInt(resolver, LOCK_STYLE, 0);
                int edge = Math.min(metrics.widthPixels, Math.max(1, Math.round(24 * metrics.density)));
                if (style == 1) selected.set(metrics.widthPixels - edge, 0,
                        metrics.widthPixels, metrics.heightPixels);
                else if (style == 2 && navigationEnabled) selected.set(navigation);
                else if (style == 3) selected.set(metrics.widthPixels - edge,
                        metrics.heightPixels * 2 / 3, metrics.widthPixels, metrics.heightPixels);
            }
        }
        boolean eligible = !selected.isEmpty() && !isCardShown();
        int height = selected.height();
        int top = selected.top;
        return new Snapshot(eligible, autoHide, locked, false, opacity, width, height, top,
                metrics.widthPixels, metrics.heightPixels, metrics.density);
    }

    /** Same source-width scaling and rejection rules as GestureConfigBridge.Policy. */
    private static Rect navigationRegion(String raw, DisplayMetrics metrics) {
        Rect result = new Rect();
        if (raw == null) return result;
        String[] pieces = raw.split(";", -1);
        if (pieces.length != 4) return result;
        try {
            float[] values = new float[4];
            for (int i = 0; i < 4; i++) {
                values[i] = Float.parseFloat(pieces[i].trim());
                if (!Float.isFinite(values[i])) return result;
            }
            if (values[2] <= 0 || values[1] < 0 || values[3] <= values[1]) return result;
            double scale = (double) metrics.widthPixels / values[2];
            double top = values[1] * scale;
            double bottom = values[3] * scale;
            if (!Double.isFinite(top) || !Double.isFinite(bottom) || top < 0
                    || bottom > metrics.heightPixels || top >= bottom) return result;
            int start = (int) Math.round(top);
            int end = (int) Math.round(bottom);
            if (start < end) {
                int edge = Math.min(metrics.widthPixels, Math.max(1, Math.round(24 * metrics.density)));
                result.set(metrics.widthPixels - edge, start, metrics.widthPixels, end);
            }
        } catch (NumberFormatException ignored) {
            // Malformed or empty ranges do not display a misleading gesture hint.
        }
        return result;
    }

    private static boolean isCardShown() throws Exception {
        Object region = regionInstance == null ? null : regionInstance.get(null);
        if (region != null && Boolean.TRUE.equals(regionCardShown.invoke(region))) return true;
        Object impl = field(originalPlugin, "cardServiceImpl");
        Object coordinator = field(impl, "cardCoordinator");
        Object manager = field(coordinator, "windowManager");
        if (manager == null) return false;
        if (windowShown == null || !windowShown.getDeclaringClass().isInstance(manager)) {
            windowShown = manager.getClass().getDeclaredMethod("isWindowShown");
            windowShown.setAccessible(true);
        }
        return Boolean.TRUE.equals(windowShown.invoke(manager));
    }

    private static Object field(Object owner, String name) throws Exception {
        if (owner == null) return null;
        String key = owner.getClass().getName() + "#" + name;
        Field resolved = FIELDS.get(key);
        if (resolved == null) {
            for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    resolved = type.getDeclaredField(name);
                    resolved.setAccessible(true);
                    FIELDS.put(key, resolved);
                    break;
                } catch (NoSuchFieldException ignored) { }
            }
        }
        if (resolved == null) throw new NoSuchFieldException(key);
        return resolved.get(owner);
    }

    private static void ensureHandle(Snapshot state, boolean showAgain) {
        if (view == null) {
            HandleView created = new HandleView(application, state.density);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    state.width, state.height, 2009,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.RIGHT;
            params.x = 0;
            params.y = state.top;
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setTitle("SuperCardGestureHandle");
            created.setAlpha(0f);
            try {
                windows.addView(created, params);
            } catch (Throwable error) {
                // addView may have attached before throwing; retain any live
                // instance until removeHandle can detach it to avoid duplicates.
                if (created.getParent() != null) {
                    view = created;
                    layout = params;
                    removeHandle();
                }
                throw error;
            }
            view = created;
            layout = params;
            showAgain = true;
        } else if (layout.width != state.width || layout.height != state.height || layout.y != state.top) {
            layout.width = state.width;
            layout.height = state.height;
            layout.y = state.top;
            windows.updateViewLayout(view, layout);
        }
        view.radius = 2 * state.density;
        int paintAlpha = Math.round(255 * state.opacity);
        if (view.paint.getAlpha() != paintAlpha) {
            view.paint.setAlpha(paintAlpha);
            view.invalidate();
        }
        if (showAgain) {
            animationGeneration++;
            fading = false;
            view.animate().cancel();
            view.setVisibility(View.VISIBLE);
            view.setTranslationX(0);
            view.animate().alpha(1f).setDuration(300).setInterpolator(ANIMATION).withEndAction(null).start();
        }
    }

    private static void beginAutoHide() {
        MAIN.removeCallbacks(HIDE_TIMER);
        scheduledHide = 0;
        if (view == null || fading) return;
        fading = true;
        HandleView fadingView = view;
        int generation = ++animationGeneration;
        fadingView.animate().cancel();
        fadingView.animate().alpha(0f).translationX(fadingView.getWidth())
                .setDuration(200).setInterpolator(ANIMATION).withEndAction(() -> {
                    if (view == fadingView && generation == animationGeneration && fading) removeHandle();
                }).start();
        // The original input monitor remains active; this only hides the hint.
    }

    private static void removeHandle() {
        MAIN.removeCallbacks(HIDE_TIMER);
        scheduledHide = 0;
        animationGeneration++;
        fading = false;
        HandleView removing = view;
        if (removing == null) return;
        WindowManager.LayoutParams removingLayout = layout;
        view = null;
        layout = null;
        try {
            removing.animate().cancel();
            windows.removeViewImmediate(removing);
        } catch (Throwable error) {
            if (removing.getParent() != null) {
                // Keep a failed-detach reference so later refreshes reuse or
                // retry removal instead of adding a second window.
                view = removing;
                layout = removingLayout;
                removing.setAlpha(0f);
                removing.setVisibility(View.INVISIBLE);
                report("remove window", error);
            }
        }
    }

    private static void report(String stage, Throwable error) {
        long now = SystemClock.uptimeMillis();
        if (lastError == 0 || now - lastError >= ERROR_INTERVAL_MS) {
            lastError = now;
            Log.w(TAG, stage + " failed", error);
        }
    }

    private static final class HandleView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius;

        HandleView(Context context, float density) {
            super(context);
            radius = 2 * density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(202, 202, 202));
            paint.setAlpha(102);
            setFocusable(false);
            setClickable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override protected void onDraw(Canvas canvas) {
            float corner = Math.min(radius, Math.min(getWidth(), getHeight()) / 2f);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), corner, corner, paint);
        }
    }

    private static final class Preview {
        final float top, bottom, opacity;
        final boolean visible;
        final long expiresAt;

        Preview(float top, float bottom, float opacity, boolean visible, long expiresAt) {
            this.top = top;
            this.bottom = bottom;
            this.opacity = opacity;
            this.visible = visible;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Snapshot {
        final boolean eligible, autoHide, locked, previewing;
        final int width, height, top, screenWidth, screenHeight;
        final float density, opacity;

        Snapshot(boolean eligible, boolean autoHide, boolean locked, boolean previewing, float opacity, int width, int height,
                 int top, int screenWidth, int screenHeight, float density) {
            this.eligible = eligible;
            this.autoHide = autoHide;
            this.locked = locked;
            this.previewing = previewing;
            this.opacity = opacity;
            this.width = width;
            this.height = height;
            this.top = top;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.density = density;
        }

        boolean samePresentation(Snapshot other) {
            return other != null && eligible == other.eligible && autoHide == other.autoHide
                    && locked == other.locked && previewing == other.previewing
                    && Float.compare(opacity, other.opacity) == 0 && width == other.width && height == other.height
                    && top == other.top && screenWidth == other.screenWidth && screenHeight == other.screenHeight
                    && Float.compare(density, other.density) == 0;
        }
    }
}
