package bot.paradox.downloader;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Moves a finished download into Download/Downloader Ultra. */
final class Saver {
    static final String FOLDER = "Downloader Ultra";

    private Saver() {}

    static Uri publish(Context c, File part, String name, String mime) throws IOException {
        if (mime == null || mime.contains("octet-stream")) {
            String byName = Opener.fromName(name);
            mime = byName != null ? byName : "application/octet-stream";
        }
        return Build.VERSION.SDK_INT >= 29 ? mediaStore(c, part, name, mime) : legacy(c, part, name);
    }

    private static Uri mediaStore(Context c, File part, String name, String mime) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Couldn't create the file in Downloads");
        try (InputStream in = new FileInputStream(part); OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("Couldn't write to Downloads");
            copy(in, out);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    private static Uri legacy(Context c, File part, String name) throws IOException {
        if (c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("Allow storage access to save files on this Android version");
        }
        File dir = FilesProvider.folder();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't create the Downloads folder");
        File target = new File(dir, name);
        String ext = Fmt.ext(name);
        String stem = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
        for (int i = 2; target.exists(); i++) {
            target = new File(dir, stem + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext));
        }
        if (!part.renameTo(target)) {
            try (InputStream in = new FileInputStream(part); OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
        }
        return FilesProvider.uriFor(target);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** Deletes a saved file. Returns false if it was already gone or can't be deleted. */
    static boolean delete(Context c, String uri) {
        if (uri == null) return false;
        try {
            return c.getContentResolver().delete(Uri.parse(uri), null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** True if the saved file still exists. */
    static boolean exists(Context c, String uri) {
        if (uri == null) return false;
        try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }
}
