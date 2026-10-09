package dev.local.supercardhost;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Private staging files: SystemUI writes, a URI grant allows the memory app to read. */
public final class MemoryTransferProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.local.supercardhost.memory-transfer";
    public static final Uri ROOT = Uri.parse("content://" + AUTHORITY + "/items");

    @Override public boolean onCreate() { return true; }

    private boolean isSystemUi(int uid) {
        if (!"com.android.systemui".equals(getCallingPackage())) return false;
        try { return uid == getContext().getPackageManager().getApplicationInfo("com.android.systemui", 0).uid; }
        catch (PackageManager.NameNotFoundException absent) { return false; }
    }

    private void enforceWriter() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        if (!isSystemUi(uid)) {
            throw new SecurityException("Memory staging is owned by SystemUI");
        }
    }

    private void enforceReader(Uri uri) {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        if (isSystemUi(uid)) return;
        if (!"com.oplus.aimemory".equals(getCallingPackage())
                || getContext().checkUriPermission(uri, Binder.getCallingPid(), uid,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Missing memory file read grant");
        }
    }

    private File file(Uri uri) {
        if (!AUTHORITY.equals(uri.getAuthority()) || !"content".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Invalid memory URI");
        }
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 3 || !"items".equals(parts.get(0))
                || !parts.get(1).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !parts.get(2).matches("[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException("Invalid memory item");
        }
        return new File(new File(getContext().getFilesDir(), "memory-transfer"),
                parts.get(1) + "_" + parts.get(2));
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        enforceWriter();
        if (!ROOT.equals(uri)) throw new IllegalArgumentException("Insert requires items root");
        String name = values == null ? null : values.getAsString(OpenableColumns.DISPLAY_NAME);
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException("Invalid display name");
        }
        Uri result = ROOT.buildUpon().appendPath(UUID.randomUUID().toString()).appendPath(name).build();
        File target = file(result);
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create staging directory");
        try {
            if (!target.createNewFile()) throw new IOException("Staging collision");
        } catch (IOException error) { throw new IllegalStateException("Cannot create memory staging", error); }
        // Grant as the provider owner. Giving SystemUI the global read permission
        // makes Android omit a URI grant, but the original RecordExecutor explicitly
        // checks checkUriPermission before it reads an attachment.
        long identity = Binder.clearCallingIdentity();
        try {
            getContext().grantUriPermission("com.android.systemui", result,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } finally { Binder.restoreCallingIdentity(identity); }
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File target = file(uri);
        if ("r".equals(mode)) {
            enforceReader(uri);
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if ("w".equals(mode) || "wt".equals(mode)) {
            String caller = getCallingPackage();
            boolean grantedCamera = ("com.oplus.camera".equals(caller) || "com.android.camera".equals(caller))
                    && getContext().checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(),
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            if (!grantedCamera) enforceWriter();
            if (!target.exists()) throw new java.io.FileNotFoundException("Insert item before writing");
            return ParcelFileDescriptor.open(target,
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        throw new java.io.FileNotFoundException("Unsupported staging mode");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] args, String order) {
        enforceReader(uri);
        File target = file(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        String name = uri.getLastPathSegment();
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) row[index] = name;
            if (OpenableColumns.SIZE.equals(columns[index])) row[index] = target.length();
        }
        result.addRow(row);
        return result;
    }

    @Override public String getType(Uri uri) {
        file(uri);
        String name = uri.getLastPathSegment().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".m4a") || name.endsWith(".mp4")) return "audio/mp4";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        enforceWriter();
        File target = file(uri);
        return target.exists() && target.delete() ? 1 : 0;
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Immutable staging metadata");
    }
}
