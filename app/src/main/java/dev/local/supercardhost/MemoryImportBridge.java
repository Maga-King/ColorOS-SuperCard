package dev.local.supercardhost;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * Runs the installed AIMemory importers in their own process and identity.
 *
 * Ordered action dev.local.supercardhost.MEMORY_IMPORT, explicit AIMemory package,
 * BroadcastOptions.setShareIdentityEnabled(true). Extras: op=import (or check/probe),
 * guid=UUID/32 hexadecimal digits, type=101/2401 image, 401 casual voice note, 501 document,
 * or 0 for a batch with types=ArrayList<Integer> aligned with uris=ArrayList<Uri>.
 * sourcePkg, sourceName and real read URI grants are required. A batch accepts at most
 * twelve images and one recording, stored in one original Memory, never split up.
 * Voice notes use the original casual-notes handler with asrSuccess=false and empty
 * transcription. The original subsequent pipeline decides whether to transcribe.
 *
 * RESULT_OK means every original Provider insert returned a nonempty memoryId.
 * It means local storage completed; later AI arrangement/cloud sync may continue.
 * Result extras: guid, status, error, memoryIds, canDeleteSources. A timeout never
 * permits source deletion. op=check also returns actual privacy/service/network
 * gates and, with a known guid, the latest import result (including late completion).
 */
public final class MemoryImportBridge {
    public static final String ACTION = "dev.local.supercardhost.MEMORY_IMPORT";
    private static final String PACKAGE = "com.oplus.aimemory";
    private static final String MODULE = "dev.local.supercardhost";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String TRANSFER = MODULE + ".memory-transfer";
    private static final String TAG = "SuperCardMemoryImport";
    private static final String ENTRY = PACKAGE + ".business_memory_entry.";
    private static final long REPLY_TIMEOUT_MS = 8_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SuperCardMemoryImport"); thread.setDaemon(true); return thread;
    });
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<String, Job> HISTORY = new LinkedHashMap<>();
    private static final Map<Object, Job> IMAGE_JOBS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Job active;
    private static volatile ClassLoader loader;
    private static volatile String installError;
    private static volatile boolean providerObserved, documentsObserved;
    private static volatile Method imageImport, manualImport;
    private static volatile Constructor<?> imageInfoConstructor, imageRequestConstructor, pairConstructor;
    private static volatile Method imageMemory, imageCleanup, nativeJson;
    private static volatile Constructor<?> attachmentConstructor;
    private static volatile boolean imageSaveObserved, voiceStoreObserved;
    private static volatile Object manualSingleton;
    private static volatile Method voiceStorageLow, voiceHandle;
    private static volatile Constructor<?> voiceSuccessConstructor;
    private static volatile Object voiceTrigger, voiceHandler, voiceCallback;
    private static volatile boolean audioObserved;
    private static volatile String audioError;

    private MemoryImportBridge() { }

    public static void install(XposedModule module, ClassLoader target) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        loader = target;
        try {
            resolveImporters();
            observeProvider(module);
            observeManualRequests(module);
            observeImageSaving(module);
            try { resolveVoiceImporter(); observeVoiceStore(module); }
            catch (Throwable error) { audioError = safeError(error); Log.w(TAG, "Voice importer unavailable: " + audioError); }
        } catch (Throwable error) {
            installError = safeError(error);
            Log.e(TAG, "Native importer discovery failed: " + installError);
        }
        try {
            Method onCreate = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) instanceof Application) register((Application) chain.getArg(0));
                return result;
            });
        } catch (Throwable error) {
            installError = safeError(error);
            Log.e(TAG, "Receiver bootstrap failed: " + installError);
        }
        // onPackageLoaded can run before Application exists. The lifecycle hook above is
        // authoritative; failure of this optional already-created fast path is harmless.
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getDeclaredMethod("currentApplication"); current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof Application) MAIN.post(() -> register((Application) app));
        } catch (Throwable ignored) { }
    }

    private static void resolveImporters() throws Exception {
        Class<?> image = load(ENTRY + "portal.usecase.PortalImageUseCase");
        Class<?> info = load(PACKAGE + ".utils.ImageInfo");
        imageInfoConstructor = info.getDeclaredConstructor(String.class, Uri.class, String.class,
                int.class, int.class, long.class);
        imageRequestConstructor = null;
        for (Class<?> nested : image.getDeclaredClasses()) {
            for (Constructor<?> constructor : nested.getDeclaredConstructors()) {
                if (Arrays.equals(constructor.getParameterTypes(), new Class<?>[]{List.class,
                        String.class, String.class, String.class, boolean.class})) {
                    if (imageRequestConstructor != null) throw new IllegalStateException("Ambiguous image request");
                    imageRequestConstructor = constructor;
                }
            }
        }
        if (imageRequestConstructor == null) throw new NoSuchMethodException("Image request signature changed");
        imageImport = suspendMethod(image, imageRequestConstructor.getDeclaringClass());
        Class<?> collectMemory = load(PACKAGE + ".scene.bean.DataCollectMemory");
        for (Method method : image.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (Arrays.equals(method.getParameterTypes(), new Class<?>[]{Uri.class, String.class, String.class})
                    && method.getReturnType() == collectMemory) {
                if (imageMemory != null) throw new IllegalStateException("Ambiguous image memory model");
                method.setAccessible(true); imageMemory = method;
            }
            if (method.getParameterCount() == 0 && method.getReturnType() == void.class) {
                if (imageCleanup != null) throw new IllegalStateException("Ambiguous image bitmap cleanup");
                method.setAccessible(true); imageCleanup = method;
            }
        }
        if (imageMemory == null || imageCleanup == null) throw new NoSuchMethodException("Image model signature changed");
        // @Keep Attachment: preserve the original model defaults and serialized field names.
        attachmentConstructor = load(PACKAGE + ".db.table.Attachment").getDeclaredConstructor(
                String.class, String.class, int.class, String.class, String.class, int.class, int.class,
                String.class, String.class, int.class, String.class, String.class, String.class, String.class,
                long.class, String.class, String.class, boolean.class, Integer.class, String.class,
                String.class, Long.class);
        for (Method method : load(PACKAGE + ".ext.GsonExtKt").getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == String.class
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{Object.class, boolean.class})) {
                if (nativeJson != null) throw new IllegalStateException("Ambiguous native JSON serializer");
                method.setAccessible(true); nativeJson = method;
            }
        }
        if (nativeJson == null) throw new NoSuchMethodException("Native serializer signature changed");
        Class<?> manual = load(ENTRY + "manual.MemoryManualImport");
        Class<?> pair = load("kotlin.Pair");
        pairConstructor = pair.getDeclaredConstructor(Object.class, Object.class);
        manualImport = suspendMethod(manual, Context.class, List.class, pair);
        manualSingleton = singleton(manual);
    }

    private static void register(Application app) {
        if (!PACKAGE.equals(app.getPackageName()) || !REGISTERED.compareAndSet(false, true)) return;
        try {
            final int moduleUid = app.getPackageManager().getPackageUid(MODULE, 0);
            final int systemUiUid = app.getPackageManager().getPackageUid(SYSTEM_UI, 0);
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    int uid = getSentFromUid();
                    String sender = getSentFromPackage();
                    boolean allowed = uid == systemUiUid && SYSTEM_UI.equals(sender)
                            || uid == moduleUid && MODULE.equals(sender);
                    if (!allowed || !isOrderedBroadcast() || intent == null
                            || !ACTION.equals(intent.getAction()) || !PACKAGE.equals(intent.getPackage())) {
                        Log.w(TAG, "Rejected unauthenticated memory request"); return;
                    }
                    PendingResult pending = goAsync();
                    AtomicBoolean replied = new AtomicBoolean();
                    MAIN.postDelayed(() -> {
                        Bundle timeout = failure(null, "timeout",
                                "原生导入尚未确认完成，请保留临时源文件");
                        reply(pending, replied, timeout);
                    }, REPLY_TIMEOUT_MS);
                    WORK.execute(() -> receive(app, intent, pending, replied));
                }
            }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
            Log.i(TAG, "Authenticated native memory receiver registered");
        } catch (Throwable error) {
            REGISTERED.set(false); installError = safeError(error);
            Log.e(TAG, "Receiver registration failed: " + installError);
        }
    }

    private static void receive(Application app, Intent intent, BroadcastReceiver.PendingResult pending,
            AtomicBoolean replied) {
        String guid = null;
        try {
            guid = intent.getStringExtra("guid");
            if (replied.get()) return;
            String operation = intent.getStringExtra("op");
            if ("probe".equals(operation)) {
                // Same authenticated ordered receiver; discovery only, no gates/importers invoked.
                reply(pending, replied, MemoryDexResolver.probe(loader,
                        intent.getBooleanExtra("forceDexKit", false))); return;
            }
            if ("check".equals(operation)) {
                Bundle state = readiness();
                synchronized (HISTORY) {
                    Job old = HISTORY.get(guid);
                    if (old != null) state.putAll(old.snapshot());
                }
                reply(pending, replied, state); return;
            }
            if (operation != null && !"import".equals(operation)) throw new IllegalArgumentException("Unknown operation");
            requireGuid(guid);
            int type = intent.getIntExtra("type", -1);
            String sourcePkg = text(intent, "sourcePkg", 180);
            String sourceName = text(intent, "sourceName", 256);
            if (!sourcePkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                throw new IllegalArgumentException("Invalid source package");
            }
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra("uris", Uri.class);
            if (uris == null || uris.isEmpty() || uris.size() > 13) throw new IllegalArgumentException("每条记忆最多 12 张图片和 1 段录音");
            Set<Uri> unique = new LinkedHashSet<>();
            for (Uri uri : uris) { validateUri(app, uri); if (!unique.add(uri)) throw new IllegalArgumentException("Duplicate URI"); }
            if (type != 0 && type != 101 && type != 2401 && type != 401 && type != 501) throw new IllegalArgumentException("Unsupported memory type");
            ArrayList<Integer> types = type == 0 ? intent.getIntegerArrayListExtra("types") : new ArrayList<>();
            if (type == 0 && (types == null || types.size() != uris.size())) {
                throw new IllegalArgumentException("批次 types 必须和 uris 等长");
            }
            if (type != 0) for (int i = 0; i < uris.size(); i++) types.add(type);
            int audioCount = 0, imageCount = 0;
            for (Integer itemType : types) {
                if (itemType == null) throw new IllegalArgumentException("Missing attachment type");
                if (itemType == 401) audioCount++;
                else if (itemType == 101 || itemType == 2401) imageCount++;
                else if (type != 501 || itemType != 501) throw new IllegalArgumentException("批次只支持图片和随口记录音");
            }
            if (audioCount > 1) {
                reply(pending, replied, failure(guid, "unsupported", "小布单条随口记只支持 1 段录音，请保留当前草稿")); return;
            }
            if (imageCount > 12 || type == 501 && uris.size() > 9) {
                reply(pending, replied, failure(guid, "unsupported", "每条最多 12 张图片；原生文档导入每批最多 9 个")); return;
            }
            if (audioCount != 0 && !voiceReady()) {
                reply(pending, replied, failure(guid, "unsupported_audio",
                        audioError == null ? "原生随口记入口未就绪" : audioError)); return;
            }
            if (type == 401 && uris.size() != 1) throw new IllegalArgumentException("One voice recording per request");
            Bundle ready = readiness();
            if (!ready.getBoolean("ready")) {
                reply(pending, replied, failure(guid, "not_ready", ready.getString("error"))); return;
            }
            if (type == 501 && !documentsObserved) throw new IllegalStateException("Document correlation hook unavailable");
            Job job;
            int attempt = 1;
            synchronized (HISTORY) {
                Job old = HISTORY.get(guid);
                if (old != null) {
                    if (intent.getBooleanExtra("retryPrewrite", false) && old.canRetryBeforeWrite()
                            && old.uris.equals(uris) && old.type == type
                            && old.attachmentTypes.equals(types)
                            && old.sourcePkg.equals(sourcePkg) && old.sourceName.equals(sourceName)) {
                        if (active != null) {
                            reply(pending, replied, failure(guid, "busy", "另一个原生导入仍在进行")); return;
                        }
                        HISTORY.remove(guid);
                        attempt = old.attempt + 1;
                        Log.i(TAG, "Retrying failed import before any native write, attempt=" + attempt + "/3");
                    } else { reply(pending, replied, old.snapshot()); return; }
                }
                if (active != null) {
                    reply(pending, replied, failure(guid, "busy", "另一个原生导入仍在进行")); return;
                }
                job = new Job(guid, type, sourcePkg, sourceName, new ArrayList<>(uris), types, pending, replied, attempt);
                active = job; HISTORY.put(guid, job);
                while (HISTORY.size() > 32) HISTORY.remove(HISTORY.keySet().iterator().next());
            }
            // Bound the active slot even when an OEM coroutine never resumes. No successful ack
            // is produced, and late provider responses still belong to this Job object.
            MAIN.postDelayed(() -> job.fail("timeout", "原生导入超时，源文件仍需保留"), 60_000);
            try {
                if (type == 501) importDocuments(app, job);
                else if (!job.imageUris.isEmpty()) importImages(app, job);
                else importVoice(app, job);
            } catch (Throwable error) { job.fail("native_error", safeError(error)); }
        } catch (Throwable error) { reply(pending, replied, failure(guid, "invalid_request", safeError(error))); }
    }

    private static Bundle readiness() {
        Bundle state = new Bundle();
        boolean privacy = false, service = false, network = false;
        boolean requiresSetup = false;
        String error = installError;
        try {
            privacy = nativeBoolean("privacy");
            service = nativeBoolean("service");
            network = nativeBoolean("network");
            if (error == null && !privacy) { error = "小布记忆隐私协议尚未同意"; requiresSetup = true; }
            if (error == null && !service) { error = "小布记忆服务开关已关闭"; requiresSetup = true; }
            if (error == null && !network) error = "原生导入要求网络连接";
            if (error == null && (!providerObserved || !imageSaveObserved || imageImport == null)) error = "原生导入接口未就绪";
        } catch (Throwable failure) { error = safeError(failure); }
        state.putBoolean("privacyEnabled", privacy); state.putBoolean("serviceEnabled", service);
        state.putBoolean("requiresSetup", requiresSetup);
        state.putBoolean("networkAvailable", network);
        state.putBoolean("supportsImages", providerObserved && imageSaveObserved && imageImport != null);
        state.putBoolean("supportsDocuments", providerObserved && documentsObserved && manualImport != null);
        state.putBoolean("supportsAudio", voiceReady());
        state.putBoolean("supportsMixed", voiceReady() && imageSaveObserved);
        state.putInt("maxImages", 12); state.putInt("maxAudio", 1); state.putInt("maxAttachments", 13);
        if (audioError != null) state.putString("audioError", audioError);
        state.putBoolean("ready", error == null); state.putString("status", error == null ? "ready" : "not_ready");
        if (error != null) state.putString("error", error);
        return state;
    }

    private static boolean voiceReady() {
        return audioError == null && audioObserved && voiceStoreObserved && voiceHandle != null && voiceStorageLow != null
                && voiceSuccessConstructor != null && voiceTrigger != null
                && voiceHandler != null && voiceCallback != null;
    }

    private static void importImages(Application app, Job job) throws Exception {
        ArrayList<Object> images = new ArrayList<>();
        for (Uri uri : job.imageUris) {
            Meta meta = metadata(app, uri);
            if (!Set.of("image/jpeg", "image/png", "image/webp", "image/heic").contains(meta.mime)) {
                throw new IllegalArgumentException("原生图片入口不支持此 MIME");
            }
            if (meta.size <= 0 || meta.size > 10L * 1024 * 1024) throw new IllegalArgumentException("图片超过原生 10 MiB 限制");
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("Unreadable image");
                BitmapFactory.decodeStream(input, null, options);
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IllegalArgumentException("Cannot decode image dimensions");
            images.add(imageInfoConstructor.newInstance(meta.name, uri, meta.mime,
                    options.outWidth, options.outHeight, meta.size));
            // The original SaveDataTask keeps these IDs while copying and assigns its
            // newly generated Memory ID. They allow every source image to be verified.
            String id = UUID.nameUUIDFromBytes((job.guid + ":image:" + job.imageParts.size())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            job.imageParts.add(new ImagePart(uri, id, meta, options.outWidth, options.outHeight));
            job.imageIds.add(id);
        }
        Object request = imageRequestConstructor.newInstance(images, job.sourcePkg, job.sourceName, job.guid, false);
        Object useCase = imageImport.getDeclaringClass().getDeclaredConstructor().newInstance();
        job.imageUseCase = useCase;
        IMAGE_JOBS.put(useCase, job);
        invokeSuspend(imageImport, useCase, new Object[]{request}, result -> {
            // Original format/size/resolution/network checks have run. Its normal
            // per-image saves were intercepted only for this use-case instance.
            cleanupImages(job, useCase);
            WORK.execute(() -> {
                if (job.isComplete()) return;
                try {
                    String state = nativeState(result);
                    if ((!"IMAGE_SINGLE_SUCCESS".equals(state) && !"IMAGE_MULTI_SUCCESS".equals(state))
                            || !job.allImagesValidated()) {
                        job.fail("native_rejected", state); return;
                    }
                    job.imagesValidated = true;
                    stageImages(app, job);
                    if (job.hasVoice()) importVoice(app, job);
                    else saveImagesAsOne(app, job, useCase);
                } catch (Throwable error) { job.deleteImageCopies(); job.fail("native_error", safeError(error)); }
            });
        }, error -> { cleanupImages(job, useCase); job.fail("native_error", safeError(error)); });
    }

    private static void observeImageSaving(XposedModule module) throws Exception {
        Method save = null;
        for (Method method : imageImport.getDeclaringClass().getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{Uri.class, String.class, String.class})) {
                if (save != null) throw new IllegalStateException("Ambiguous per-image save entry");
                save = method;
            }
        }
        if (save == null) throw new NoSuchMethodException("Per-image save signature changed");
        module.hook(save).intercept(chain -> {
            Job job = IMAGE_JOBS.get(chain.getThisObject());
            if (job == null) return chain.proceed();
            // The native Portal importer normally creates N memories. This exact instance
            // is used only to validate our batch; all accepted images are saved below once.
            Object uri = chain.getArg(0);
            if (uri instanceof Uri && job.imageUris.contains(uri)
                    && job.sourcePkg.equals(chain.getArg(1)) && job.sourceName.equals(chain.getArg(2))) {
                job.imageValidated((Uri) uri);
            } else job.fail("native_error", "原生图片校验请求与当前批次不符");
            return null;
        });
        observeImageFinalization(module);
        imageSaveObserved = true;
    }

    private static void observeImageFinalization(XposedModule module) throws Exception {
        Class<?> model = imageMemory.getReturnType();
        Method create = model.getMethod("toMemoryWithDetails", String.class, String.class);
        if (create.getReturnType() != load(PACKAGE + ".db.bean.MemoryWithDetails")) {
            throw new IllegalStateException("Original memory finalization signature changed");
        }
        Method getExtra = model.getMethod("getExtraDataElement");
        Method getImportId = getExtra.getReturnType().getMethod("getImportId");
        Method getAttachments = model.getMethod("getAttachments");
        Method setScreenshot = model.getMethod("setScreenshot", String.class);
        Class<?> attachment = attachmentConstructor.getDeclaringClass();
        Method getId = attachment.getMethod("getAttachmentId");
        Method getUri = attachment.getMethod("getUri");
        module.hook(create).intercept(chain -> {
            Job job = active;
            if (job == null || job.imageUris.isEmpty()) return chain.proceed();
            Object extra = getExtra.invoke(chain.getThisObject());
            if (extra == null || !job.guid.equals(getImportId.invoke(extra))) return chain.proceed();
            Object list = getAttachments.invoke(chain.getThisObject());
            String firstId = job.imageParts.get(0).id;
            String uri = null;
            if (list instanceof List<?>) for (Object item : (List<?>) list) {
                if (attachment.isInstance(item) && firstId.equals(getId.invoke(item))) {
                    uri = (String) getUri.invoke(item); break;
                }
            }
            if (uri == null) throw new VerificationException("原生批次首张图片尚未复制");
            // A header and an attachment using different copies would show the first
            // picture twice in the native detail gallery. Reuse its actual saved URI.
            storedUri(job, uri);
            setScreenshot.invoke(chain.getThisObject(), uri);
            return chain.proceed();
        });
    }

    private static void stageImages(Application app, Job job) throws Exception {
        JSONArray preload = new JSONArray();
        File directory = new File(app.getCacheDir(), "voice_collect");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create native cache");
        Method uriForFile = load("androidx.core.content.FileProvider").getMethod("getUriForFile",
                Context.class, String.class, File.class);
        if (!Modifier.isStatic(uriForFile.getModifiers()) || uriForFile.getReturnType() != Uri.class) {
            throw new IllegalStateException("Native private-file URI signature changed");
        }
        for (ImagePart image : job.imageParts) {
            String suffix = image.meta.mime.equals("image/png") ? ".png" : image.meta.mime.equals("image/webp")
                    ? ".webp" : image.meta.mime.equals("image/heic") ? ".heic" : ".jpg";
            File copy = new File(directory, "supercard-" + job.guid.replace("-", "") + "-image-" + preload.length() + suffix);
            if (copy.exists()) throw new IllegalStateException("Image staging file already exists");
            job.imageCopies.add(copy);
            try (InputStream input = app.getContentResolver().openInputStream(image.uri);
                    FileOutputStream output = new FileOutputStream(copy)) {
                if (input == null) throw new IllegalArgumentException("Validated image became unreadable");
                byte[] bytes = new byte[32 * 1024]; int count; long total = 0;
                while ((count = input.read(bytes)) != -1) {
                    total += count;
                    if (total > image.meta.size || total > 10L * 1024 * 1024) {
                        throw new IllegalArgumentException("Validated image size changed");
                    }
                    output.write(bytes, 0, count);
                }
                if (total <= 0 || total != image.meta.size) throw new IllegalArgumentException("Validated image incomplete");
            }
            Uri uri = (Uri) uriForFile.invoke(null, app, "com.oplus.aimemory.fileprovider", copy);
            Object attachment = attachmentConstructor.newInstance(image.id, "associate_memory_id", 1,
                    null, uri.toString(), image.width, image.height, Integer.toString(preload.length()),
                    null, 0, null, null, null, null, 0L, "0", null, false, null, null, null, null);
            preload.put(new JSONObject(serialize(attachment)));
        }
        job.preloadImages = preload;
    }

    private static void cleanupImages(Job job, Object useCase) {
        if (useCase != null) {
            IMAGE_JOBS.remove(useCase);
            try { imageCleanup.invoke(useCase); }
            catch (Throwable error) { Log.w(TAG, "Native image bitmap cleanup failed: " + safeError(error)); }
            job.imageUseCase = null;
        }
    }

    private static String serialize(Object value) throws Exception {
        String json = (String) nativeJson.invoke(null, value, true);
        if (json == null || json.isEmpty()) throw new IllegalStateException("Native serialization failed");
        return json;
    }

    private static JSONObject batchExtra(Job job, String original) throws Exception {
        JSONObject extra = original == null || original.isEmpty() ? new JSONObject() : new JSONObject(original);
        extra.put("importId", job.guid);
        if (!job.imageUris.isEmpty()) {
            if (!job.imagesValidated || job.preloadImages == null
                    || job.preloadImages.length() != job.imageUris.size()) {
                throw new IllegalStateException("Images did not pass the original importer");
            }
            // SaveDataTask processes preloadImages for every Memory, including VoiceMemory.
            // Its cuiContent-image branch only handles the speech-assistant source.
            extra.put("preloadImages", job.preloadImages);
        }
        return extra;
    }

    private static void saveImagesAsOne(Application app, Job job, Object useCase) throws Exception {
        if (job.isComplete()) { job.deleteImageCopies(); return; }
        Bundle ready = readiness();
        if (!ready.getBoolean("ready")) { job.deleteImageCopies(); job.fail("not_ready", ready.getString("error")); return; }
        Object model = imageMemory.invoke(useCase, job.imageUris.get(0), job.sourcePkg, job.sourceName);
        JSONObject data = new JSONObject(serialize(model));
        JSONObject extra = batchExtra(job, data.optString("extraData"));
        data.put("extraData", extra.toString());
        // Native completeResult allows portal + valid extraData. SaveDataTask copies
        // preloadImages, then the narrow finalization hook supplies the real header URI.
        data.put("screenshot", "");
        Uri endpoint = Uri.parse("content://com.oplus.aimemory.provider.DataShareProvider/datacollectmemory");
        Bundle values = new Bundle(); values.putString("uri", endpoint.toString());
        values.putString("indexData", data.toString());
        // This is the Portal importer's own Provider call. CheckDataTask, SaveDataTask,
        // InsertMemoryTask and the Provider's privacy/service/caller checks remain native.
        job.writeAttempted = true;
        Bundle result = app.getContentResolver().call(endpoint, "insert", null, values);
        String id = result == null ? null : result.getString("memoryId");
        if (result == null || result.containsKey("errorCode") || id == null || id.isEmpty()) {
            job.deleteImageCopies(); job.fail("provider_rejected", "原生图片批次未确认入库"); return;
        }
        job.nativeCompleted(id);
        job.deleteImageCopies();
    }

    private static void importDocuments(Application app, Job job) throws Exception {
        for (Uri uri : job.uris) {
            Meta meta = metadata(app, uri);
            if (!Set.of("text/plain", "application/msword", "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(meta.mime)) {
                throw new IllegalArgumentException("原生文档入口不支持此 MIME");
            }
            if (meta.size <= 0 || meta.size > 50L * 1024 * 1024) throw new IllegalArgumentException("文档超过原生 50 MiB 限制");
        }
        DisplayMetrics metrics = new DisplayMetrics();
        app.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        Object size = pairConstructor.newInstance(metrics.widthPixels, metrics.heightPixels);
        job.writeAttempted = true;
        invokeSuspend(manualImport, manualSingleton, new Object[]{app, job.uris, size}, result -> {
            if (!job.documentTagged) job.fail("native_rejected", "原生文档请求未进入可验证的导入链");
            else job.nativeCompleted();
        }, error -> job.fail("native_error", safeError(error)));
    }

    private static void resolveVoiceImporter() throws Exception {
        String collect = PACKAGE + ".voicecollect.collect.";
        Class<?> trigger = load(PACKAGE + ".voicecollect.service.TriggerType");
        for (Object value : trigger.getEnumConstants()) {
            if ("VOICE_RECORD".equals(((Enum<?>) value).name())) voiceTrigger = value;
        }
        if (voiceTrigger == null) throw new IllegalStateException("Casual-note trigger changed");
        Class<?> recorder = load(collect + "VoiceRecorder");
        for (Class<?> parent : recorder.getDeclaredClasses()) {
            for (Class<?> nested : parent.getDeclaredClasses()) {
                for (Constructor<?> constructor : nested.getDeclaredConstructors()) {
                    if (Arrays.equals(constructor.getParameterTypes(), new Class<?>[]{File.class,
                            long.class, boolean.class, String.class, List.class})) {
                        if (voiceSuccessConstructor != null) throw new IllegalStateException("Ambiguous voice result model");
                        voiceSuccessConstructor = constructor;
                    }
                }
            }
        }
        if (voiceSuccessConstructor == null) throw new NoSuchMethodException("Voice result signature changed");
        Class<?> handler = load(collect + "VoiceRecordResultHandler");
        for (Method method : handler.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (p.length == 7 && method.getReturnType() == Object.class && p[0] == Context.class
                    && p[1] == long.class && p[2] == voiceSuccessConstructor.getDeclaringClass()
                    && p[3] == trigger && p[4] == List.class && p[5].isInterface() && isContinuation(p[6])) {
                if (voiceHandle != null) throw new IllegalStateException("Ambiguous voice handler");
                method.setAccessible(true); voiceHandle = method;
            }
        }
        if (voiceHandle == null) throw new NoSuchMethodException("Voice handler signature changed");
        voiceHandler = singleton(handler);
        // Exactly the callback used by VoiceCollectionDetailFragment's own recording UI.
        voiceCallback = singleton(MemoryDexResolver.voiceCallback(loader, voiceHandle, false));
        if (!voiceHandle.getParameterTypes()[5].isInstance(voiceCallback)) {
            throw new IllegalStateException("Casual-note callback changed");
        }
        // Native VoiceCollect Utils.isStorageLow(long), selected by the complete signature.
        // Saving a finished recording follows the collection page's result handler;
        // it must not ask VoiceCollector whether starting a new capture is possible.
        voiceStorageLow = MemoryDexResolver.storageGuard(loader, false);
    }

    private static void observeVoiceStore(XposedModule module) throws Exception {
        Class<?> dataType = load(PACKAGE + ".voicecollect.schema.PreprocessData");
        Method save = suspendMethod(load(PACKAGE + ".voicecollect.store.DataStoreManager"),
                Context.class, dataType, String.class, Boolean.class, int.class);
        String[] names = {"PackageName", "ActivityName", "CollectTime", "CollectTimestamp", "UpdateTime",
                "AppName", "DataText", "DataTitle", "DataEntity", "DataCategory", "Screenshot", "Deeplink",
                "DataAbstract", "SceneName", "Thing", "ExtraData", "SecurityResult", "ScheduleSecurityResult",
                "AudioFile", "ResultType"};
        Method[] getters = new Method[names.length];
        Class<?>[] signature = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            getters[i] = dataType.getMethod("get" + names[i]);
            signature[i] = getters[i].getReturnType();
        }
        Method copy = dataType.getMethod("copy", signature);
        if (copy.getReturnType() != dataType || signature[0] != String.class || signature[15] != String.class
                || signature[18] != String.class || signature[16] != int.class || signature[17] != int.class) {
            throw new IllegalStateException("Original voice copy signature changed");
        }
        module.hook(save).intercept(chain -> {
            Job job = active;
            Object model = chain.getArg(1);
            if (job == null || !job.hasVoice() || job.voiceCopy == null || !dataType.isInstance(model)) {
                return chain.proceed();
            }
            // Never change another recording, a result edit, or a native in-app capture.
            String audioString = (String) getters[18].invoke(model);
            if (audioString == null) return chain.proceed();
            Uri audio = Uri.parse(audioString);
            if (!"content".equals(audio.getScheme()) || !"com.oplus.aimemory.fileprovider".equals(audio.getAuthority())
                    || !job.voiceCopy.getName().equals(audio.getLastPathSegment())
                    || !"VoiceMemory".equals(getters[0].invoke(model)) || chain.getArg(2) != null) {
                return chain.proceed();
            }
            try {
                if (job.isComplete()) throw new IllegalStateException("Voice batch expired before storing");
                Object[] fields = new Object[getters.length];
                for (int i = 0; i < getters.length; i++) fields[i] = getters[i].invoke(model);
                JSONObject original = new JSONObject((String) fields[15]);
                if (!"5".equals(original.optString("triggerType"))
                        || !original.has("asrSuccess") || original.optBoolean("asrSuccess", true)) {
                    throw new IllegalStateException("Original casual-note metadata changed");
                }
                fields[15] = batchExtra(job, original.toString()).toString();
                Object[] args = chain.getArgs().toArray();
                args[1] = copy.invoke(model, fields);
                job.voiceTagged = true;
                return chain.proceed(args);
            } catch (Throwable error) {
                job.fail("native_error", safeError(error)); throw error;
            }
        });
        voiceStoreObserved = true;
    }

    private static void importVoice(Application app, Job job) throws Exception {
        logVoiceCaptureGates(app);
        // Recheck the same current privacy, service and network gates enforced for native
        // imports. Provider.insert still performs its own privacy/caller/model validation.
        Bundle state = readiness();
        if (!state.getBoolean("ready")) {
            job.deleteImageCopies(); job.fail("not_ready", state.getString("error")); return;
        }
        if (Boolean.TRUE.equals(voiceStorageLow.invoke(null, 10L))) {
            job.deleteImageCopies(); job.fail("native_rejected", "小布原生存储检查未通过：可用空间不足 10 MiB"); return;
        }
        if (!job.isComplete()) {
            try { saveVoiceAfterGuard(app, job); }
            catch (Exception error) { job.deleteVoiceCopy(); job.deleteImageCopies(); throw error; }
        } else job.deleteImageCopies();
    }

    private static void logVoiceCaptureGates(Application app) {
        // These are diagnostics for the old, inappropriate start-recording guard only.
        // Never log the current page, source URI, audio content or a transcription.
        try {
            Class<?> settings = load(PACKAGE + ".voicecollect.utils.r");
            boolean introductionMissing = nativeContextBoolean(settings, "c", app);
            boolean legacyPrivacy = nativeContextBoolean(settings, "a", app);
            boolean captureSwitch = nativeContextBoolean(settings, "g", app);
            android.media.AudioManager audio = app.getSystemService(android.media.AudioManager.class);
            int activeCount = -1;
            try { if (audio != null) activeCount = audio.getActiveRecordingConfigurations().size(); }
            catch (RuntimeException ignored) { }
            Log.i(TAG, "Finished voice import: captureIntroductionMissing=" + introductionMissing
                    + ", captureLegacyPrivacy=" + legacyPrivacy + ", captureSwitch=" + captureSwitch
                    + ", micPermission=" + (app.checkSelfPermission("android.permission.RECORD_AUDIO") == 0)
                    + ", audioMode=" + (audio == null ? -1 : audio.getMode())
                    + ", activeRecordings=" + activeCount);
        } catch (Throwable error) {
            Log.w(TAG, "Capture-only gate diagnostics unavailable: " + safeError(error));
        }
    }

    private static boolean nativeContextBoolean(Class<?> type, String name, Context context) throws Exception {
        Method method = type.getDeclaredMethod(name, Context.class);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class) {
            throw new IllegalStateException("Native diagnostic signature changed");
        }
        method.setAccessible(true); return (Boolean) method.invoke(null, context);
    }

    private static void saveVoiceAfterGuard(Application app, Job job) throws Exception {
        Uri source = job.voiceUri;
        Meta meta = metadata(app, source);
        if (!meta.mime.startsWith("audio/") || meta.size > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("Expected an audio recording up to 20 MiB");
        }
        // This is the original FileProvider's declared cache-path voice_collect.
        File directory = new File(app.getCacheDir(), "voice_collect");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create voice cache");
        String suffix = meta.mime.contains("3gpp") ? ".3gp" : meta.mime.contains("wav") ? ".wav"
                : meta.mime.contains("mpeg") ? ".mp3" : meta.mime.contains("ogg") ? ".ogg"
                : meta.mime.equals("audio/aac") ? ".aac" : ".m4a";
        File copy = new File(directory, "supercard-" + job.guid.replace("-", "") + suffix);
        if (copy.exists()) throw new IllegalStateException("Voice correlation file already exists");
        job.voiceCopy = copy;
        try (InputStream input = app.getContentResolver().openInputStream(source);
                FileOutputStream output = new FileOutputStream(copy)) {
            if (input == null) throw new IllegalArgumentException("Unreadable recording");
            byte[] bytes = new byte[32 * 1024]; long total = 0; int count;
            while ((count = input.read(bytes)) != -1) {
                total += count;
                if (total > 20L * 1024 * 1024 || total > meta.size) throw new IllegalArgumentException("Recording size changed");
                output.write(bytes, 0, count);
            }
            if (total <= 0 || total != meta.size) throw new IllegalArgumentException("Recording incomplete");
        }
        long duration;
        try (MediaMetadataRetriever media = new MediaMetadataRetriever()) {
            media.setDataSource(copy.getAbsolutePath());
            if (!"yes".equals(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                throw new IllegalArgumentException("No playable audio track");
            }
            duration = Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        }
        // Native VoiceCollector discards recordings below 1 s; native recording UI times
        // out at 62 s. Use real container metadata rather than trusting caller duration.
        if (duration < 1_000 || duration > 62_000) throw new IllegalArgumentException("Recording duration outside native limits");
        try (InputStream input = new java.io.FileInputStream(copy)) { job.voiceDigest = digest(input); }
        List<Float> waves = List.of();
        Object success = voiceSuccessConstructor.newInstance(copy, duration, false, "", waves);
        job.writeAttempted = true;
        invokeSuspend(voiceHandle, voiceHandler, new Object[]{app, System.currentTimeMillis(),
                success, voiceTrigger, waves, voiceCallback}, result -> {
            if (!job.voiceTagged) job.fail("native_rejected", "原生随口记没有进入当前批次保存链");
            else if (result instanceof String && !((String) result).isEmpty()) job.nativeCompleted((String) result);
            else job.fail("native_rejected", "原生随口记未返回记忆 ID");
            job.deleteVoiceCopy();
            job.deleteImageCopies();
        }, error -> { job.deleteVoiceCopy(); job.deleteImageCopies(); job.fail("native_error", safeError(error)); });
    }

    private static void observeProvider(XposedModule module) throws Exception {
        Class<?> provider = load(PACKAGE + ".provider.DataShareProvider");
        Method call = provider.getDeclaredMethod("call", String.class, String.class, Bundle.class);
        if (call.getReturnType() != Bundle.class) throw new IllegalStateException("Provider call signature changed");
        module.hook(call).intercept(chain -> {
            Job job = active;
            boolean ours = false;
            try {
                if (job != null && "insert".equals(chain.getArg(0)) && chain.getArg(2) instanceof Bundle) {
                    Bundle values = (Bundle) chain.getArg(2);
                    String index = values.getString("indexData");
                    if (index != null) {
                        Object extra = new JSONObject(index).opt("extraData");
                        JSONObject data = extra instanceof JSONObject ? (JSONObject) extra
                                : extra instanceof String ? new JSONObject((String) extra) : null;
                        ours = data != null && job.guid.equals(data.optString("importId"));
                    }
                }
            } catch (Exception ignored) { /* Never alter unrelated native provider requests. */ }
            Object result;
            try { result = chain.proceed(); }
            catch (Throwable error) { if (ours) job.fail("provider_error", safeError(error)); throw error; }
            if (ours) {
                if (result instanceof Bundle) {
                    Bundle saved = (Bundle) result;
                    String id = saved.getString("memoryId");
                    if (!saved.containsKey("errorCode") && id != null && !id.isEmpty()) {
                        try {
                            verifySavedCopy(chain.getThisObject(), job, id);
                            job.saved(id);
                        } catch (Throwable error) { job.fail("copy_unconfirmed", safeError(error)); }
                    }
                    else job.fail("provider_rejected", "原生 Provider 拒绝导入，错误码 " + saved.getInt("errorCode", -1));
                } else job.fail("provider_error", "原生 Provider 未返回入库结果");
            }
            return result;
        });
        providerObserved = true;
        Method insert = provider.getDeclaredMethod("insert", Uri.class, android.content.ContentValues.class);
        if (insert.getReturnType() != Uri.class) throw new IllegalStateException("Provider insert signature changed");
        module.hook(insert).intercept(chain -> {
            Job job = active;
            boolean ours = false;
            try {
                if (job != null && job.hasVoice() && job.voiceTagged && job.voiceCopy != null
                        && chain.getArg(0) instanceof Uri && chain.getArg(1) instanceof android.content.ContentValues) {
                    Uri endpoint = (Uri) chain.getArg(0);
                    android.content.ContentValues values = (android.content.ContentValues) chain.getArg(1);
                    String json = values.getAsString("indexData");
                    if ("com.oplus.aimemory.provider.DataShareProvider".equals(endpoint.getAuthority())
                            && "/datacollectmemory".equals(endpoint.getPath()) && json != null) {
                        JSONObject data = new JSONObject(json);
                        Uri audio = Uri.parse(data.optString("audioFile"));
                        Object raw = data.opt("extraData");
                        JSONObject extra = raw instanceof JSONObject ? (JSONObject) raw
                                : raw instanceof String ? new JSONObject((String) raw) : null;
                        ours = "VoiceMemory".equals(data.optString("packageName"))
                                && "content".equals(audio.getScheme())
                                && "com.oplus.aimemory.fileprovider".equals(audio.getAuthority())
                                && job.voiceCopy.getName().equals(audio.getLastPathSegment())
                                && extra != null && "5".equals(extra.optString("triggerType"))
                                && extra.has("asrSuccess") && !extra.optBoolean("asrSuccess", true)
                                && job.guid.equals(extra.optString("importId"));
                    }
                }
            } catch (Exception ignored) { }
            Object result;
            try { result = chain.proceed(); }
            catch (Throwable error) { if (ours) job.fail("provider_error", safeError(error)); throw error; }
            if (ours) {
                Uri saved = result instanceof Uri ? (Uri) result : null;
                String id = saved == null ? null : saved.getQueryParameter("indexDataId");
                if (id == null || id.isEmpty() || saved.getQueryParameter("errorCode") != null) {
                    job.fail("provider_rejected", "原生随口记 Provider 未确认入库");
                } else {
                    try { verifySavedCopy(chain.getThisObject(), job, id); job.saved(id); }
                    catch (Throwable error) { job.fail("copy_unconfirmed", safeError(error)); }
                }
            }
            return result;
        });
        audioObserved = true;
    }

    private static void verifySavedCopy(Object provider, Job job, String id) throws Exception {
        // A provider ID alone is insufficient if copying the actual attachment failed.
        // Read the original repository and require a readable, independent stored file.
        Class<?> repositoryType = load(PACKAGE + ".db.repository.impl.MemoryRepository");
        Object repository = semanticGetter(provider.getClass(), repositoryType, "getMemoryRepository").invoke(provider);
        Class<?> detailsType = load(PACKAGE + ".db.bean.MemoryWithDetails");
        Method query = null;
        for (Method method : repositoryType.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == detailsType
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class})) {
                if (query != null) throw new IllegalStateException("Ambiguous stored-memory lookup");
                query = method;
            }
        }
        if (query == null) throw new NoSuchMethodException("Stored-memory lookup changed");
        query.setAccessible(true);
        Object details = query.invoke(repository, id);
        if (details == null) throw new VerificationException("原生仓库没有返回已保存的记忆");
        Class<?> memoryType = load(PACKAGE + ".db.table.Memory");
        Object memory = semanticGetter(detailsType, memoryType, "getMemory").invoke(details);
        if (job.type == 501) {
            Object extra = memoryType.getMethod("getExtraData").invoke(memory);
            String path = extra == null ? null : (String) extra.getClass().getMethod("getFilePath").invoke(extra);
            if (path == null || !path.startsWith("/") || !new File(path).isFile() || new File(path).length() <= 0) {
                throw new VerificationException("原生文档副本尚未写入");
            }
            return;
        }
        Context context = ((ContentProvider) provider).getContext();
        if (context == null) throw new VerificationException("原生 Provider 上下文不可用");
        if (job.hasVoice() && !Boolean.TRUE.equals(memoryType.getMethod("isVoiceMemory").invoke(memory))) {
            throw new VerificationException("录音没有保存为原生随口记");
        }
        if (job.hasVoice()) {
            String stored = (String) memoryType.getMethod("getAudioFile").invoke(memory);
            Uri uri = storedUri(job, stored);
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null || job.voiceDigest == null || !Arrays.equals(job.voiceDigest, digest(input))) {
                    throw new VerificationException("原生录音副本与完整源录音校验不符");
                }
            }
        }
        if (!job.imageUris.isEmpty()) {
            // The first-image header is native too; every input, including this first
            // image, must additionally exist as a distinct Attachment of the same Memory.
            Uri screenshot = storedUri(job, (String) memoryType.getMethod("getScreenshot").invoke(memory));
            verifyStoredImage(context, screenshot);
            Object raw = detailsType.getMethod("getAttachments").invoke(details);
            if (!(raw instanceof List<?>)) throw new VerificationException("原生图片附件列表不存在");
            Class<?> attachmentType = attachmentConstructor.getDeclaringClass();
            Set<String> found = new LinkedHashSet<>();
            Set<String> paths = new LinkedHashSet<>();
            File directory = new File(context.getFilesDir(), "Attachment").getCanonicalFile();
            for (Object attachment : (List<?>) raw) {
                if (!attachmentType.isInstance(attachment)) continue;
                String attachmentId = (String) attachmentType.getMethod("getAttachmentId").invoke(attachment);
                if (!job.imageIds.contains(attachmentId)) continue;
                String memoryId = (String) attachmentType.getMethod("getMemoryId").invoke(attachment);
                int state = (Integer) attachmentType.getMethod("getState").invoke(attachment);
                int mediaType = (Integer) attachmentType.getMethod("getMediaType").invoke(attachment);
                String path = (String) attachmentType.getMethod("getPath").invoke(attachment);
                if (!id.equals(memoryId) || state != 2 || mediaType != 1 || path == null) {
                    throw new VerificationException("图片附件尚未成为同一条记忆的本地副本");
                }
                File file = new File(path).getCanonicalFile();
                if (!file.getPath().startsWith(directory.getPath() + File.separator)
                        || !file.isFile() || file.length() <= 0 || !paths.add(file.getPath())) {
                    throw new VerificationException("原生图片附件独立文件尚未写入私有存储");
                }
                Uri uri = storedUri(job, (String) attachmentType.getMethod("getUri").invoke(attachment));
                verifyStoredImage(context, uri);
                if (!found.add(attachmentId)) throw new VerificationException("原生图片附件 ID 重复");
            }
            if (found.size() != job.imageIds.size() || !found.containsAll(job.imageIds)) {
                throw new VerificationException("当前批次有图片尚未复制到同一条记忆");
            }
        }
    }

    private static Uri storedUri(Job job, String stored) {
        if (stored == null || stored.isEmpty()) throw new VerificationException("原生副本 URI 为空");
        Uri uri = Uri.parse(stored);
        if (!"content".equals(uri.getScheme()) || !"com.oplus.aimemory.dataCenterFileProvider".equals(uri.getAuthority())
                || job.uris.contains(uri)) throw new VerificationException("附件仍引用源文件而非小布内部副本");
        return uri;
    }

    private static void verifyStoredImage(Context context, Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new VerificationException("原生图片副本不可读");
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new VerificationException("原生图片副本无法解码");
    }

    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = new byte[32 * 1024]; int count; long total = 0;
        while ((count = input.read(bytes)) != -1) {
            total += count;
            if (total > 20L * 1024 * 1024) throw new VerificationException("原生录音副本超过大小限制");
            digest.update(bytes, 0, count);
        }
        if (total == 0) throw new VerificationException("原生录音副本为空");
        return digest.digest();
    }

    private static void observeManualRequests(XposedModule module) throws Exception {
        Class<?> base = load(ENTRY + "usecase.BaseFileUseCase");
        Method execute = null;
        for (Method method : base.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 2 && isContinuation(parameters[1])
                    && method.getReturnType() == Object.class && hasFields(parameters[0], List.class, String.class)) {
                if (execute != null) throw new IllegalStateException("Ambiguous document execution signature");
                execute = method;
            }
        }
        if (execute == null) throw new NoSuchMethodException("Document execution signature changed");
        Class<?> continuationType = execute.getParameterTypes()[1];
        module.hook(execute).intercept(chain -> {
            Job job = active;
            Object request = chain.getArg(0);
            if (job == null || job.type != 501 || !sameUris(request, job.uris)) return chain.proceed();
            // Manual importer fixes importId to an empty string. Assign only the correlation
            // metadata of this exact URI list; all native format/network/privacy checks remain.
            Field correlation = uniqueField(request.getClass(), String.class);
            correlation.set(request, job.guid);
            job.documentTagged = true;
            Object[] args = chain.getArgs().toArray();
            Object original = args[1];
            args[1] = continuation(continuationType, original, result -> {
                documentState(job, result);
            }, error -> job.fail("native_error", safeError(error)));
            Object result = chain.proceed(args);
            if (!suspended(result)) documentState(job, result);
            return result;
        });
        documentsObserved = true;
    }

    private static void documentState(Job job, Object result) {
        String state = nativeState(result);
        if (!"DOC_SINGLE_SUCCESS".equals(state) && !"DOC_MULTI_SUCCESS".equals(state)) {
            job.fail("native_rejected", state);
        }
    }

    private static boolean sameUris(Object request, List<Uri> expected) {
        try {
            Object value = uniqueField(request.getClass(), List.class).get(request);
            if (!(value instanceof List) || ((List<?>) value).size() != expected.size()) return false;
            Set<Uri> found = new LinkedHashSet<>();
            for (Object info : (List<?>) value) {
                Method getter = semanticGetter(info.getClass(), Uri.class, "getUri");
                found.add((Uri) getter.invoke(info));
            }
            return found.equals(new LinkedHashSet<>(expected));
        } catch (Throwable ignored) { return false; }
    }

    private static void validateUri(Context context, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || uri.getFragment() != null
                || uri.getQuery() != null || uri.toString().length() > 2048) throw new IllegalArgumentException("Invalid content URI");
        if ("media".equals(uri.getAuthority())) {
            List<String> path = uri.getPathSegments();
            if (path.size() < 3 || !path.get(path.size() - 1).matches("[0-9]+")) {
                throw new IllegalArgumentException("Expected a MediaStore item URI");
            }
            return;
        }
        if (!TRANSFER.equals(uri.getAuthority())) throw new IllegalArgumentException("Untrusted URI authority");
        List<String> path = uri.getPathSegments();
        if (path.size() != 3 || !"items".equals(path.get(0))) throw new IllegalArgumentException("Invalid transfer path");
        requireGuid(path.get(1));
        String name = path.get(2);
        if (name.isEmpty() || name.length() > 256 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.chars().anyMatch(c -> c < 32)) {
            throw new IllegalArgumentException("Invalid transfer filename");
        }
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(TRANSFER, 0);
        if (provider == null || !MODULE.equals(provider.packageName)) throw new IllegalArgumentException("Transfer provider unavailable");
    }

    private static Meta metadata(Context context, Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri), name = null; long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n);
                if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s);
            }
        }
        if (name == null || name.isEmpty() || mime == null || size <= 0) {
            throw new IllegalArgumentException("原生导入要求文件名、真实 MIME 和长度元数据");
        }
        if (name.length() > 256 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.chars().anyMatch(c -> c < 32)) {
            throw new IllegalArgumentException("Invalid document display name");
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Missing URI read grant");
        }
        return new Meta(name, mime, size);
    }

    private record Meta(String name, String mime, long size) { }
    private record ImagePart(Uri uri, String id, Meta meta, int width, int height) { }
    private static final class VerificationException extends IllegalStateException {
        VerificationException(String reason) { super(reason); }
    }
    private interface Success { void accept(Object value) throws Exception; }
    private interface Failed { void accept(Throwable error); }

    private static void invokeSuspend(Method method, Object receiver, Object[] input, Success done, Failed failed) {
        AtomicBoolean completed = new AtomicBoolean();
        Success once = value -> { if (completed.compareAndSet(false, true)) done.accept(value); };
        Failed failOnce = error -> { if (completed.compareAndSet(false, true)) failed.accept(error); };
        try {
            Object[] args = Arrays.copyOf(input, input.length + 1);
            args[input.length] = continuation(method.getParameterTypes()[input.length], null, once, failOnce);
            Object result = method.invoke(receiver, args);
            if (!suspended(result)) once.accept(result);
        } catch (Throwable error) { failOnce.accept(unwrap(error)); }
    }

    private static Object continuation(Class<?> type, Object delegate, Success done, Failed failed) throws Exception {
        if (!isContinuation(type)) throw new IllegalStateException("Invalid Kotlin continuation interface");
        Object empty = delegate == null ? singleton(load("kotlin.coroutines.EmptyCoroutineContext")) : null;
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if ("getContext".equals(method.getName())) return delegate == null ? empty : method.invoke(delegate);
            if ("resumeWith".equals(method.getName())) {
                try {
                    Throwable error = coroutineFailure(args[0]);
                    if (error == null) done.accept(args[0]); else failed.accept(error);
                } catch (Throwable error) { failed.accept(unwrap(error)); }
                finally { if (delegate != null) method.invoke(delegate, args); }
                return null;
            }
            if ("toString".equals(method.getName())) return "SuperCardMemoryContinuation";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
            throw new UnsupportedOperationException("Unexpected continuation method");
        });
    }

    private static Throwable coroutineFailure(Object value) throws Exception {
        if (value == null || !value.getClass().getName().startsWith("kotlin.Result$")) return null;
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Throwable.class.isAssignableFrom(field.getType())) { field.setAccessible(true); return (Throwable) field.get(value); }
        }
        return null;
    }

    private static boolean suspended(Object value) {
        return value instanceof Enum<?> && "COROUTINE_SUSPENDED".equals(((Enum<?>) value).name());
    }

    private static String nativeState(Object value) {
        if (value == null) return "missing_native_state";
        try {
            Method getter = null;
            for (Method method : value.getClass().getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType().isEnum()) {
                    if (getter != null) return "ambiguous_native_state";
                    getter = method;
                }
            }
            if (getter != null) { getter.setAccessible(true); return ((Enum<?>) getter.invoke(value)).name(); }
        } catch (Throwable ignored) { }
        return "unknown_native_state";
    }

    private static Method suspendMethod(Class<?> type, Class<?>... prefix) throws Exception {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() != Object.class || params.length != prefix.length + 1
                    || !isContinuation(params[prefix.length])) continue;
            boolean match = true;
            for (int i = 0; i < prefix.length; i++) if (params[i] != prefix[i]) match = false;
            if (match) { if (found != null) throw new IllegalStateException("Ambiguous suspend signature"); found = method; }
        }
        if (found == null) throw new NoSuchMethodException(type.getName() + " suspend signature changed");
        found.setAccessible(true); return found;
    }

    private static boolean isContinuation(Class<?> type) {
        if (!type.isInterface()) return false;
        try { return type.getMethod("resumeWith", Object.class).getReturnType() == void.class
                && type.getMethod("getContext").getParameterCount() == 0; }
        catch (NoSuchMethodException ignored) { return false; }
    }

    private static Object singleton(Class<?> type) throws Exception {
        Field found = null;
        for (Field field : type.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
            if (found != null) throw new IllegalStateException("Ambiguous singleton"); found = field;
        }
        if (found == null) throw new NoSuchFieldException("No native singleton");
        found.setAccessible(true); return found.get(null);
    }

    private static Field uniqueField(Class<?> type, Class<?> wanted) throws Exception {
        Field found = null;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && field.getType() == wanted) {
                if (found != null) throw new IllegalStateException("Ambiguous native model field"); found = field;
            }
        }
        if (found == null) throw new NoSuchFieldException("Native model field changed");
        found.setAccessible(true); return found;
    }

    private static boolean hasFields(Class<?> type, Class<?>... wanted) {
        try { for (Class<?> field : wanted) uniqueField(type, field); return true; }
        catch (Exception ignored) { return false; }
    }

    private static Method semanticGetter(Class<?> type, Class<?> result, String propertyGetter) throws Exception {
        // @Keep models also expose componentN aliases. Prefer the proven property getter,
        // never whichever same-return-type method reflection happens to enumerate first.
        try {
            Method named = type.getDeclaredMethod(propertyGetter);
            if (!Modifier.isStatic(named.getModifiers()) && named.getReturnType() == result) {
                named.setAccessible(true); return named;
            }
        } catch (NoSuchMethodException ignored) { }
        Method found = null;
        for (Method method : type.getDeclaredMethods()) if (!Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0 && method.getReturnType() == result) {
            if (found != null) throw new NoSuchMethodException("Ambiguous native property " + propertyGetter);
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("Native property changed: " + propertyGetter);
        found.setAccessible(true); return found;
    }

    private static Class<?> load(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

    private static boolean nativeBoolean(String role) throws Exception {
        // A business exception from invocation is not a discovery failure and must not retry.
        return (Boolean) MemoryDexResolver.gate(loader, role, false).invoke(null);
    }

    private static void requireGuid(String guid) {
        if (guid == null || !guid.matches("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")) {
            throw new IllegalArgumentException("Invalid guid");
        }
    }

    private static String text(Intent intent, String key, int max) {
        String value = intent.getStringExtra(key);
        if (value == null || value.isEmpty() || value.length() > max || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Missing or invalid " + key);
        }
        return value;
    }

    private static Bundle failure(String guid, String status, String error) {
        Bundle result = new Bundle(); if (guid != null) result.putString("guid", guid);
        result.putString("status", status); result.putString("error", error);
        result.putBoolean("canDeleteSources", false); result.putStringArrayList("memoryIds", new ArrayList<>());
        return result;
    }

    private static void reply(BroadcastReceiver.PendingResult pending, AtomicBoolean replied, Bundle result) {
        if (!replied.compareAndSet(false, true)) return;
        try {
            pending.setResultCode("saved".equals(result.getString("status")) || result.getBoolean("ready")
                    || "probe_ok".equals(result.getString("status"))
                    ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
            pending.setResultExtras(result);
        } finally { pending.finish(); }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && ((InvocationTargetException) error).getCause() != null) error = error.getCause();
        return error;
    }

    private static String safeError(Throwable error) {
        // Native exception messages may contain recognized content or private file paths.
        Throwable cause = unwrap(error);
        StringBuilder trace = new StringBuilder("Native adapter failure: ");
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (depth != 0) trace.append("\nCaused by: ");
            trace.append(current.getClass().getName());
            for (StackTraceElement frame : current.getStackTrace()) trace.append("\n\tat ").append(frame);
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        Log.e(TAG, trace.toString());
        return cause instanceof VerificationException ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private static final class Job {
        final String guid, sourcePkg, sourceName;
        final int type;
        final int attempt;
        final ArrayList<Uri> uris;
        final List<Integer> attachmentTypes;
        final ArrayList<Uri> imageUris = new ArrayList<>();
        final ArrayList<ImagePart> imageParts = new ArrayList<>();
        final ArrayList<File> imageCopies = new ArrayList<>();
        final LinkedHashSet<String> imageIds = new LinkedHashSet<>();
        final LinkedHashSet<Uri> validatedImages = new LinkedHashSet<>();
        final Uri voiceUri;
        final BroadcastReceiver.PendingResult pending;
        final AtomicBoolean replied;
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        boolean nativeDone, complete;
        volatile boolean writeAttempted;
        String nativeId;
        volatile boolean documentTagged, imagesValidated, voiceTagged;
        volatile File voiceCopy;
        volatile byte[] voiceDigest;
        volatile Object imageUseCase;
        volatile JSONArray preloadImages;
        String status = "pending", error;

        Job(String guid, int type, String sourcePkg, String sourceName, ArrayList<Uri> uris, ArrayList<Integer> types,
                BroadcastReceiver.PendingResult pending, AtomicBoolean replied, int attempt) {
            this.guid = guid; this.type = type; this.sourcePkg = sourcePkg; this.sourceName = sourceName;
            this.uris = uris; this.pending = pending; this.replied = replied;
            this.attachmentTypes = List.copyOf(types);
            this.attempt = attempt;
            Uri audio = null;
            for (int i = 0; i < uris.size(); i++) {
                int itemType = types.get(i);
                if (itemType == 401) audio = uris.get(i);
                else if (itemType == 101 || itemType == 2401) imageUris.add(uris.get(i));
            }
            voiceUri = audio;
        }

        boolean hasVoice() { return voiceUri != null; }
        synchronized void imageValidated(Uri uri) { validatedImages.add(uri); }
        synchronized boolean allImagesValidated() {
            return validatedImages.size() == imageUris.size() && validatedImages.containsAll(imageUris);
        }
        synchronized void saved(String id) {
            if (complete) return;
            if (type != 501 && !ids.isEmpty() && !ids.contains(id)) {
                fail("copy_unconfirmed", "当前批次被拆为多条记忆，源文件仍需保留"); return;
            }
            ids.add(id); tryComplete();
        }
        synchronized void nativeCompleted() { if (!complete) { nativeDone = true; tryComplete(); } }
        synchronized void nativeCompleted(String id) {
            if (!complete) { nativeId = id; nativeDone = true; tryComplete(); }
        }
        synchronized boolean isComplete() { return complete; }
        synchronized boolean canRetryBeforeWrite() {
            return attempt < 3 && complete && "native_error".equals(status) && !writeAttempted
                    && !nativeDone && (nativeId == null || nativeId.isEmpty()) && ids.isEmpty();
        }
        void deleteVoiceCopy() {
            File file = voiceCopy;
            if (file != null && file.isFile() && !file.delete()) Log.w(TAG, "Native voice cache cleanup deferred");
        }
        void deleteImageCopies() {
            for (File file : imageCopies) {
                if (file.isFile() && !file.delete()) Log.w(TAG, "Native image cache cleanup deferred");
            }
        }

        private void tryComplete() {
            int expected = type == 501 ? uris.size() : 1;
            if (nativeDone && ids.size() == expected) {
                if (type != 501 && (nativeId == null || !ids.contains(nativeId))) {
                    fail("copy_unconfirmed", "原生完成结果与已核对的记忆 ID 不一致"); return;
                }
                complete = true; status = "saved"; finish();
            }
        }

        synchronized void fail(String status, String error) {
            if (complete) return;
            complete = true; this.status = status; this.error = error; finish();
        }

        private void finish() {
            // receive() is serialized by WORK. A new job cannot claim this slot until
            // it becomes null; avoid taking HISTORY while holding this job's monitor.
            if (active == this) active = null;
            // An expired importer may still resume. Its weak instance correlation keeps
            // suppressing per-image inserts until the native validation callback finishes.
            // Do not recycle a bitmap while a native validation coroutine is using it.
            imageUseCase = null;
            reply(pending, replied, snapshot());
            Log.i(TAG, "Native import result=" + status + " memories=" + ids.size() + " attachments=" + uris.size());
        }

        synchronized Bundle snapshot() {
            Bundle result = new Bundle(); result.putString("guid", guid); result.putString("status", status);
            result.putBoolean("complete", complete);
            result.putInt("attempt", attempt);
            if (error != null) result.putString("error", error);
            result.putStringArrayList("memoryIds", new ArrayList<>(ids));
            result.putBoolean("canDeleteSources", "saved".equals(status));
            result.putBoolean("retryableBeforeWrite", canRetryBeforeWrite());
            return result;
        }
    }
}
