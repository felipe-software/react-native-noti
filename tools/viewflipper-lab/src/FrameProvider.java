package dev.noti.flipperlab;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

public final class FrameProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "image/png"; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"r".equals(mode) || name == null || !name.matches("frame_[0-9]+\\.png")) {
            throw new FileNotFoundException("Unknown lab frame");
        }
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), name), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
