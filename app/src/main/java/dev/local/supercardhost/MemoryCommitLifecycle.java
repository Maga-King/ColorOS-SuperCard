package dev.local.supercardhost;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Retires acknowledged attachments from vivo's draft without discarding newer/failed items. */
public final class MemoryCommitLifecycle {
    private static final String TAG = "SuperCardMemoryCommit";
    private static final String DRAFT = "save_memory_card_key";
    private static final String FLAG = "card_save_success_flag_key";
    private static final String TIME = "save_success_time_key";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<ClassLoader, State> STATES = new HashMap<>();

    private MemoryCommitLifecycle() {}

    public static synchronized void install(XposedModule module, Context host, ClassLoader loader) {
        if (STATES.containsKey(loader)) return;
        try {
            State state = new State(host, loader);
            state.install(module);
            STATES.put(loader, state);
            Log.i(TAG, "Original memory commit/draft lifecycle installed");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot install original memory commit lifecycle", error);
        }
    }

    /** Call only after the importer acknowledged a real memory ID; source files remain untouched. */
    public static void onCommitted(Context host, ClassLoader loader, List<?> committed) {
        State state;
        synchronized (MemoryCommitLifecycle.class) { state = STATES.get(loader); }
        if (state == null) {
            Log.e(TAG, "Commit lifecycle is not installed for this memory loader");
            return;
        }
        if (committed != null) {
            for (Object item : new ArrayList<>(committed)) {
                try {
                    String guid = state.guid(item);
                    if (!guid.isEmpty()) state.confirmed.add(guid);
                } catch (Throwable error) { Log.e(TAG, "Cannot identify committed attachment", error); }
            }
        }
        // Always queue: save callbacks and new attachment mutations also run on main.
        MAIN.post(() -> {
            state.filterDraft();
            ArrayList<Object> views;
            synchronized (state.views) { views = new ArrayList<>(state.views.keySet()); }
            for (Object view : views) state.prune(view);
            state.filterDraft();
        });
    }

    private static final class State {
        final Context host;
        final ClassLoader loader;
        final SharedPreferences commits;
        final Set<String> confirmed = Collections.synchronizedSet(new HashSet<>());
        final Map<Object, Boolean> views = Collections.synchronizedMap(new WeakHashMap<>());
        final Class<?> viewType;
        final Class<?> infoType;
        final Field guidField;
        final Field preferencesField;
        final Field groupField;
        final Field bottomField;
        final Field memoryGuidField;
        final Field restoredField;
        final Method getItems;
        final Method clearView;
        final Method clearGroup;
        final Method rebuildGroup;
        final Method bottomItems;
        final Method background;
        final Method json;
        final ThreadLocal<Boolean> pruning = ThreadLocal.withInitial(() -> false);

        State(Context host, ClassLoader loader) throws Exception {
            this.host = host;
            this.loader = loader;
            commits = host.getSharedPreferences("supercard_memory_commits", Context.MODE_PRIVATE);
            viewType = loader.loadClass("com.vivo.memory.card.view.MemoryCardView");
            infoType = loader.loadClass("com.vivo.memory.card.ipc.bean.MemoryCardInfo");
            Class<?> groupType = loader.loadClass("com.vivo.memory.card.widget.MemoryCardGroupView");
            Class<?> bottomType = loader.loadClass("com.vivo.memory.card.view.CardBottomView");
            guidField = field(infoType, "d");
            preferencesField = field(loader.loadClass("k6.f0"), "a");
            groupField = uniqueField(viewType, groupType);
            bottomField = uniqueField(viewType, bottomType);
            memoryGuidField = field(viewType, "a");
            restoredField = field(viewType, "G");
            getItems = method(groupType, "getAttachmentInfoBeanList");
            clearView = method(viewType, "k", viewType, String.class);
            clearGroup = method(groupType, "e", String.class);
            rebuildGroup = method(groupType, "q", ArrayList.class);
            bottomItems = method(bottomType, "n", List.class);
            background = method(viewType, "s");
            json = method(loader.loadClass("h6.a"), "b", Object.class);
        }

        void install(XposedModule module) throws Exception {
            for (String lifecycle : new String[]{"onCardShowing", "onCardShown"}) {
                module.hook(method(viewType, lifecycle)).intercept(chain -> {
                    Object view = chain.getThisObject();
                    views.put(view, true);
                    filterDraft();
                    prune(view);
                    Object result = chain.proceed();
                    // onCardShowing restores the persisted draft; onCardShown displays its guide.
                    prune(view);
                    filterDraft();
                    return result;
                });
            }
            module.hook(method(viewType, "p", loader.loadClass("a6.k"))).intercept(chain -> {
                Object view = chain.getThisObject();
                views.put(view, true);
                filterDraft();
                prune(view);
                return chain.proceed();
            });
            module.hook(method(viewType, "r", List.class, boolean.class)).intercept(chain -> {
                Object view = chain.getThisObject();
                views.put(view, true);
                List<?> incoming = (List<?>) chain.getArg(0);
                ArrayList<Object> remaining = keep(incoming);
                if (incoming == null || remaining.size() == incoming.size()) return chain.proceed();
                if (remaining.isEmpty()) return null;
                Object[] args = chain.getArgs().toArray();
                args[0] = remaining;
                return chain.proceed(args);
            });
            // A delayed recognizer/record callback can return an attachment already committed.
            Method update = null;
            for (Method candidate : viewType.getDeclaredMethods()) {
                Class<?>[] p = candidate.getParameterTypes();
                if (candidate.getName().equals("t") && p.length == 3 && p[0] == infoType
                        && p[1] == boolean.class) update = candidate;
            }
            if (update == null) throw new NoSuchMethodException("Original updateCardInfo");
            update.setAccessible(true);
            module.hook(update).intercept(chain -> {
                views.put(chain.getThisObject(), true);
                if (isCommitted(chain.getArg(0))) return null;
                return chain.proceed();
            });
            // Prevent delayed original auto-save/restore code from persisting retired items again.
            module.hook(method(loader.loadClass("k6.c0"), "f", List.class)).intercept(chain -> {
                List<?> incoming = (List<?>) chain.getArg(0);
                if (incoming == null) return chain.proceed();
                ArrayList<Object> remaining = keep(incoming);
                Object[] args = chain.getArgs().toArray();
                args[0] = remaining;
                Object result = chain.proceed(args);
                filterDraft();
                return result;
            });
            // The original hidden-card save callback runs after onCommitted and writes
            // SAVE_SUCCESS/TIME again. Normalize after that write, preserving any survivors.
            module.hook(method(loader.loadClass("m6.i1"), "b", int.class, List.class, String.class))
                    .intercept(chain -> {
                        try { return chain.proceed(); }
                        finally { reconcileAfterSaveCallback(); }
                    });
            // ADD_MEMORY's callback and its animation completion both clear the whole
            // current group, which may already contain newer or failed attachments.
            module.hook(clearView).intercept(chain -> {
                String reason = (String) chain.getArg(1);
                if (!pruning.get() && ("animEnd".equals(reason) || "saveEnd".equals(reason))) {
                    Object view = chain.getArg(0);
                    try {
                        views.put(view, true);
                        Object group = groupField.get(view);
                        ArrayList<Object> remaining = keep((List<?>) getItems.invoke(group));
                        if (!remaining.isEmpty()) {
                            prune(view);
                            stopSaveAnimation(view, group);
                            mergeDraft(remaining);
                            filterDraft();
                            Log.i(TAG, "Kept uncommitted attachments after " + reason
                                    + ": " + remaining.size());
                            return null;
                        }
                    } catch (Throwable error) {
                        // An uncertain save callback must never discard the current draft.
                        Log.e(TAG, "Cannot reconcile delayed save clear; keeping draft", error);
                        return null;
                    }
                }
                return chain.proceed();
            });
        }

        String guid(Object item) throws Exception {
            if (item == null || !infoType.isInstance(item)) return "";
            Object value = guidField.get(item);
            return value == null ? "" : value.toString();
        }

        boolean committedGuid(String id) {
            return id != null && !id.isEmpty()
                    && (confirmed.contains(id) || commits.contains("attachment_" + id));
        }

        boolean isCommitted(Object item) {
            try { return committedGuid(guid(item)); }
            catch (Throwable error) { Log.e(TAG, "Cannot inspect attachment GUID; keeping item", error); return false; }
        }

        ArrayList<Object> keep(List<?> items) {
            ArrayList<Object> result = new ArrayList<>();
            if (items != null) for (Object item : new ArrayList<>(items)) {
                if (!isCommitted(item)) result.add(item);
            }
            return result;
        }

        SharedPreferences preferences() throws Exception {
            Object value = preferencesField.get(null);
            return value instanceof SharedPreferences ? (SharedPreferences) value : null;
        }

        void reconcileAfterSaveCallback() {
            try {
                ArrayList<Object> currentViews;
                synchronized (views) { currentViews = new ArrayList<>(views.keySet()); }
                for (Object view : currentViews) {
                    Object group = groupField.get(view);
                    if (group != null) mergeDraft(keep((List<?>) getItems.invoke(group)));
                }
            } catch (Throwable error) {
                Log.e(TAG, "Cannot preserve current attachments after save callback", error);
            }
            filterDraft();
        }

        void filterDraft() {
            try {
                SharedPreferences prefs = preferences();
                if (prefs == null) return; // Original onCreate has not assigned k6.f0.a yet.
                String original = prefs.getString(DRAFT, "");
                if (original == null || original.isEmpty()) {
                    // m6.i1 can recreate these two keys after our successful retirement.
                    // Neither is meaningful without a persisted attachment list.
                    if (prefs.contains(FLAG) || prefs.contains(TIME)) {
                        prefs.edit().remove(FLAG).remove(TIME).apply();
                    }
                    return;
                }
                JSONArray incoming = new JSONArray(original);
                JSONArray remaining = new JSONArray();
                for (int i = 0; i < incoming.length(); i++) {
                    Object item = incoming.get(i);
                    if (!(item instanceof JSONObject)
                            || !committedGuid(((JSONObject) item).optString("d", ""))) remaining.put(item);
                }
                SharedPreferences.Editor edit = prefs.edit();
                if (remaining.length() == 0) {
                    edit.remove(DRAFT).remove(FLAG).remove(TIME);
                } else {
                    if (remaining.length() != incoming.length()) edit.putString(DRAFT, remaining.toString());
                    // 4 is the original NEED_RESTORE state. Never let another attachment's
                    // successful save age out this still-uncommitted draft after 2500ms.
                    edit.putInt(FLAG, 4).remove(TIME);
                }
                edit.apply();
                if (remaining.length() != incoming.length()) {
                    Log.i(TAG, "Filtered committed draft attachments=" + (incoming.length() - remaining.length()));
                }
            } catch (Throwable error) {
                // Unknown/corrupt serialization must remain intact for recovery.
                Log.e(TAG, "Cannot filter original draft; keeping it", error);
            }
        }

        void prune(Object view) {
            if (view == null || pruning.get()) return;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN.post(() -> prune(view));
                return;
            }
            pruning.set(true);
            try {
                Object group = groupField.get(view);
                if (group == null) return;
                List<?> original = (List<?>) getItems.invoke(group);
                ArrayList<Object> remaining = keep(original);
                if (original == null || remaining.size() == original.size()) return;
                if (remaining.isEmpty()) {
                    // Original clearData stops save animations, releases group views and
                    // resets its parent memory GUID. It does not delete source files.
                    clearView.invoke(null, view, "committed attachments retired");
                    restoredField.setBoolean(view, false);
                    clearGuide(view);
                } else {
                    // Rebuild via original APIs with a snapshot of all surviving attachments.
                    Method getListener = group.getClass().getMethod("getOnChangeListener");
                    Object listener = getListener.invoke(group);
                    Method setListener = group.getClass().getMethod("setOnChangeListener", getListener.getReturnType());
                    setListener.invoke(group, new Object[]{null});
                    try {
                        clearGroup.invoke(group, "retire committed subset");
                        rebuildGroup.invoke(group, remaining);
                    } finally { setListener.invoke(group, listener); }
                    Object bottom = bottomField.get(view);
                    if (bottom != null) bottomItems.invoke(bottom, remaining);
                    background.invoke(view);
                }
                // Preserve persisted survivors which may belong to a newer in-flight draft.
                mergeDraft(remaining);
                filterDraft();
                Log.i(TAG, "Retired UI attachments=" + (original.size() - remaining.size())
                        + ", remaining=" + remaining.size());
            } catch (Throwable error) { Log.e(TAG, "Cannot retire committed UI attachments", error); }
            finally { pruning.set(false); }
        }

        void mergeDraft(List<?> live) throws Exception {
            SharedPreferences prefs = preferences();
            if (prefs == null || live.isEmpty()) return;
            JSONArray combined = new JSONArray();
            Set<String> seen = new HashSet<>();
            String existing = prefs.getString(DRAFT, "");
            if (existing != null && !existing.isEmpty()) {
                JSONArray persisted = new JSONArray(existing);
                for (int i = 0; i < persisted.length(); i++) {
                    Object item = persisted.get(i);
                    String id = item instanceof JSONObject ? ((JSONObject) item).optString("d", "") : "";
                    if (!committedGuid(id)) {
                        combined.put(item);
                        if (!id.isEmpty()) seen.add(id);
                    }
                }
            }
            String serialized = (String) json.invoke(null, live);
            if (serialized == null) throw new IllegalStateException("Cannot serialize remaining draft");
            JSONArray current = new JSONArray(serialized);
            for (int i = 0; i < current.length(); i++) {
                Object item = current.get(i);
                String id = item instanceof JSONObject ? ((JSONObject) item).optString("d", "") : "";
                if (!committedGuid(id) && (id.isEmpty() || seen.add(id))) combined.put(item);
            }
            prefs.edit().putString(DRAFT, combined.toString()).putInt(FLAG, 4).remove(TIME).apply();
        }

        void clearGuide(Object view) throws Exception {
            Field helperField = uniqueField(viewType, loader.loadClass("n6.m"));
            Object helper = helperField.get(view);
            if (helper == null) return;
            Handler handler = (Handler) field(helper.getClass(), "h").get(helper);
            handler.removeCallbacksAndMessages(null);
            ((List<?>) field(helper.getClass(), "k").get(helper)).clear();
            for (String flag : new String[]{"i", "j", "m"}) field(helper.getClass(), flag).setBoolean(helper, false);
            field(helper.getClass(), "n").set(helper, null);
            method(helper.getClass(), "a").invoke(helper);
            method(helper.getClass(), "e", int.class).invoke(helper, View.GONE);
            // Also cancel original delayed multi-modal and auto-save guide requests.
            Handler viewHandler = (Handler) field(viewType, "k").get(view);
            for (String name : new String[]{"l", "m"}) {
                Field pending = field(viewType, name);
                Object task = pending.get(view);
                if (task instanceof Runnable) viewHandler.removeCallbacks((Runnable) task);
                pending.set(view, null);
            }
        }

        void stopSaveAnimation(Object view, Object group) throws Exception {
            field(viewType, "t").setBoolean(view, false);
            Field animationField = field(viewType, "r");
            Object animation = animationField.get(view);
            if (animation == null) return;
            animationField.set(view, null);
            // g0.e invokes P.run() before cancelling; detach that completed callback
            // to avoid recursively re-entering k("saveEnd") while restoring the group.
            field(animation.getClass(), "P").set(animation, null);
            method(animation.getClass(), "e", groupField.getType()).invoke(animation, group);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method result = owner.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Field uniqueField(Class<?> owner, Class<?> type) throws Exception {
        Field found = null;
        for (Field candidate : owner.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers()) || candidate.getType() != type) continue;
            if (found != null) throw new IllegalStateException("Ambiguous field: " + type.getName());
            found = candidate;
        }
        if (found == null) throw new NoSuchFieldException(type.getName());
        found.setAccessible(true);
        return found;
    }
}
