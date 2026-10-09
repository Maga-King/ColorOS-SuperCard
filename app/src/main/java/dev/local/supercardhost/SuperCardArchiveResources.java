package dev.local.supercardhost;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Original APK resources and native libraries, independent of an installed vivo package. */
public final class SuperCardArchiveResources {
    private static final String TAG = "SuperCardArchive";
    public static final String PACKAGE = "com.vivo.card";
    public static final String ASSET = "supercard-resources.apk";

    private SuperCardArchiveResources() { }

    public static synchronized Context open(Context host, Context module) throws Exception {
        if (hasOriginalResources(module)) {
            // The final merged APK preserves vivo's resource table and exposes its real local
            // components under the module package. Do not extract a second APK in this case.
            Log.i(TAG, "Using merged original SuperCard resources from module APK");
            return module;
        }
        File root = new File(host.getCodeCacheDir(), "supercard_archive");
        mkdir(root);
        File temporary = File.createTempFile("resources-", ".part", root);
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = module.getAssets().open(ASSET);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                // Protect before writing, as required for dynamically loaded APKs on recent Android.
                if (!temporary.setReadOnly()) throw new IOException("Cannot protect resource archive");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            hash = hex(digest.digest());
            File version = new File(root, hash);
            mkdir(version);
            File archive = new File(version, ASSET);
            if (archive.isFile() && archive.length() == temporary.length()) {
                if (!temporary.delete()) throw new IOException("Cannot discard temporary archive");
            } else {
                if (archive.exists() && !archive.delete()) throw new IOException("Cannot replace resource archive");
                if (!temporary.renameTo(archive)) throw new IOException("Cannot publish resource archive");
            }
            PackageInfo parsed = host.getPackageManager().getPackageArchiveInfo(archive.getAbsolutePath(), 0);
            if (parsed == null || parsed.applicationInfo == null || !PACKAGE.equals(parsed.packageName)) {
                throw new IOException("Embedded SuperCard manifest mismatch");
            }
            ApplicationInfo info = new ApplicationInfo(parsed.applicationInfo);
            info.sourceDir = archive.getAbsolutePath();
            info.publicSourceDir = archive.getAbsolutePath();
            info.splitSourceDirs = null;
            info.splitPublicSourceDirs = null;
            info.uid = Process.myUid();
            info.dataDir = host.getApplicationInfo().dataDir;
            info.nativeLibraryDir = extractNativeLibraries(archive, version);
            Resources original = host.getPackageManager().getResourcesForApplication(info);
            // Resolve a genuine application resource before publishing the context. This also
            // prevents a malformed archive from silently falling back to module resources.
            if (info.labelRes != 0 && !PACKAGE.equals(original.getResourcePackageName(info.labelRes))) {
                throw new Resources.NotFoundException("Original SuperCard resource package mismatch");
            }
            Log.i(TAG, "Original resources loaded from embedded APK " + parsed.versionName
                    + "; nativeLibraries=" + (info.nativeLibraryDir == null ? "none" : "extracted"));
            return new ArchiveContext(host, original.getAssets(), info);
        } finally {
            if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Temporary archive cleanup deferred");
        }
    }

    public static boolean hasOriginalResources(Context context) {
        try {
            Resources resources = context.getResources();
            int layout = resources.getIdentifier("activity_card_setting", "layout", PACKAGE);
            if (layout == 0 || !PACKAGE.equals(resources.getResourcePackageName(layout))) return false;
            resources.getLayout(layout).close();
            return true;
        } catch (Resources.NotFoundException error) { return false; }
    }

    private static String extractNativeLibraries(File archive, File version) throws IOException {
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        try (ZipFile zip = new ZipFile(archive)) {
            for (String abi : abis) {
                if (!abi.matches("[A-Za-z0-9_-]+")) continue;
                String prefix = "lib/" + abi + "/";
                List<ZipEntry> libraries = new ArrayList<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                long total = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                    String name = entry.getName().substring(prefix.length());
                    if (!name.matches("lib[A-Za-z0-9_.+\\-]+\\.so")) {
                        throw new IOException("Invalid embedded library path");
                    }
                    if (entry.getSize() < 0 || entry.getSize() > 64L * 1024 * 1024
                            || (total += entry.getSize()) > 128L * 1024 * 1024 || libraries.size() >= 256) {
                        throw new IOException("Embedded library size limit exceeded");
                    }
                    libraries.add(entry);
                }
                if (libraries.isEmpty()) continue;
                File directory = new File(new File(version, "lib"), abi);
                mkdir(directory);
                for (ZipEntry entry : libraries) {
                    File library = new File(directory, entry.getName().substring(prefix.length()));
                    if (!library.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
                        throw new IOException("Embedded library escaped cache");
                    }
                    if (library.isFile() && library.length() == entry.getSize()) continue;
                    File temporary = File.createTempFile("native-", ".part", directory);
                    try {
                        try (InputStream input = zip.getInputStream(entry);
                             FileOutputStream output = new FileOutputStream(temporary)) {
                            if (!temporary.setReadOnly()) throw new IOException("Cannot protect native library");
                            input.transferTo(output);
                            output.getFD().sync();
                        }
                        if (temporary.length() != entry.getSize()) throw new IOException("Incomplete native library");
                        if (library.exists() && !library.delete()) throw new IOException("Cannot replace native library");
                        if (!temporary.renameTo(library)) throw new IOException("Cannot publish native library");
                    } finally { if (temporary.exists()) temporary.delete(); }
                }
                return directory.getAbsolutePath();
            }
        }
        return null;
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create archive cache");
    }

    private static String hex(byte[] digest) {
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte b : digest) value.append(Character.forDigit((b >>> 4) & 15, 16))
                .append(Character.forDigit(b & 15, 16));
        return value.toString();
    }

    private static final class ArchiveContext extends ContextWrapper {
        private final ApplicationInfo info;
        private final Resources resources;
        private final Resources.Theme theme;
        private Configuration lastConfiguration;
        private DisplayMetrics lastMetrics;

        @SuppressWarnings("deprecation")
        ArchiveContext(Context host, AssetManager assets, ApplicationInfo info) {
            super(host);
            this.info = new ApplicationInfo(info);
            lastConfiguration = new Configuration(host.getResources().getConfiguration());
            lastMetrics = new DisplayMetrics();
            lastMetrics.setTo(host.getResources().getDisplayMetrics());
            resources = new Resources(assets, lastMetrics, lastConfiguration);
            theme = resources.newTheme();
            theme.applyStyle(info.theme != 0 ? info.theme : android.R.style.Theme_DeviceDefault, true);
        }

        @SuppressWarnings("deprecation")
        @Override public synchronized Resources getResources() {
            Resources current = getBaseContext().getResources();
            Configuration configuration = current.getConfiguration();
            DisplayMetrics metrics = current.getDisplayMetrics();
            if (!lastConfiguration.equals(configuration) || !lastMetrics.equals(metrics)) {
                lastConfiguration = new Configuration(configuration);
                lastMetrics = new DisplayMetrics(); lastMetrics.setTo(metrics);
                // Only this archive's Resources changes; host/global density is never modified.
                resources.updateConfiguration(lastConfiguration, lastMetrics);
                theme.rebase();
            }
            return resources;
        }

        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public String getPackageName() { return PACKAGE; }
        @Override public String getPackageCodePath() { return info.sourceDir; }
        @Override public String getPackageResourcePath() { return info.publicSourceDir; }
        @Override public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(info); }
        @Override public Resources.Theme getTheme() { getResources(); return theme; }
        @Override public void setTheme(int resource) { getTheme().applyStyle(resource, true); }
        @Override public Object getSystemService(String name) {
            if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                return LayoutInflater.from(getBaseContext()).cloneInContext(this);
            }
            return super.getSystemService(name);
        }
        @Override public Context createConfigurationContext(Configuration configuration) {
            return new ArchiveContext(getBaseContext().createConfigurationContext(configuration), resources.getAssets(), info);
        }
        @Override public Context createDisplayContext(Display display) {
            return new ArchiveContext(getBaseContext().createDisplayContext(display), resources.getAssets(), info);
        }
    }
}
