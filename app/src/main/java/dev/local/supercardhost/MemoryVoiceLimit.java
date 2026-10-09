package dev.local.supercardhost;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/** One completed voice attachment per original memory draft; original stop remains available. */
public final class MemoryVoiceLimit {
    private static final String TAG = "SuperCardMemoryVoice";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<ClassLoader> INSTALLED = new HashSet<>();

    private MemoryVoiceLimit() {}

    public static synchronized void install(XposedModule module, ClassLoader loader) {
        if (INSTALLED.contains(loader)) return;
        try {
            new State(loader).install(module);
            INSTALLED.add(loader);
            Log.i(TAG, "Original memory one-voice-per-draft limit installed");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot install memory voice limit", error);
        }
    }

    private static final class State {
        // Original memory.card.widget.e maps type 401 to "voice" (101 photo, 2401 screen).
        private static final int VOICE_TYPE = 401;
        final Class<?> bottomType, infoType, executorType, startType, gestureType;
        final Field items, button, locked, executor, attachmentType, recordingState;
        final Field gestureBottom, startExecutor;
        final Method style;
        final int idle;
        final Map<Object, WeakReference<Object>> owners =
                Collections.synchronizedMap(new WeakHashMap<>());
        boolean loggedFailure;

        State(ClassLoader loader) throws Exception {
            bottomType = loader.loadClass("com.vivo.memory.card.view.CardBottomView");
            infoType = loader.loadClass("com.vivo.memory.card.ipc.bean.MemoryCardInfo");
            executorType = loader.loadClass("com.vivo.memory.card.operation.RecordExecutor");
            gestureType = loader.loadClass("com.vivo.memory.card.view.CardBottomView$a");
            startType = loader.loadClass("j6.g");
            items = field(bottomType, "Q");
            button = field(bottomType, "C");
            locked = field(bottomType, "M");
            executor = field(bottomType, "y");
            attachmentType = field(infoType, "e");
            recordingState = field(executorType, "f");
            gestureBottom = field(gestureType, "b");
            startExecutor = field(startType, "a");
            style = method(button.getType(), "e", String.class, boolean.class, boolean.class);
            Class<?> states = loader.loadClass(executorType.getName() + "$RecordLifecycleState");
            idle = field(states, "RECORD_IDLE").getInt(null);
        }

        void install(XposedModule module) throws Exception {
            module.hook(method(bottomType, "n", List.class)).intercept(chain -> {
                Object result = chain.proceed();
                sync(chain.getThisObject());
                return result;
            });
            // onCardShowing resets the native recording state, including its early-return branch.
            module.hook(method(bottomType, "onCardShowing")).intercept(chain -> {
                Object result = chain.proceed();
                sync(chain.getThisObject());
                return result;
            });
            module.hook(method(gestureType, "b")).intercept(chain -> {
                try {
                    Object bottom = gestureBottom.get(chain.getThisObject());
                    track(bottom);
                    if (hasVoice(bottom)) {
                        sync(bottom);
                        Log.i(TAG, "Blocked another voice start in the same draft");
                        return null;
                    }
                } catch (Throwable error) { report(error); }
                return chain.proceed();
            });
            // Recheck after the original authorization dialog; the draft can change while open.
            module.hook(method(startType, "run")).intercept(chain -> {
                try {
                    Object active = startExecutor.get(chain.getThisObject());
                    Object bottom = owner(active);
                    if (bottom != null && hasVoice(bottom)) {
                        sync(bottom);
                        Log.i(TAG, "Blocked authorized voice start: draft already has voice");
                        return null;
                    }
                } catch (Throwable error) { report(error); }
                return chain.proceed();
            });
            // These are completion/release notifications, not the stop request itself.
            module.hook(method(executorType, "a", int.class, infoType, boolean.class, boolean.class))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        sync(owner(chain.getThisObject()));
                        return result;
                    });
        }

        void track(Object bottom) throws Exception {
            if (bottom == null) return;
            Object active = executor.get(bottom);
            if (active != null) owners.put(active, new WeakReference<>(bottom));
        }

        Object owner(Object active) {
            WeakReference<Object> reference = owners.get(active);
            return reference == null ? null : reference.get();
        }

        boolean hasVoice(Object bottom) throws Exception {
            Object value = items.get(bottom);
            if (!(value instanceof List<?> list)) return false;
            for (Object info : list) {
                if (infoType.isInstance(info) && attachmentType.getInt(info) == VOICE_TYPE) return true;
            }
            return false;
        }

        void sync(Object bottom) {
            if (bottom == null) return;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN.post(() -> sync(bottom));
                return;
            }
            try {
                track(bottom);
                Object active = executor.get(bottom);
                // Never disable or recolor the live recording/stop gesture or its animation.
                if (active != null && recordingState.getInt(active) != idle) return;
                boolean enabled = !hasVoice(bottom);
                Object record = button.get(bottom);
                if (record == null) return;
                // Reuse the original unselected record drawable and colors. Screen disabling
                // uses its native icon/label alpha transition, not a dimmed root View alpha.
                style.invoke(record, "record", enabled, locked.getBoolean(bottom));
                ((View) record).setEnabled(enabled);
            } catch (Throwable error) { report(error); }
        }

        synchronized void report(Throwable error) {
            if (loggedFailure) return;
            loggedFailure = true;
            Log.e(TAG, "Cannot synchronize original voice button", error);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method value = type.getDeclaredMethod(name, parameters);
        value.setAccessible(true);
        return value;
    }
}
