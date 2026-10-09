package dev.local.supercardhost;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;

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
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Loads the genuine embedded Favorite card plugin into the original SuperCard container. */
public final class MemoryPluginLoader {
    private static final String TAG = "SuperCardMemoryLoader";
    private static final String PACKAGE = "com.vivo.memory.card";
    private static final String IMPLEMENTATION = PACKAGE + ".MemoryCardPluginImpl";
    private static final Map<ClassLoader, Installation> INSTALLATIONS = new WeakHashMap<>();

    private MemoryPluginLoader() {}

    public static void install(XposedModule module, Application host, ClassLoader cardLoader)
            throws Exception {
        install(module, host, cardLoader, null);
    }

    /** initializer runs once per plugin class loader, before its first onCreate. */
    public static synchronized void install(XposedModule module, Application host,
            ClassLoader cardLoader, Consumer<ClassLoader> initializer) throws Exception {
        if (INSTALLATIONS.containsKey(cardLoader)) return;
        Installation installation = new Installation(module, host, cardLoader, initializer);
        // Validate embedded resources, patched implementation and backend hooks before
        // making the original container's loading path available.
        try { installation.prepare(); }
        catch (Throwable error) { throw new Exception("Embedded memory plugin unavailable", error); }
        installation.install();
        INSTALLATIONS.put(cardLoader, installation);
    }

    private static final class Installation {
        final XposedModule module;
        final Application host;
        final ClassLoader cardLoader;
        final Consumer<ClassLoader> initializer;
        final Class<?> pluginInterface;
        final Class<?> managerInterface;
        final Method loaded;
        final Method unloaded;
        final Method failure;
        final Method load;
        final Method destroyCard;
        final Field containerPlugin;
        final Field alive;
        // Neither value holds a strong reference back to its weak key.
        final Map<Object, Session> containers = Collections.synchronizedMap(new WeakHashMap<>());
        final Map<Object, Session> plugins = Collections.synchronizedMap(new WeakHashMap<>());
        ClassLoader pluginLoader;
        Context pluginResources;
        Method createPlugin;
        Method destroyPlugin;
        Throwable initializationFailure;

        Installation(XposedModule module, Application host, ClassLoader cardLoader,
                Consumer<ClassLoader> initializer) throws Exception {
            this.module = module;
            this.host = host;
            this.cardLoader = cardLoader;
            this.initializer = initializer;
            Class<?> container = cardLoader.loadClass("com.vivo.card.cards.cardmemory.MemoryCardView");
            pluginInterface = cardLoader.loadClass("com.android.systemui.plugins.MemoryCardPlugin");
            managerInterface = cardLoader.loadClass("com.android.systemui.plugins.PluginLifecycleManager");
            load = method(container, "loadPlugin");
            destroyCard = method(container, "notifyCardDestroyed");
            loaded = method(container, "onPluginLoaded", pluginInterface, Context.class, managerInterface);
            unloaded = method(container, "onPluginUnloaded", pluginInterface, managerInterface);
            failure = method(container, "handleFailure", String.class);
            containerPlugin = field(container, "plugin");
            alive = field(container, "isAlive");
        }

        void install() {
            module.hook(load).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    Object container = chain.getThisObject();
                    Log.i(TAG, "Queued memory plugin load for " + System.identityHashCode(container));
                    // BaseCardView calls init virtually from its constructor. Always queue
                    // loading until MemoryCardView has initialized isAlive and state.
                    new Handler(Looper.getMainLooper()).post(() -> loadInto(container));
                    return null;
                }
            });
            module.hook(destroyCard).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object container = chain.getThisObject();
                    Session session = containers.get(container);
                    try {
                        // The original container calls the actual plugin's onDestroy itself.
                        return chain.proceed();
                    } finally {
                        if (session != null) {
                            // Also release if an earlier container cleanup failed before onDestroy.
                            release(session);
                            containers.remove(container);
                        }
                    }
                }
            });
            Log.i(TAG, "Original memory container loader installed");
        }

        synchronized void prepare() throws Throwable {
            if (initializationFailure != null) throw initializationFailure;
            if (pluginLoader != null) return;
            try {
                Context moduleResources = host.createPackageContext("dev.local.supercardhost", Context.CONTEXT_IGNORE_SECURITY);
                java.io.File directory = new java.io.File(host.getCodeCacheDir(), "vivo_memory");
                MemoryArchiveResources resources = MemoryArchiveResources.load(host, moduleResources, directory);
                java.io.File code = MemoryArchiveResources.copyAsset(moduleResources, "memory-code.zip", directory);
                ClassLoader loader = new MemoryClassLoader(code.getAbsolutePath(), null, cardLoader);
                Class<?> implementation = Class.forName(IMPLEMENTATION, true, loader);
                if (!pluginInterface.isAssignableFrom(implementation)) {
                    throw new LinkageError("MemoryCardPlugin interface is not shared with the card container");
                }
                implementation.getDeclaredConstructor();
                createPlugin = method(implementation, "onCreate", Context.class, Context.class);
                destroyPlugin = method(implementation, "onDestroy");
                method(implementation, "getPluginView");
                // Backend hooks can resolve their exact plugin classes here, before onCreate.
                if (initializer != null) initializer.accept(loader);
                module.hook(destroyPlugin).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Session session = plugins.get(chain.getThisObject());
                        if (session != null) {
                            synchronized (session) {
                                if (session.destroyed) return null;
                                session.destroyed = true;
                                session.loaded = false;
                            }
                        }
                        return chain.proceed();
                    }
                });
                pluginResources = resources;
                pluginLoader = loader;
                Log.i(TAG, "Validated embedded memory APK/resources and patched plugin: " + resources.archive.getName());
            } catch (Throwable error) {
                // Avoid installing backend hooks repeatedly after a partial initialization.
                initializationFailure = unwrap(error);
                throw initializationFailure;
            }
        }

        void loadInto(Object container) {
            Session session = null;
            try {
                if (!alive.getBoolean(container)) {
                    Log.w(TAG, "Memory container no longer alive: " + System.identityHashCode(container));
                    return;
                }
                Session previous = containers.get(container);
                if (previous != null && previous.loaded && !previous.destroyed) return;
                prepare();
                Object plugin = pluginLoader.loadClass(IMPLEMENTATION).getDeclaredConstructor().newInstance();
                Context context = new MemoryContext(pluginResources, host, pluginLoader);
                session = new Session(container, plugin);
                Session current = session;
                session.manager = Proxy.newProxyInstance(cardLoader, new Class<?>[]{managerInterface},
                        (proxy, invoked, args) -> managerCall(current, proxy, invoked, args));
                containers.put(container, session);
                plugins.put(plugin, session);
                // PluginInstance's original order: onCreate, then container callback (which
                // sets CardPluginCallback and calls getPluginView exactly once).
                invoke(createPlugin, plugin, host, context);
                if (!alive.getBoolean(container)) {
                    release(session);
                    containers.remove(container);
                    return;
                }
                invoke(loaded, container, plugin, context, session.manager);
                if (containerPlugin.get(container) != plugin) {
                    throw new IllegalStateException("Original memory container rejected its plugin view");
                }
                session.loaded = true;
                MemoryCaptureSupport.attachPlugin(host, plugin, pluginLoader);
                Log.i(TAG, "Original memory card view attached");
            } catch (Throwable error) {
                if (session != null) release(session);
                containers.remove(container);
                reportFailure(container, unwrap(error));
            }
        }

        Object managerCall(Session session, Object proxy, Method invoked, Object[] args) throws Throwable {
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
                    runOnMain(() -> {
                        Object container = session.container.get();
                        if (container != null) loadInto(container);
                    });
                    return null;
                case "unloadPlugin":
                    runOnMain(() -> {
                        Object container = session.container.get();
                        Object plugin = session.plugin.get();
                        try {
                            if (!session.destroyed && container != null && plugin != null
                                    && alive.getBoolean(container)) {
                                invoke(unloaded, container, plugin, session.manager);
                            }
                        } catch (Throwable error) {
                            Log.e(TAG, "Original memory unload callback failed", unwrap(error));
                        } finally {
                            release(session);
                            if (container != null) containers.remove(container);
                        }
                    });
                    return null;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "OriginalMemoryLifecycle(" + PACKAGE + ")";
                default: throw new UnsupportedOperationException("Unknown plugin lifecycle method: " + invoked);
            }
        }

        void runOnMain(Runnable work) {
            if (Looper.myLooper() == Looper.getMainLooper()) work.run();
            else host.getMainExecutor().execute(work);
        }

        void release(Session session) {
            Object plugin = session.plugin.get();
            if (plugin == null || session.destroyed || destroyPlugin == null) return;
            try {
                // The onDestroy hook makes release idempotent, including original destruction.
                invoke(destroyPlugin, plugin);
            } catch (Throwable error) {
                Log.e(TAG, "Original memory plugin release failed", unwrap(error));
            } finally {
                session.destroyed = true;
                session.loaded = false;
                session.logger = null;
            }
        }

        void reportFailure(Object container, Throwable error) {
            Log.e(TAG, "Original memory plugin load failed", error);
            try (var writer = new java.io.PrintWriter(new java.io.File(host.getFilesDir(), "supercard-memory-load-error.txt"))) {
                error.printStackTrace(writer);
            } catch (Exception ignored) { }
            try {
                if (alive.getBoolean(container)) {
                    invoke(failure, container, "Memory APK load failed: " + error);
                }
            } catch (Throwable displayError) {
                Log.e(TAG, "Original memory error view failed", unwrap(displayError));
            }
        }
    }

    private static final class Session {
        final WeakReference<Object> container;
        final WeakReference<Object> plugin;
        Object manager;
        volatile boolean loaded;
        volatile boolean destroyed;
        BiConsumer<String, String> logger;

        Session(Object container, Object plugin) {
            this.container = new WeakReference<>(container);
            this.plugin = new WeakReference<>(plugin);
        }
    }

    /** Plugin-private libraries must not collide with the outer APK's obfuscated classes. */
    private static final class MemoryClassLoader extends PathClassLoader {
        MemoryClassLoader(String dexPath, String libraryPath, ClassLoader parent) {
            super(dexPath, libraryPath, parent);
        }

        @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            Class<?> found = findLoadedClass(name);
            if (found == null) {
                if (shared(name)) found = getParent().loadClass(name);
                else {
                    try { found = findClass(name); }
                    catch (ClassNotFoundException absent) { found = getParent().loadClass(name); }
                }
            }
            if (resolve) resolveClass(found);
            return found;
        }

        private static boolean shared(String name) {
            return name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("android.") || name.startsWith("dalvik.")
                    || name.startsWith("sun.") || name.startsWith("jdk.")
                    || name.startsWith("org.xml.") || name.startsWith("org.w3c.")
                    || name.startsWith("org.json.") || name.startsWith("libcore.")
                    || name.startsWith("com.android.internal.")
                    || name.startsWith("com.android.systemui.plugins.")
                    || name.startsWith("dev.local.supercardhost.")
                    || name.startsWith("vivo.app.") || name.startsWith("com.vivo.framework.");
        }
    }

    /** Host identity for Binder/services/storage, original package resources for every view. */
    private static final class MemoryContext extends ContextWrapper {
        final Context resources;
        final ClassLoader loader;
        private LayoutInflater inflater;

        MemoryContext(Context resources, Context host, ClassLoader loader) {
            super(host);
            this.resources = resources;
            this.loader = loader;
        }

        @Override public ClassLoader getClassLoader() { return loader; }
        @Override public Context getApplicationContext() {
            Context app = getBaseContext().getApplicationContext();
            return app == null ? getBaseContext() : app;
        }
        @Override public Resources getResources() { return resources.getResources(); }
        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public Resources.Theme getTheme() { return resources.getTheme(); }
        @Override public void setTheme(int resource) { resources.setTheme(resource); }
        @Override public String getPackageCodePath() { return resources.getPackageCodePath(); }
        @Override public String getPackageResourcePath() { return resources.getPackageResourcePath(); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences("vivo_memory_plugin_" + name, mode);
        }
        @Override public boolean deleteSharedPreferences(String name) {
            return getBaseContext().deleteSharedPreferences("vivo_memory_plugin_" + name);
        }
        @Override public Object getSystemService(String name) {
            if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                if (inflater == null) {
                    inflater = LayoutInflater.from(resources).cloneInContext(this);
                    // LayoutInflater's process-wide constructor cache accepts constructors
                    // from a parent loader. The outer APK contains different SpringKit
                    // classes with the same names, so inflate plugin views explicitly.
                    inflater.setFactory2(new LayoutInflater.Factory2() {
                        @Override public android.view.View onCreateView(android.view.View parent,
                                String viewName, Context context, android.util.AttributeSet attributes) {
                            return onCreateView(viewName, context, attributes);
                        }
                        @Override public android.view.View onCreateView(String viewName,
                                Context context, android.util.AttributeSet attributes) {
                            if (!viewName.contains(".") || viewName.startsWith("android.")) return null;
                            try {
                                return (android.view.View) loader.loadClass(viewName)
                                        .getConstructor(Context.class, android.util.AttributeSet.class)
                                        .newInstance(context, attributes);
                            } catch (ClassNotFoundException absent) { return null; }
                            catch (ReflectiveOperationException error) {
                                throw new android.view.InflateException("Original memory view: " + viewName, unwrap(error));
                            }
                        }
                    });
                }
                return inflater;
            }
            return super.getSystemService(name);
        }
        @Override public Context createConfigurationContext(Configuration configuration) {
            return new MemoryContext(resources.createConfigurationContext(configuration),
                    getBaseContext().createConfigurationContext(configuration), loader);
        }
        @Override public Context createDisplayContext(android.view.Display display) {
            return new MemoryContext(resources.createDisplayContext(display),
                    getBaseContext().createDisplayContext(display), loader);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Method result = type.getDeclaredMethod(name, parameters);
                result.setAccessible(true);
                return result;
            } catch (NoSuchMethodException absent) { /* Try the superclass. */ }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) { throw unwrap(error); }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }
}
