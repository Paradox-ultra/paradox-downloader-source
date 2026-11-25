package bot.paradox.downloader;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Files shared into the app, one or several at a time (from a file manager or gallery; Telegram
 * only offers Share for a single file, and Forward for several).
 * Each file is copied into Download/Downloader Ultra and listed as a finished download.
 */
final class ShareImport {
    private ShareImport() {}

    /** The files an ACTION_SEND / ACTION_SEND_MULTIPLE intent carries, or an empty list. */
    static List<Uri> files(Intent i) {
        List<Uri> out = new ArrayList<>();
        String a = i.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) out.addAll(l);
        }
        if (out.isEmpty() && i.getClipData() != null) {
            ClipData cd = i.getClipData();
            for (int k = 0; k < cd.getItemCount(); k++) {
                Uri u = cd.getItemAt(k).getUri();
                if (u != null) out.add(u);
            }
        }
        return out;
    }

    static void run(Context c, List<Uri> uris) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(app, uris.size() == 1 ? "Saving file…" : "Saving " + uris.size() + " files…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0;
            JobStore store = JobStore.get(app);
            for (Uri u : uris) {
                File tmp = null;
                try {
                    ContentResolver cr = app.getContentResolver();
                    String name = name(cr, u);
                    String mime = cr.getType(u);
                    tmp = new File(app.getCacheDir(), "share-" + UUID.randomUUID() + ".part");
                    long size;
                    try (InputStream in = cr.openInputStream(u); FileOutputStream out = new FileOutputStream(tmp)) {
                        if (in == null) throw new java.io.IOException("Can't read the shared file");
                        byte[] buf = new byte[256 * 1024];
                        long n = 0;
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            out.write(buf, 0, r);
                            n += r;
                        }
                        size = n;
                    }
                    Uri saved = Saver.publish(app, tmp, name, mime);
                    Job j = new Job();
                    j.id = UUID.randomUUID().toString();
                    j.name = name;
                    j.nameFixed = true;
                    j.state = Job.DONE;
                    j.done = size;
                    j.total = size;
                    j.uri = saved.toString();
                    j.mime = mime;
                    j.created = System.currentTimeMillis();
                    store.add(j);
                    ok++;
                } catch (Throwable e) {
                    // Skip this file; the others still save.
                } finally {
                    if (tmp != null) //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                }
            }
            final int saved = ok;
            main.post(() -> Toast.makeText(app, saved == uris.size()
                    ? (saved == 1 ? "Saved to Downloads" : "Saved " + saved + " files to Downloads")
                    : "Saved " + saved + " of " + uris.size() + " files", Toast.LENGTH_LONG).show());
        }, "share-import").start();
    }

    private static String name(ContentResolver cr, Uri u) {
        String n = null;
        try (Cursor q = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) n = q.getString(0);
        } catch (RuntimeException ignored) {
            // Fall through to the path.
        }
        if (n == null || n.isEmpty()) n = u.getLastPathSegment();
        if (n == null || n.isEmpty()) n = "shared-file";
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (Fmt.ext(n).isEmpty()) {
            String t = cr.getType(u);
            String e = t == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(t);
            if (e != null) n = n + "." + e;
        }
        return n;
    }
}
