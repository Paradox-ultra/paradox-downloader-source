package bot.paradox.downloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads video thumbnails for the download cards and the quality sheet: memory
 * cache, then a small disk cache, then the network. Downscaled on decode so a
 * 1280px thumbnail costs a card only a few hundred KB.
 */
final class Thumbs {
    interface Done {
        void loaded(boolean ok);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Thumbs() {}

    /** Shows {@code url} in {@code view}; {@code done} says whether a picture is showing. */
    static void load(ImageView view, String url, int maxPx, Done done) {
        view.setTag(R.id.thumb_url, url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            if (done != null) done.loaded(false);
            return;
        }
        String key = url + "@" + maxPx;
        Bitmap hit = MEM.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            if (done != null) done.loaded(true);
            return;
        }
        view.setImageDrawable(null);
        if (done != null) done.loaded(false);
        Context app = view.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = fetch(app, url, maxPx);
            if (b != null) MEM.put(key, b);
            MAIN.post(() -> {
                if (!url.equals(view.getTag(R.id.thumb_url))) return; // view was recycled
                if (b != null) {
                    view.setAlpha(0f);
                    view.setImageBitmap(b);
                    view.animate().alpha(1f).setDuration(180).start();
                }
                if (done != null) done.loaded(b != null);
            });
        });
    }

    private static Bitmap fetch(Context c, String url, int maxPx) {
        try {
            File dir = new File(c.getCacheDir(), "thumbs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, hash(url));
            if (!f.exists() || f.length() == 0) {
                HttpURLConnection conn = Http.open(url, "GET", null, null, null);
                try {
                    if (conn.getResponseCode() / 100 != 2) return null;
                    File tmp = new File(dir, f.getName() + ".tmp");
                    try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        long total = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            total += n;
                            if (total > 8_000_000) return null;
                        }
                    }
                    if (!tmp.renameTo(f)) return null;
                } finally {
                    conn.disconnect();
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }
}
