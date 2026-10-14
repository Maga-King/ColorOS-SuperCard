package dev.local.supercardhost;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Starts the backend when Android initializes this app, without replacing the OEM Application. */
public final class RootDisplayBootstrapProvider extends ContentProvider {
    @Override public boolean onCreate() {
        RootAppSetup.request(getContext(), false, null);
        AutoVirtualDisplayManager.start(getContext(), null);
        return true;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
