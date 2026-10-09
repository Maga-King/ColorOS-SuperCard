package dev.local.supercardhost;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

/** Original memory UI resources from an APK asset; never requires its package to be installed. */
final class MemoryArchiveResources extends ContextWrapper {
    private static final String PACKAGE = "com.vivo.memory.card";
    final File archive;
    final Resources resources;
    final Resources.Theme theme;
    final int themeId;

    private MemoryArchiveResources(Context host, File archive, Resources resources, int themeId) {
        super(host);
        this.archive = archive;
        this.resources = resources;
        this.themeId = themeId == 0 ? android.R.style.Theme_Material_Light_NoActionBar : themeId;
        theme = resources.newTheme();
        theme.applyStyle(this.themeId, true);
    }

    static MemoryArchiveResources load(Context host, Context module, File directory) throws Exception {
        File archive = copyAsset(module, "memory-plugin.apk", directory);
        PackageInfo info = host.getPackageManager().getPackageArchiveInfo(archive.getAbsolutePath(), 0);
        if (info == null || info.applicationInfo == null || !PACKAGE.equals(info.packageName))
            throw new IllegalArgumentException("Embedded memory APK manifest mismatch");
        info.applicationInfo.sourceDir = archive.getAbsolutePath();
        info.applicationInfo.publicSourceDir = archive.getAbsolutePath();
        info.applicationInfo.splitSourceDirs = null;
        info.applicationInfo.splitPublicSourceDirs = null;
        // This original plugin archive contains no native libraries. A future archive
        // must explicitly add ABI extraction rather than borrow an installed package's path.
        try (ZipFile zip = new ZipFile(archive)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/") && name.endsWith(".so"))
                    throw new IOException("Embedded memory plugin now requires explicit native library loading");
            }
        }
        Resources raw = host.getPackageManager().getResourcesForApplication(info.applicationInfo);
        Resources resources = new Resources(raw.getAssets(), host.getResources().getDisplayMetrics(),
                host.getResources().getConfiguration());
        for (String name : new String[]{"card_fragment_layout", "card_memory_main_layout",
                "card_memory_bottom_layout", "item_card_attachment_layout"}) {
            int id = resources.getIdentifier(name, "layout", PACKAGE);
            if (id == 0) throw new Resources.NotFoundException("Missing embedded memory layout " + name);
            resources.getLayout(id).close();
        }
        int id = resources.getIdentifier("card_view", "id", PACKAGE);
        if (id == 0) throw new Resources.NotFoundException("Missing original memory card_view ID");
        return new MemoryArchiveResources(host, archive, resources, info.applicationInfo.theme);
    }

    /** Content-addressed, read-only files keep loaded resources stable across module updates. */
    static File copyAsset(Context module, String asset, File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create memory archive directory");
        File temporary = File.createTempFile("memory-", ".tmp", directory);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            try (var input = module.getAssets().open(asset);
                 var output = new FileOutputStream(temporary)) {
                if (!temporary.setReadOnly()) throw new IOException("Cannot protect memory archive cache");
                byte[] buffer = new byte[16_384];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                    digest.update(buffer, 0, length);
                }
                output.getFD().sync();
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) {
                hash.append(Character.forDigit((value & 255) >>> 4, 16));
                hash.append(Character.forDigit(value & 15, 16));
            }
            File cached = new File(directory, hash + "-" + asset);
            if (cached.exists()) {
                if (!cached.isFile() || cached.length() != temporary.length())
                    throw new IOException("Invalid existing memory archive cache");
                if (!cached.setReadOnly()) throw new IOException("Cannot protect existing memory archive");
                return cached;
            }
            if (!temporary.renameTo(cached)) throw new IOException("Cannot publish memory archive cache");
            return cached;
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
    @Override public Resources.Theme getTheme() { return theme; }
    @Override public void setTheme(int id) { theme.applyStyle(id, true); }
    @Override public Context getApplicationContext() {
        Context app = getBaseContext().getApplicationContext();
        return app == null ? getBaseContext() : app;
    }
    @Override public String getPackageCodePath() { return archive.getAbsolutePath(); }
    @Override public String getPackageResourcePath() { return archive.getAbsolutePath(); }
    @Override public Context createConfigurationContext(Configuration configuration) {
        return configured(getBaseContext().createConfigurationContext(configuration));
    }
    @Override public Context createDisplayContext(Display display) {
        return configured(getBaseContext().createDisplayContext(display));
    }
    private MemoryArchiveResources configured(Context host) {
        Resources changed = new Resources(resources.getAssets(), host.getResources().getDisplayMetrics(),
                host.getResources().getConfiguration());
        MemoryArchiveResources context = new MemoryArchiveResources(host, archive, changed, themeId);
        context.theme.setTo(theme);
        return context;
    }
}
