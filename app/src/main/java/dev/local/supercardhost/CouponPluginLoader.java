package dev.local.supercardhost;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import dalvik.system.PathClassLoader;
import io.github.libxposed.api.XposedModule;

/** Embeds the genuine coupon APK without installing its package or VivoAssistant. */
public final class CouponPluginLoader {
    private static final String TAG = "SuperCardCouponLoader";
    private static final String PACKAGE = "com.vivo.cardplugin.coupon";
    private static final String IMPLEMENTATION = "com.vivo.cardplugin.staging.StagingCardPluginImpl";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<ClassLoader, Installation> INSTALLATIONS = new WeakHashMap<>();

    private CouponPluginLoader() {}

    public static void install(XposedModule module, Application host, ClassLoader cardLoader)
            throws Exception {
        install(module, host, cardLoader, null);
    }

    /** Called once before onCreate and before the original initializer sees the card as available. */
    public static synchronized void install(XposedModule module, Application host,
            ClassLoader cardLoader, Consumer<ClassLoader> initializer) throws Exception {
        if (INSTALLATIONS.containsKey(cardLoader)) return;
        Installation installation = new Installation(host, cardLoader);
        // No availability override is installed if the archive/resources/interface
        // or the caller's actual backend adapter cannot be prepared.
        installation.prepare(initializer);
        installation.install(module);
        INSTALLATIONS.put(cardLoader, installation);
    }

    private static final class Installation {
        final Application host;
        final ClassLoader cardLoader;
        final Class<?> pluginInterface, managerInterface;
        final Method load, loaded, unloaded, failed, destroyed, availability;
        final Field alive, containerPlugin, pendingTicket;
        final Map<Object, Session> sessions = Collections.synchronizedMap(new WeakHashMap<>());
        final Map<Object, Pending> pending = Collections.synchronizedMap(new WeakHashMap<>());
        ClassLoader pluginLoader;
        CouponContext context;
        java.lang.reflect.Constructor<?> constructor;
        Method create, destroy, hide, showing, shown;
        boolean ownDestroy;

        Installation(Application host, ClassLoader loader) throws Exception {
            this.host = host;
            cardLoader = loader;
            Class<?> card = loader.loadClass("com.vivo.card.cards.cardstaging.StagingCardView");
            pluginInterface = loader.loadClass("com.android.systemui.plugins.StagingCardPlugin");
            managerInterface = loader.loadClass("com.android.systemui.plugins.PluginLifecycleManager");
            load = method(card, "loadPlugin");
            loaded = method(card, "onPluginLoaded", pluginInterface, Context.class, managerInterface);
            unloaded = method(card, "onPluginUnloaded", pluginInterface, managerInterface);
            failed = method(card, "handleFailure", String.class);
            destroyed = method(card, "notifyCardDestroyed");
            alive = field(card, "isAlive");
            containerPlugin = field(card, "plugin");
            pendingTicket = field(card, "pendingTicketCardId");
            availability = method(loader.loadClass("com.vivo.card.data.CardDataInitializer"),
                    "isStagingCardPluginAvailable");
        }

        void prepare(Consumer<ClassLoader> initializer) throws Exception {
            Context moduleContext = host.createPackageContext("dev.local.supercardhost", Context.CONTEXT_IGNORE_SECURITY);
            File directory = new File(host.getCodeCacheDir(), "vivo_coupon");
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new java.io.IOException("Cannot create coupon code cache");
            File archive = new File(directory, "coupon-plugin.apk");
            if (archive.exists() && !archive.delete()) throw new java.io.IOException("Cannot replace coupon APK cache");
            try (var input = moduleContext.getAssets().open("coupon-plugin.apk");
                 var output = new FileOutputStream(archive)) {
                if (!archive.setReadOnly()) throw new java.io.IOException("Cannot protect coupon APK cache");
                input.transferTo(output);
                output.getFD().sync();
            }
            PackageInfo info = host.getPackageManager().getPackageArchiveInfo(archive.getAbsolutePath(), 0);
            if (info == null || info.applicationInfo == null || !PACKAGE.equals(info.packageName))
                throw new IllegalArgumentException("Embedded coupon APK manifest mismatch");
            info.applicationInfo.sourceDir = archive.getAbsolutePath();
            info.applicationInfo.publicSourceDir = archive.getAbsolutePath();
            Resources resources = host.getPackageManager().getResourcesForApplication(info.applicationInfo);
            for (String name : new String[]{"item_ticket_card", "item_coupon_empty_state"}) {
                int id = resources.getIdentifier(name, "layout", PACKAGE);
                if (id == 0) throw new Resources.NotFoundException("Missing original coupon layout " + name);
                resources.getLayout(id).close();
            }
            ClassLoader loader = new CouponClassLoader(archive.getAbsolutePath(), cardLoader);
            Class<?> implementation = Class.forName(IMPLEMENTATION, true, loader);
            if (!pluginInterface.isAssignableFrom(implementation))
                throw new LinkageError("Coupon plugin does not share the original StagingCardPlugin interface");
            constructor = implementation.getDeclaredConstructor();
            create = method(implementation, "onCreate", Context.class, Context.class);
            // Unlike Memory, this version inherits the empty Plugin.onDestroy.
            // Never hook that shared default interface method globally.
            destroy = pluginInterface.getMethod("onDestroy");
            ownDestroy = false;
            try { destroy = implementation.getDeclaredMethod("onDestroy"); ownDestroy = true; }
            catch (NoSuchMethodException inherited) { }
            destroy.setAccessible(true);
            hide = method(implementation, "onCardHidden");
            showing = method(implementation, "onCardShowing", String.class);
            shown = method(implementation, "onCardShown");
            method(implementation, "getPluginView");
            if (initializer != null) initializer.accept(loader);
            context = new CouponContext(host, resources, loader, archive.getAbsolutePath(), info.applicationInfo.theme);
            pluginLoader = loader;
            Log.i(TAG, "Validated embedded coupon APK " + info.versionName + ", resources and shared interface");
        }

        void install(XposedModule module) throws Exception {
            module.hook(load).intercept(chain -> {
                Object card = chain.getThisObject();
                // BaseCardView invokes init from its constructor; let the subclass
                // finish assigning isAlive/state before creating its genuine view.
                MAIN.post(() -> loadInto(card));
                return null;
            });
            for (String event : new String[]{"onCardPackShowing", "onCardPackShown", "onCardPackHidden"}) {
                module.hook(method(load.getDeclaringClass(), event)).intercept(chain -> {
                    Object card = chain.getThisObject();
                    Pending status = pending.get(card);
                    if (status == null) { status = new Pending(); pending.put(card, status); }
                    if ("onCardPackShowing".equals(event)) {
                        status.state = 1;
                        status.ticket = (String) pendingTicket.get(card);
                    } else status.state = "onCardPackShown".equals(event) ? 2 : 0;
                    return chain.proceed();
                });
            }
            module.hook(destroyed).intercept(chain -> {
                Object card = chain.getThisObject();
                Session session = sessions.get(card);
                Object plugin = session == null ? null : session.plugin.get();
                try { return chain.proceed(); }
                finally {
                    release(session, plugin, ownDestroy);
                    sessions.remove(card);
                    pending.remove(card);
                }
            });
            // Prepared code/resources/backend, rather than a missing installed
            // Service declaration, now determine this card's availability.
            module.hook(availability).intercept(chain -> pluginLoader != null && context != null);
            Log.i(TAG, "Original StagingCardView lifecycle loader installed");
        }

        void loadInto(Object card) {
            Session session = null;
            try {
                if (!alive.getBoolean(card)) return;
                Session previous = sessions.get(card);
                if (previous != null && previous.loaded && !previous.destroyed) return;
                Object plugin = constructor.newInstance();
                session = new Session(card, plugin);
                Session current = session;
                session.manager = Proxy.newProxyInstance(cardLoader, new Class<?>[]{managerInterface},
                        (proxy, invoked, args) -> managerCall(current, proxy, invoked, args));
                sessions.put(card, session);
                invoke(create, plugin, host, context);
                if (!alive.getBoolean(card)) { release(session, plugin, false); sessions.remove(card); return; }
                invoke(loaded, card, plugin, context, session.manager);
                if (containerPlugin.get(card) != plugin || !ready(card))
                    throw new IllegalStateException("Original staging container rejected the coupon view");
                session.loaded = true;
                // Preserve events which happened while the constructor load was queued.
                Pending status = pending.get(card);
                if (status != null && status.state > 0) {
                    invoke(showing, plugin, status.ticket);
                    if (status.state == 2) invoke(shown, plugin);
                }
                Log.i(TAG, "Original coupon card UI attached");
            } catch (Throwable error) {
                release(session, session == null ? null : session.plugin.get(), false);
                sessions.remove(card);
                Log.e(TAG, "Embedded coupon load failed", unwrap(error));
                try {
                    if (alive.getBoolean(card)) invoke(failed, card, "Coupon plugin: " + unwrap(error));
                } catch (Throwable displayError) { Log.e(TAG, "Coupon error UI failed", unwrap(displayError)); }
            }
        }

        private boolean ready(Object card) throws Exception {
            Object state = field(card.getClass(), "state").get(card);
            return state instanceof Enum<?> && "READY".equals(((Enum<?>) state).name());
        }

        Object managerCall(Session session, Object proxy, Method invoked, Object[] args) {
            switch (invoked.getName()) {
                case "getComponentName": return new ComponentName(PACKAGE, IMPLEMENTATION);
                case "getPackage": return PACKAGE;
                case "getPlugin": return session.destroyed ? null : session.plugin.get();
                case "isLoaded": return session.loaded && !session.destroyed;
                case "setLogFunc":
                    @SuppressWarnings("unchecked")
                    BiConsumer<String, String> logger = (BiConsumer<String, String>) args[0];
                    session.logger = logger;
                    return null;
                case "loadPlugin":
                    MAIN.post(() -> { Object card = session.card.get(); if (card != null) loadInto(card); });
                    return null;
                case "unloadPlugin":
                    MAIN.post(() -> {
                        Object card = session.card.get(), plugin = session.plugin.get();
                        try {
                            if (!session.destroyed && card != null && plugin != null && alive.getBoolean(card))
                                invoke(unloaded, card, plugin, session.manager);
                        } catch (Throwable error) { Log.e(TAG, "Coupon unload callback failed", unwrap(error)); }
                        finally { release(session, plugin, false); if (card != null) sessions.remove(card); }
                    });
                    return null;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "EmbeddedCouponLifecycle(" + PACKAGE + ")";
                default: throw new UnsupportedOperationException("Unknown coupon lifecycle method " + invoked);
            }
        }

        void release(Session session, Object plugin, boolean originalDestroyed) {
            if (session == null || session.destroyed) return;
            session.destroyed = true;
            session.loaded = false;
            session.logger = null;
            if (plugin == null) return;
            try { invoke(hide, plugin); }
            catch (Throwable error) { Log.w(TAG, "Coupon hidden cleanup failed", unwrap(error)); }
            if (!originalDestroyed) try { invoke(destroy, plugin); }
            catch (Throwable error) { Log.w(TAG, "Coupon destroy failed", unwrap(error)); }
        }
    }

    private static final class Pending { int state; String ticket; }
    private static final class Session {
        final WeakReference<Object> card, plugin;
        Object manager;
        boolean loaded, destroyed;
        BiConsumer<String, String> logger;
        Session(Object card, Object plugin) {
            this.card = new WeakReference<>(card);
            this.plugin = new WeakReference<>(plugin);
        }
    }

    private static final class CouponClassLoader extends PathClassLoader {
        CouponClassLoader(String archive, ClassLoader parent) { super(archive, parent); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> result = findLoadedClass(name);
            if (result == null) {
                if (shared(name)) result = getParent().loadClass(name);
                else try { result = findClass(name); }
                catch (ClassNotFoundException absent) { result = getParent().loadClass(name); }
            }
            if (resolve) resolveClass(result);
            return result;
        }
        private static boolean shared(String name) {
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                    || name.startsWith("dalvik.") || name.startsWith("sun.") || name.startsWith("jdk.")
                    || name.startsWith("org.xml.") || name.startsWith("org.w3c.") || name.startsWith("org.json.")
                    || name.startsWith("libcore.") || name.startsWith("com.android.internal.")
                    || name.startsWith("com.android.systemui.plugins.") || name.startsWith("dev.local.supercardhost.")
                    || name.startsWith("vivo.app.") || name.startsWith("com.vivo.framework.");
        }
    }

    /** Host service/storage identity with only the embedded archive's UI resources and classes. */
    private static final class CouponContext extends ContextWrapper {
        final Resources resources;
        final ClassLoader loader;
        final String archive;
        final Resources.Theme theme;
        int themeId;
        LayoutInflater inflater;
        CouponContext(Context host, Resources resources, ClassLoader loader, String archive, int themeId) {
            super(host);
            this.resources = resources; this.loader = loader; this.archive = archive;
            this.themeId = themeId == 0 ? android.R.style.Theme_Light_NoTitleBar : themeId;
            theme = resources.newTheme(); theme.applyStyle(this.themeId, true);
        }
        @Override public Resources getResources() { return resources; }
        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public ClassLoader getClassLoader() { return loader; }
        @Override public Context getApplicationContext() { return getBaseContext().getApplicationContext(); }
        @Override public Resources.Theme getTheme() { return theme; }
        @Override public void setTheme(int id) { themeId = id; theme.applyStyle(id, true); }
        @Override public String getPackageCodePath() { return archive; }
        @Override public String getPackageResourcePath() { return archive; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences("vivo_coupon_plugin_" + name, mode);
        }
        @Override public boolean deleteSharedPreferences(String name) {
            return getBaseContext().deleteSharedPreferences("vivo_coupon_plugin_" + name);
        }
        @Override public Object getSystemService(String name) {
            if (!LAYOUT_INFLATER_SERVICE.equals(name)) return super.getSystemService(name);
            if (inflater == null) {
                inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
                inflater.setFactory2(new LayoutInflater.Factory2() {
                    @Override public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
                        return onCreateView(name, context, attrs);
                    }
                    @Override public View onCreateView(String name, Context context, AttributeSet attrs) {
                        if (!name.contains(".") || name.startsWith("android.")) return null;
                        try {
                            return (View) loader.loadClass(name).getConstructor(Context.class, AttributeSet.class)
                                    .newInstance(context, attrs);
                        } catch (ClassNotFoundException absent) { return null; }
                        catch (ReflectiveOperationException error) {
                            throw new android.view.InflateException("Original coupon view " + name, unwrap(error));
                        }
                    }
                });
            }
            return inflater;
        }
        @Override public Context createConfigurationContext(Configuration configuration) {
            Context host = getBaseContext().createConfigurationContext(configuration);
            Resources configured = new Resources(resources.getAssets(), host.getResources().getDisplayMetrics(),
                    host.getResources().getConfiguration());
            return new CouponContext(host, configured, loader, archive, themeId);
        }
        @Override public Context createDisplayContext(android.view.Display display) {
            Context host = getBaseContext().createDisplayContext(display);
            Resources configured = new Resources(resources.getAssets(), host.getResources().getDisplayMetrics(),
                    host.getResources().getConfiguration());
            return new CouponContext(host, configured, loader, archive, themeId);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) try {
            Method found = owner.getDeclaredMethod(name, args); found.setAccessible(true); return found;
        } catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field found = type.getDeclaredField(name); found.setAccessible(true); return found;
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) { throw unwrap(error); }
    }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }
}
