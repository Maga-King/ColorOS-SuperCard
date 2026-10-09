package dev.local.supercardhost;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/** A real Camera2 camera confined to the original memory-card camera container. */
public final class EmbeddedMemoryCamera {
    public interface Callback {
        void onSaved(File file);
        void onCancelled();
        void onError(Throwable error);
    }
    private static final String TAG = "MemoryEmbeddedCamera";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Session current;

    private EmbeddedMemoryCamera() {}

    public static void show(Context host, ViewGroup container, File output, Callback callback) {
        MAIN.post(() -> {
            if (current != null) {
                // The original action can be delivered again while its camera is visible.
                // Never replace an active/closing device: its deferred callback would hide
                // the new container, and a second open can race the first device's close.
                Log.i(TAG, "Ignoring duplicate photo action; camera " + (current.closed ? "closing" : "already active"));
                return;
            }
            Session session = new Session(host, container, output, callback);
            current = session;
            try { session.start(); } catch (Throwable error) { session.fail(error); }
        });
    }

    /** Cancels the current camera, releases its resources, and delivers onCancelled. */
    public static void close() { MAIN.post(() -> { if (current != null) current.finish(null, false); }); }
    public static boolean active() { return current != null && !current.closed; }

    private static final class Session implements TextureView.SurfaceTextureListener {
        final Context context;
        final ViewGroup container;
        final File output;
        final Callback callback;
        final Matrix bufferToView = new Matrix();
        final HandlerThread thread = new HandlerThread("MemoryCardCamera");
        Handler worker;
        FrameLayout root, previewBox;
        FrameLayout actions;
        TextureView texture;
        ImageView still;
        FocusView focus;
        TextView zoomLabel, status;
        View shutter, retake, closeButton;
        TextView confirm, cancel;
        CameraCharacteristics characteristics;
        CameraDevice camera;
        CameraCaptureSession captureSession;
        CaptureRequest.Builder repeating;
        ImageReader reader;
        Surface previewSurface;
        String cameraId;
        Size previewSize, jpegSize;
        Size[] previewSizes, jpegSizes;
        Rect activeArray;
        volatile float zoom = 1f;
        float minZoom = 1f, maxZoom = 1f;
        boolean ratioZoom;
        volatile boolean explicitZoom;
        volatile boolean closed, busy, photographed, opening;
        boolean configuring, fallbackStreams, wasVisible;
        boolean jpegSession, requestJpegSession;
        long visibleDeadline;
        int sensorOrientation, rotation;
        Bitmap frozenBitmap;
        long captureSerial;
        long previewFrames, textureFrames;
        final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                long frame = ++previewFrames;
                if (frame == 1 || frame % 60 == 0) Log.i(TAG, "preview frame=" + frame
                        + " sensorFrame=" + result.getFrameNumber()
                        + " ae=" + result.get(CaptureResult.CONTROL_AE_STATE)
                        + " af=" + result.get(CaptureResult.CONTROL_AF_STATE)
                        + " exposureNs=" + result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        + " iso=" + result.get(CaptureResult.SENSOR_SENSITIVITY)
                        + " durationNs=" + result.get(CaptureResult.SENSOR_FRAME_DURATION)
                        + " ratio=" + result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                        + " crop=" + result.get(CaptureResult.SCALER_CROP_REGION));
            }
            @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                Log.w(TAG, "preview failed frame=" + failure.getFrameNumber() + " reason=" + failure.getReason());
            }
        };
        final Runnable visibilityMonitor = new Runnable() {
            @Override public void run() {
                if (closed) return;
                boolean visible = effectivelyVisible();
                if (visible) wasVisible = true;
                else if (wasVisible || SystemClock.uptimeMillis() >= visibleDeadline) {
                    finish(null, false);
                    return;
                }
                MAIN.postDelayed(this, 200);
            }
        };

        Session(Context context, ViewGroup container, File output, Callback callback) {
            this.context = context; this.container = container; this.output = output; this.callback = callback;
        }

        void start() throws Exception {
            if (context == null || container == null || output == null || callback == null)
                throw new IllegalArgumentException("Missing camera container/output/callback");
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Camera permission is not granted");
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) throw new IllegalStateException("Camera service unavailable");
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics candidate = manager.getCameraCharacteristics(id);
                Integer facing = candidate.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id; characteristics = candidate; break;
                }
            }
            if (cameraId == null) throw new IllegalStateException("No rear camera available");
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (activeArray == null) throw new IllegalStateException("Camera sensor size unavailable");
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 90 : orientation;
            Range<Float> ratios = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (ratios != null && Float.isFinite(ratios.getLower()) && Float.isFinite(ratios.getUpper())
                    && ratios.getLower() > 0 && ratios.getUpper() >= ratios.getLower()) {
                ratioZoom = true; minZoom = ratios.getLower(); maxZoom = ratios.getUpper();
            } else {
                Float maximum = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                maxZoom = maximum == null || !Float.isFinite(maximum) ? 1 : Math.max(1, maximum);
            }
            zoom = clamp(1, minZoom, maxZoom);
            StreamConfigurationMap streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streams == null) throw new IllegalStateException("Camera stream formats unavailable");
            jpegSizes = streams.getOutputSizes(android.graphics.ImageFormat.JPEG);
            previewSizes = streams.getOutputSizes(SurfaceTexture.class);
            jpegSize = chooseJpeg(jpegSizes);
            previewSize = choosePreview(previewSizes, jpegSize);
            Log.i(TAG, "selected camera=" + cameraId + " level="
                    + characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    + " active=" + activeArray + " sensorOrientation=" + sensorOrientation
                    + " preview=" + previewSize + " jpeg=" + jpegSize
                    + " ratioControl=" + ratioZoom + " zoomRange=" + minZoom + ".." + maxZoom
                    + " fpsAvailable=" + Arrays.toString(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)));
            thread.start(); worker = new Handler(thread.getLooper());
            buildUi();
            reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onImage, worker);
            visibleDeadline = SystemClock.uptimeMillis() + 2000;
            MAIN.post(visibilityMonitor);
            opening = true;
            try { manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    opening = false;
                    if (closed) { device.close(); thread.quitSafely(); return; }
                    Log.i(TAG, "camera opened id=" + device.getId());
                    camera = device; configure();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    opening = false; device.close();
                    if (closed) thread.quitSafely(); else fail(new IllegalStateException("Camera disconnected"));
                }
                @Override public void onError(CameraDevice device, int error) {
                    opening = false; device.close();
                    if (closed) thread.quitSafely(); else fail(new IllegalStateException("Camera error " + error));
                }
            }, worker); } catch (Throwable error) { opening = false; throw error; }
        }

        void buildUi() {
            root = new LifecycleFrame(context, this); root.setBackgroundColor(Color.BLACK);
            previewBox = new FrameLayout(context);
            FrameLayout.LayoutParams box = new FrameLayout.LayoutParams(-1, 1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            box.setMargins(dp(10), dp(10), dp(10), 0);
            root.addView(previewBox, box);
            texture = new TextureView(context); texture.setSurfaceTextureListener(this);
            previewBox.addView(texture, new FrameLayout.LayoutParams(-1, -1));
            still = new ImageView(context); still.setScaleType(ImageView.ScaleType.FIT_CENTER); still.setBackgroundColor(Color.BLACK);
            still.setVisibility(View.GONE); previewBox.addView(still, new FrameLayout.LayoutParams(-1, -1));
            focus = new FocusView(context); previewBox.addView(focus, new FrameLayout.LayoutParams(-1, -1));
            actions = new FrameLayout(context);
            root.addView(actions, new FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM));
            FrameLayout shutterGroup = new FrameLayout(context);
            ImageView outside = icon("memory_vivo_shutter_outside", "拍照");
            ImageView inside = icon("memory_vivo_shutter_inside", "");
            shutterGroup.addView(outside, new FrameLayout.LayoutParams(dp(68),dp(68),Gravity.CENTER));
            shutterGroup.addView(inside, new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.CENTER));
            shutter = shutterGroup; shutter.setContentDescription("拍照"); shutter.setBackground(roundRipple(0));
            shutter.setOnClickListener(v -> shoot());
            actions.addView(shutter, new FrameLayout.LayoutParams(dp(76),dp(76),Gravity.CENTER));
            retake = icon("memory_vivo_retake", "重拍"); retake.setVisibility(View.GONE);
            retake.setBackground(roundRipple(0)); retake.setOnClickListener(v -> retake());
            actions.addView(retake, new FrameLayout.LayoutParams(dp(68),dp(68),Gravity.CENTER));
            cancel = actionText("取消"); cancel.setOnClickListener(v -> finish(null, false));
            FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(dp(68),dp(48),Gravity.CENTER_VERTICAL | Gravity.LEFT);
            cancelParams.leftMargin=dp(14); actions.addView(cancel,cancelParams);
            confirm = actionText("确定"); confirm.setVisibility(View.GONE); confirm.setOnClickListener(v -> {
                if (!closed && photographed && output.isFile() && output.length() > 0) finish(null, true);
            });
            FrameLayout.LayoutParams confirmParams = new FrameLayout.LayoutParams(dp(68),dp(48),Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            confirmParams.rightMargin=dp(14); actions.addView(confirm,confirmParams);
            closeButton = icon("memory_vivo_close", "关闭相机"); closeButton.setPadding(dp(11),dp(11),dp(11),dp(11));
            closeButton.setBackground(roundRipple(0x4d000000)); closeButton.setOnClickListener(v -> finish(null,false));
            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP | Gravity.RIGHT);
            closeParams.topMargin=dp(14);closeParams.rightMargin=dp(14);root.addView(closeButton,closeParams);
            zoomLabel = text(14);zoomLabel.setGravity(Gravity.CENTER);zoomLabel.setTypeface(null,android.graphics.Typeface.BOLD);
            zoomLabel.setBackground(roundRipple(0x66000000)); zoomLabel.setTextColor(0xffe3b409);
            zoomLabel.setContentDescription("当前变焦倍率，点击恢复一倍；双指缩放取景器");
            zoomLabel.setOnClickListener(v -> setZoom(1));
            root.addView(zoomLabel,new FrameLayout.LayoutParams(dp(42),dp(42),Gravity.TOP|Gravity.CENTER_HORIZONTAL));
            status = text(12); status.setText("正在打开相机…");
            FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            statusParams.topMargin = dp(18); root.addView(status, statusParams);
            ScaleGestureDetector scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    if (!busy && !photographed) setZoom(zoom * detector.getScaleFactor());
                    return true;
                }
            });
            GestureDetector tap = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent event) {
                    if (!busy && !photographed) focus(event.getX(), event.getY());
                    return true;
                }
            });
            texture.setOnTouchListener((view, event) -> {
                android.view.ViewParent parent = view.getParent();
                int action = event.getActionMasked();
                if (parent != null && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)
                    parent.requestDisallowInterceptTouchEvent(true);
                scale.onTouchEvent(event);
                if (!scale.isInProgress() && event.getPointerCount() == 1) tap.onTouchEvent(event);
                if (parent != null && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL))
                    parent.requestDisallowInterceptTouchEvent(false);
                return true;
            });
            texture.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> updateTransform());
            root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> layoutCamera());
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) { if (!closed) finish(null, false); }
            });
            container.addView(root, new ViewGroup.LayoutParams(-1, -1));
            updateZoomLabel();
        }

        void layoutCamera() {
            if (closed || root.getWidth() == 0 || root.getHeight() == 0) return;
            // VTouch t9.d() limits portrait preview to 3:4, then S0 applies 10dp margins.
            // Fit that rectangle inside this card; never stretch it to remaining card height.
            int availableWidth = Math.max(1, root.getWidth()-dp(20));
            int availableHeight = Math.max(1, root.getHeight()-dp(116));
            boolean landscape = rotation == 0 || rotation == 180;
            float aspect = landscape ? 4f/3f : 3f/4f;
            int width=availableWidth, height=Math.round(width/aspect);
            if (height>availableHeight) { height=availableHeight; width=Math.round(height*aspect); }
            FrameLayout.LayoutParams box=(FrameLayout.LayoutParams)previewBox.getLayoutParams();
            if(box.width!=width || box.height!=height) { box.width=width;box.height=height;previewBox.setLayoutParams(box); }
            FrameLayout.LayoutParams zoomParams=(FrameLayout.LayoutParams)zoomLabel.getLayoutParams();
            int top=dp(10)+height-dp(52);
            if(zoomParams.topMargin!=top) { zoomParams.topMargin=top;zoomLabel.setLayoutParams(zoomParams); }
            FrameLayout.LayoutParams controls=(FrameLayout.LayoutParams)actions.getLayoutParams();
            int bottom=Math.max(dp(12),Math.min(dp(75),root.getHeight()-dp(10)-height-dp(96)));
            if(controls.bottomMargin!=bottom) { controls.bottomMargin=bottom;actions.setLayoutParams(controls); }
        }

        void configure() {
            if (closed || camera == null || !texture.isAvailable() || captureSession != null || configuring) return;
            configuring = true;
            try {
                SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
                if (surfaceTexture == null) { configuring = false; return; }
                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                if (previewSurface != null) previewSurface.release();
                previewSurface = new Surface(surfaceTexture);
                repeating = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                repeating.addTarget(previewSurface);
                repeating.set(CaptureRequest.CONTROL_AF_MODE, previewAfMode());
                applyZoom(repeating);
                // Preserve HAL template AE/FPS/stabilization defaults. In particular do not
                // force a fixed FPS or vendor camera mode on a ported camera stack.
                Log.i(TAG, "configure preview=" + previewSize + " jpeg=" + jpegSize
                        + " fallback=" + fallbackStreams + " af=" + repeating.get(CaptureRequest.CONTROL_AF_MODE)
                        + " ae=" + repeating.get(CaptureRequest.CONTROL_AE_MODE)
                        + " fps=" + repeating.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                        + " ratio=" + repeating.get(CaptureRequest.CONTROL_ZOOM_RATIO)
                        + " crop=" + repeating.get(CaptureRequest.SCALER_CROP_REGION));
                final boolean withJpeg = requestJpegSession;
                // Keep the initial viewfinder on the minimal preview-only HAL pipeline.
                // Add the JPEG output only after an actual shutter press.
                camera.createCaptureSession(withJpeg ? Arrays.asList(previewSurface, reader.getSurface())
                        : Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        configuring = false;
                        if (closed) { session.close(); return; }
                        captureSession = session;
                        jpegSession = withJpeg;
                        try {
                            Log.i(TAG, "onConfigured preview=" + previewSize + " jpeg=" + jpegSize + " jpegAttached=" + withJpeg);
                            session.setRepeatingRequest(repeating.build(), previewCallback, worker);
                            if (busy && withJpeg) captureStill();
                            MAIN.post(() -> { if (!closed) { status.setVisibility(View.GONE); updateTransform(); } });
                        } catch (Throwable error) { fail(error); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        configuring = false; session.close();
                        if (closed) return;
                        if (!fallbackStreams) {
                            // Some HALs advertise JPEG/preview sizes that cannot be combined.
                            // Retry once using smaller sizes from the same advertised lists.
                            fallbackStreams = true;
                            try {
                                jpegSize = chooseSmall(jpegSizes, 2048L * 1536, jpegSize);
                                previewSize = chooseSmall(previewSizes, 640L * 480, previewSize);
                                Log.w(TAG, "retry smaller streams preview=" + previewSize + " jpeg=" + jpegSize);
                                reader.close();
                                reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), android.graphics.ImageFormat.JPEG, 2);
                                reader.setOnImageAvailableListener(Session.this::onImage, worker);
                                MAIN.post(() -> { if (!closed) updateTransform(); });
                                configure();
                                return;
                            } catch (Throwable error) { fail(error); return; }
                        }
                        fail(new IllegalStateException("Camera stream configuration failed, including smaller-stream retry"));
                    }
                }, worker);
            } catch (Throwable error) { configuring = false; fail(error); }
        }

        boolean effectivelyVisible() {
            if (root == null || !root.isAttachedToWindow() || !root.isShown()
                    || root.getWindowVisibility() != View.VISIBLE) return false;
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null && !power.isInteractive()) return false;
            View node = root;
            while (node != null) {
                if (node.getVisibility() != View.VISIBLE || node.getAlpha() <= .001f) return false;
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            Rect visible = new Rect();
            return root.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0;
        }

        void visibilityChanged() {
            // Defer the check until ViewRoot has finished dispatching aggregate visibility.
            // The periodic monitor also catches ancestor alpha fades and carousel clipping.
            if (!closed && visibleDeadline != 0) {
                MAIN.removeCallbacks(visibilityMonitor);
                MAIN.post(visibilityMonitor);
            }
        }

        int previewAfMode() {
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (has(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            if (has(modes, CaptureRequest.CONTROL_AF_MODE_AUTO)) return CaptureRequest.CONTROL_AF_MODE_AUTO;
            return CaptureRequest.CONTROL_AF_MODE_OFF;
        }

        void updateTransform() {
            if (closed || previewSize == null || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
            Display display = container.getDisplay();
            int turn = display == null ? Surface.ROTATION_0 : display.getRotation();
            int degrees = turn == Surface.ROTATION_90 ? 90 : turn == Surface.ROTATION_180 ? 180 : turn == Surface.ROTATION_270 ? 270 : 0;
            rotation = (sensorOrientation - degrees + 360) % 360;
            float width = texture.getWidth(), height = texture.getHeight();
            Matrix matrix = new Matrix(); matrix.setRotate(rotation);
            RectF bounds = new RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());
            matrix.mapRect(bounds); matrix.postTranslate(-bounds.left, -bounds.top);
            float factor = Math.max(width / bounds.width(), height / bounds.height());
            matrix.postScale(factor, factor);
            matrix.postTranslate((width - bounds.width() * factor) / 2, (height - bounds.height() * factor) / 2);
            synchronized (bufferToView) { bufferToView.set(matrix); }
            // bufferToView above is ONLY the sensor-coordinate map used for tap metering.
            // Camera's SurfaceTexture producer already applies sensor orientation. Applying
            // that rotation again to TextureView both turns the picture and distorts axes.
            // Use the Camera2 display-rotation transform for rendering instead.
            Matrix transform = new Matrix();
            float centerX=width/2, centerY=height/2;
            if (turn == Surface.ROTATION_90 || turn == Surface.ROTATION_270) {
                RectF viewRect = new RectF(0,0,width,height);
                RectF bufferRect = new RectF(0,0,previewSize.getHeight(),previewSize.getWidth());
                bufferRect.offset(centerX-bufferRect.centerX(),centerY-bufferRect.centerY());
                transform.setRectToRect(viewRect,bufferRect,Matrix.ScaleToFit.FILL);
                float scale=Math.max(height/previewSize.getHeight(),width/previewSize.getWidth());
                transform.postScale(scale,scale,centerX,centerY);
                transform.postRotate(90*(turn-2),centerX,centerY);
            } else {
                // Usually exactly 3:4; preserve aspect even on a HAL offering another size.
                boolean swapped=sensorOrientation==90 || sensorOrientation==270;
                float naturalWidth=swapped?previewSize.getHeight():previewSize.getWidth();
                float naturalHeight=swapped?previewSize.getWidth():previewSize.getHeight();
                float scale=Math.max(width/naturalWidth,height/naturalHeight);
                transform.setScale(naturalWidth*scale/width,naturalHeight*scale/height,centerX,centerY);
                if(turn==Surface.ROTATION_180) transform.postRotate(180,centerX,centerY);
            }
            texture.setTransform(transform);
            layoutCamera();
            Log.i(TAG, "transform card=" + container.getWidth() + "x" + container.getHeight()
                    + " viewport=" + (int)width + "x" + (int)height + " rotation=" + rotation
                    + " buffer=" + previewSize);
        }

        Rect zoomCrop() {
            float effectiveZoom = Math.max(1, zoom);
            int width = Math.max(2, (int) (activeArray.width() / effectiveZoom));
            int height = Math.max(2, (int) (activeArray.height() / effectiveZoom));
            int left = activeArray.centerX() - width / 2, top = activeArray.centerY() - height / 2;
            return new Rect(left, top, left + width, top + height);
        }

        void applyZoom(CaptureRequest.Builder builder) {
            // At startup leave the template's 1x defaults untouched. A logical-camera HAL
            // can select another pipeline even when an app redundantly sets ratio=1.
            if (Math.abs(zoom - 1f) < .0001f) {
                if (explicitZoom) {
                    if (ratioZoom) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
                    else builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(activeArray));
                }
                return;
            }
            if (ratioZoom) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            else builder.set(CaptureRequest.SCALER_CROP_REGION, zoomCrop());
        }

        void setZoom(float requested) {
            if (closed || photographed || busy || !Float.isFinite(requested)) return;
            float next = clamp(requested, minZoom, maxZoom);
            if (Math.abs(next - zoom) > .0001f) explicitZoom = true;
            zoom = next; updateZoomLabel();
            if (worker != null) worker.post(() -> {
                if (closed || repeating == null || captureSession == null) return;
                try {
                    applyZoom(repeating);
                    repeating.set(CaptureRequest.CONTROL_AF_REGIONS, null);
                    repeating.set(CaptureRequest.CONTROL_AE_REGIONS, null);
                    repeating.set(CaptureRequest.CONTROL_AF_MODE, previewAfMode());
                    repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    captureSession.setRepeatingRequest(repeating.build(), previewCallback, worker);
                } catch (Throwable error) { fail(error); }
            });
        }

        void updateZoomLabel() {
            if (zoomLabel != null) zoomLabel.setText(String.format(Locale.ROOT, "%.1f×", zoom));
        }

        void focus(float viewX, float viewY) {
            Matrix inverse = new Matrix();
            synchronized (bufferToView) { if (!bufferToView.invert(inverse)) return; }
            float[] point = {viewX, viewY}; inverse.mapPoints(point);
            float x = clamp(point[0] / previewSize.getWidth(), 0, 1);
            float y = clamp(point[1] / previewSize.getHeight(), 0, 1);
            // With zoom-ratio control, Camera2 metering coordinates remain in the active-array
            // coordinate system. With legacy crop control they refer to the cropped sensor area.
            Rect source = ratioZoom ? new Rect(activeArray) : zoomCrop();
            float aspect = (float) previewSize.getWidth() / previewSize.getHeight();
            if ((float) source.width() / source.height() > aspect) {
                int width = (int) (source.height() * aspect);
                source.left = source.centerX() - width / 2; source.right = source.left + width;
            } else {
                int height = (int) (source.width() / aspect);
                source.top = source.centerY() - height / 2; source.bottom = source.top + height;
            }
            int centerX = source.left + (int) (x * source.width());
            int centerY = source.top + (int) (y * source.height());
            MeteringRectangle af = meter(source, centerX, centerY, .15f);
            MeteringRectangle ae = meter(source, centerX, centerY, .25f);
            focus.show(viewX, viewY);
            worker.post(() -> {
                if (closed || busy || photographed || repeating == null || captureSession == null) return;
                try {
                    Integer maxAf = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                    Integer maxAe = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
                    if (maxAf != null && maxAf > 0) repeating.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{af});
                    if (maxAe != null && maxAe > 0) repeating.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{ae});
                    int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                    if (has(modes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                        repeating.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                        repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                        captureSession.capture(repeating.build(), null, worker);
                    }
                    repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    captureSession.setRepeatingRequest(repeating.build(), previewCallback, worker);
                } catch (Throwable error) { fail(error); }
            });
        }

        void shoot() {
            if (closed || busy || photographed || captureSession == null || camera == null) return;
            busy = true; shutter.setEnabled(false); status.setText("正在拍照…"); status.setVisibility(View.VISIBLE);
            long serial = ++captureSerial;
            worker.post(() -> {
                if (closed) return;
                if (!jpegSession) {
                    try {
                        requestJpegSession = true;
                        captureSession.stopRepeating(); captureSession.close(); captureSession = null;
                        configure();
                    } catch (Throwable error) { fail(error); }
                    return;
                }
                captureStill();
            });
            MAIN.postDelayed(() -> { if (!closed && busy && captureSerial == serial) fail(new IllegalStateException("Photo capture timed out")); }, 8000);
        }

        void captureStill() {
                if (closed || !busy) return;
                try {
                    CaptureRequest.Builder capture = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    capture.addTarget(reader.getSurface());
                    capture.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    capture.set(CaptureRequest.CONTROL_AF_MODE, repeating.get(CaptureRequest.CONTROL_AF_MODE));
                    capture.set(CaptureRequest.CONTROL_AF_REGIONS, repeating.get(CaptureRequest.CONTROL_AF_REGIONS));
                    capture.set(CaptureRequest.CONTROL_AE_REGIONS, repeating.get(CaptureRequest.CONTROL_AE_REGIONS));
                    capture.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    capture.set(CaptureRequest.JPEG_ORIENTATION, rotation);
                    capture.set(CaptureRequest.JPEG_QUALITY, (byte) 95); applyZoom(capture);
                    captureSession.capture(capture.build(), new CameraCaptureSession.CaptureCallback() {
                        @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                            fail(new IllegalStateException("Photo capture failed: " + failure.getReason()));
                        }
                    }, worker);
                } catch (Throwable error) { fail(error); }
        }

        void onImage(ImageReader source) {
            try (Image image = source.acquireNextImage()) {
                if (image == null || closed || !busy) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer(); byte[] jpeg = new byte[buffer.remaining()]; buffer.get(jpeg);
                if (jpeg.length == 0) throw new IllegalStateException("Camera returned an empty image");
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create photo directory");
                try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(jpeg); stream.getFD().sync(); }
                // Normalize the full-resolution pixels, not the sampled confirmation image.
                // The original attachment renderer uses BitmapFactory and ignores EXIF.
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bitmap == null) throw new IllegalStateException("Camera JPEG cannot be decoded");
                int exif = new ExifInterface(output.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                Matrix orient = new Matrix();
                if (exif == ExifInterface.ORIENTATION_ROTATE_90) orient.setRotate(90);
                else if (exif == ExifInterface.ORIENTATION_ROTATE_180) orient.setRotate(180);
                else if (exif == ExifInterface.ORIENTATION_ROTATE_270) orient.setRotate(270);
                else if (exif == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) orient.setScale(-1,1);
                else if (exif == ExifInterface.ORIENTATION_FLIP_VERTICAL) orient.setScale(1,-1);
                else if (exif == ExifInterface.ORIENTATION_TRANSPOSE) { orient.setRotate(90);orient.postScale(-1,1); }
                else if (exif == ExifInterface.ORIENTATION_TRANSVERSE) { orient.setRotate(-90);orient.postScale(-1,1); }
                if (!orient.isIdentity()) {
                    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), orient, true);
                    if (rotated != bitmap) bitmap.recycle(); bitmap = rotated;
                }
                Bitmap result;
                try {
                    try (FileOutputStream normalized = new FileOutputStream(output)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG,95,normalized))
                            throw new IllegalStateException("Cannot encode upright photo");
                        normalized.getFD().sync();
                    }
                    ExifInterface normalizedExif=new ExifInterface(output.getAbsolutePath());
                    normalizedExif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_NORMAL));
                    normalizedExif.saveAttributes();
                    Log.i(TAG,"normalized JPEG exif="+exif+" fullPixels="+bitmap.getWidth()+"x"+bitmap.getHeight());
                    result=Bitmap.createScaledBitmap(bitmap,Math.max(1,bitmap.getWidth()/2),Math.max(1,bitmap.getHeight()/2),true);
                } finally { bitmap.recycle(); }
                if (captureSession != null) {
                    try { captureSession.stopRepeating(); } catch (Throwable stopError) { Log.w(TAG,"Cannot stop frozen preview",stopError); }
                }
                MAIN.post(() -> {
                    if (closed) { result.recycle(); return; }
                    busy = false; photographed = true; frozenBitmap = result;
                    still.setImageBitmap(result); still.setVisibility(View.VISIBLE); focus.hide();
                    status.setVisibility(View.GONE); shutter.setVisibility(View.GONE);
                    retake.setVisibility(View.VISIBLE); confirm.setVisibility(View.VISIBLE);
                    zoomLabel.setVisibility(View.GONE); closeButton.setVisibility(View.GONE);
                });
            } catch (Throwable error) { if (!closed) fail(error); }
        }

        void retake() {
            if (closed || busy) return;
            photographed = false; still.setImageDrawable(null); still.setVisibility(View.GONE);
            if (frozenBitmap != null) { frozenBitmap.recycle(); frozenBitmap = null; }
            retake.setVisibility(View.GONE); confirm.setVisibility(View.GONE);
            shutter.setVisibility(View.VISIBLE); shutter.setEnabled(true);
            zoomLabel.setVisibility(View.VISIBLE); closeButton.setVisibility(View.VISIBLE);
            worker.post(() -> {
                if (closed) return;
                try {
                    requestJpegSession = false;
                    if (captureSession != null) { captureSession.close(); captureSession = null; }
                    configure();
                } catch (Throwable error) { fail(error); }
            });
        }

        void fail(Throwable error) {
            Log.e(TAG, "Embedded camera failed", error);
            MAIN.post(() -> finish(error, false));
        }

        synchronized void finish(Throwable error, boolean saved) {
            if (closed) return;
            closed = true;
            // Never remove a view from inside TextureView's destruction/detach callback.
            // Re-entering releaseSurfaceTexture there can null its field before framework
            // release completes and crash the hosting SystemUI process.
            MAIN.post(() -> finishOnMain(error, saved));
        }

        void finishOnMain(Throwable error, boolean saved) {
            MAIN.removeCallbacks(visibilityMonitor);
            Log.i(TAG, "closing camera saved=" + saved + " previewFrames=" + previewFrames
                    + " textureFrames=" + textureFrames + " error=" + (error == null ? "none" : error));
            if (root != null && root.getParent() == container) container.removeView(root);
            if (still != null) still.setImageDrawable(null);
            if (frozenBitmap != null) { frozenBitmap.recycle(); frozenBitmap = null; }
            Runnable cleanup = () -> {
                try { if (captureSession != null) captureSession.close(); } catch (Throwable ignored) {}
                try { if (camera != null) camera.close(); } catch (Throwable ignored) {}
                try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
                try { if (previewSurface != null) previewSurface.release(); } catch (Throwable ignored) {}
                captureSession = null; camera = null; reader = null; previewSurface = null;
                if (!saved && output != null && output.isFile()) output.delete();
                // Keep the callback looper alive until an in-flight open returns so its
                // CameraDevice can be closed even when the card was dismissed meanwhile.
                if (!opening) thread.quitSafely();
                MAIN.post(() -> {
                    try {
                        if (error != null) callback.onError(error);
                        else if (saved) callback.onSaved(output);
                        else callback.onCancelled();
                    } catch (Throwable callbackError) { Log.e(TAG, "Camera callback failed", callbackError); }
                    finally {
                        // The original finish callback posts a 300ms container-hide action.
                        // Let that finish before a new photo action can reuse the same view.
                        MAIN.postDelayed(() -> { if (current == this) current = null; },350);
                    }
                });
            };
            if (worker == null) cleanup.run();
            else if (!worker.post(cleanup)) new Thread(cleanup, "MemoryCameraCleanup").start();
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            updateTransform(); if (worker != null) worker.post(this::configure);
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { updateTransform(); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { if (!closed) finish(null, false); return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            if (++textureFrames == 1) {
                float[] producer=new float[16];surface.getTransformMatrix(producer);
                Log.i(TAG, "first TextureView frame, viewport=" + texture.getWidth() + "x" + texture.getHeight()
                        + " producerMatrix="+Arrays.toString(producer));
            }
        }
        int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
        TextView actionText(String label) {
            TextView view=text(16);view.setText(label);view.setGravity(Gravity.CENTER);
            view.setTypeface(null,android.graphics.Typeface.BOLD);view.setBackground(roundRipple(0));
            return view;
        }
        Drawable roundRipple(int color) {
            GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);shape.setColor(color);
            GradientDrawable mask=new GradientDrawable();mask.setShape(GradientDrawable.OVAL);mask.setColor(Color.WHITE);
            return new RippleDrawable(ColorStateList.valueOf(0x44ffffff),shape,mask);
        }
        ImageView icon(String name,String description) {
            ImageView view=new ImageView(context);view.setContentDescription(description);view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try {
                Context module=context.createPackageContext("dev.local.supercardhost",0);
                int id=module.getResources().getIdentifier(name,"drawable","dev.local.supercardhost");
                if(id==0) throw new IllegalStateException("Missing camera drawable "+name);
                view.setImageDrawable(module.getResources().getDrawable(id,module.getTheme()));
            } catch(Throwable error) { throw new IllegalStateException("Cannot load original vivo camera resources",error); }
            return view;
        }
        TextView text(float size) { TextView view = new TextView(context); view.setTextColor(Color.WHITE); view.setTextSize(size); return view; }
    }

    private static MeteringRectangle meter(Rect bounds, int x, int y, float fraction) {
        int width = Math.max(2, Math.round(bounds.width() * fraction));
        int height = Math.max(2, Math.round(bounds.height() * fraction));
        int left = Math.max(bounds.left, Math.min(x - width / 2, bounds.right - width));
        int top = Math.max(bounds.top, Math.min(y - height / 2, bounds.bottom - height));
        return new MeteringRectangle(left, top, width, height, MeteringRectangle.METERING_WEIGHT_MAX);
    }

    private static Size chooseJpeg(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("No JPEG stream available");
        Size best = sizes[0]; double score = Double.MAX_VALUE;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            double candidate = Math.abs((double) size.getWidth() / size.getHeight() - 4d / 3d) * 100
                    + Math.abs(Math.log(Math.max(1, area) / 5000000d)) + (area > 12000000 ? 100 : 0);
            if (candidate < score) { score = candidate; best = size; }
        }
        return best;
    }

    private static Size choosePreview(Size[] sizes, Size jpeg) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("No preview stream available");
        Size best = sizes[0]; double score = Double.MAX_VALUE;
        double aspect = (double) jpeg.getWidth() / jpeg.getHeight();
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            double candidate = Math.abs((double) size.getWidth() / size.getHeight() - aspect) * 100
                    + Math.abs(Math.log(Math.max(1, area) / (1280d * 960d))) + (area > 1920L * 1440 ? 100 : 0);
            if (candidate < score) { score = candidate; best = size; }
        }
        return best;
    }

    private static Size chooseSmall(Size[] sizes, long maximumArea, Size aspectReference) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("No smaller camera stream available");
        Size best = sizes[0]; double score = Double.MAX_VALUE;
        double aspect = (double) aspectReference.getWidth() / aspectReference.getHeight();
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            double candidate = Math.abs((double) size.getWidth() / size.getHeight() - aspect) * 10
                    + Math.abs(Math.log(Math.max(1, area) / (double) maximumArea))
                    + (area > maximumArea ? 100 : 0);
            if (candidate < score) { score = candidate; best = size; }
        }
        return best;
    }

    private static boolean has(int[] values, int needle) { if (values != null) for (int value : values) if (value == needle) return true; return false; }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(value, high)); }

    private static final class LifecycleFrame extends FrameLayout {
        final Session owner;
        LifecycleFrame(Context context, Session owner) { super(context); this.owner = owner; }
        @Override public void onVisibilityAggregated(boolean visible) {
            super.onVisibilityAggregated(visible); if (owner != null) owner.visibilityChanged();
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility); if (owner != null) owner.visibilityChanged();
        }
        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility); if (owner != null) owner.visibilityChanged();
        }
    }

    private static final class FocusView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float x, y; boolean visible;
        FocusView(Context context) { super(context); paint.setColor(0xffffd35a); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density); }
        void show(float x, float y) { this.x = x; this.y = y; visible = true; invalidate(); removeCallbacks(hide); postDelayed(hide, 1500); }
        final Runnable hide = this::hide;
        void hide() { visible = false; invalidate(); }
        @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); if (visible) { float radius = 25 * getResources().getDisplayMetrics().density; canvas.drawRect(x-radius,y-radius,x+radius,y+radius,paint); } }
    }
}
