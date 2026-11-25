package bot.paradox.downloader;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * The browser's ad and tracker blocker: loads the filter lists (bundled, then refreshed from their
 * maintainers every few days), builds the page script that hides ad elements, and keeps one
 * {@link AdFilter} in memory while the browser is open.
 */
final class AdBlock {
    private static final String UA_RAW = "https://raw.githubusercontent.com/";
    private static final String UAS = UA_RAW + "uBlockOrigin/uAssets/master/";
    /** name, source. The names match the bundled assets/adblock/NAME.txt.gz. */
    private static final String[][] LISTS = {
            {"easylist", UAS + "thirdparties/easylist/easylist.txt"},
            {"easyprivacy", UAS + "thirdparties/easylist/easyprivacy.txt"},
            {"ubo", UAS + "filters/filters.txt"},
            {"ubo-privacy", UAS + "filters/privacy.txt"},
            {"adguard-mobile", UA_RAW + "AdguardTeam/FiltersRegistry/master/filters/filter_11_Mobile/filter.txt"},
            {"cookies", UA_RAW + "easylist/easylist/master/easylist_cookie/easylist_cookie_general_hide.txt"},
            {"hosts", UA_RAW + "StevenBlack/hosts/master/hosts"},
    };
    private static final long UPDATE_EVERY_MS = 3L * 24 * 3600 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final List<Runnable> WAITERS = new ArrayList<>();

    private static volatile AdFilter filter;
    private static volatile String script;
    private static boolean loading;

    private AdBlock() {}

    static boolean enabled(Context c) {
        return Prefs.sp(c).getBoolean("adblock", true);
    }

    static void enabled(Context c, boolean on) {
        Prefs.sp(c).edit().putBoolean("adblock", on).apply();
    }

    /** The loaded filter, or null while the lists are still being read. */
    static AdFilter filter() {
        return filter;
    }

    /** The script that hides ad elements; run it in every frame as early as possible. */
    static String script() {
        return script;
    }

    /** Runs {@code r} on the main thread once the lists are loaded (or after 5 s, so a bad list never stalls a page). */
    static void whenReady(Context c, Runnable r) {
        final AtomicBoolean once = new AtomicBoolean();
        Runnable run = () -> {
            if (once.compareAndSet(false, true)) r.run();
        };
        synchronized (LOCK) {
            if (filter != null) {
                MAIN.post(run);
                return;
            }
            WAITERS.add(run);
            if (!loading) {
                loading = true;
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    Built b = build(app);
                    List<Runnable> go;
                    synchronized (LOCK) {
                        filter = b.filter;
                        script = b.script;
                        loading = false;
                        go = new ArrayList<>(WAITERS);
                        WAITERS.clear();
                    }
                    for (Runnable w : go) MAIN.post(w);
                }, "adblock-load").start();
            }
        }
        MAIN.postDelayed(run, 5000);
    }

    /** Frees the lists (about 20 MB) when the browser closes. */
    static void release() {
        synchronized (LOCK) {
            if (!loading) {
                filter = null;
                script = null;
            }
        }
    }

    private static final class Built {
        AdFilter filter;
        String script;
    }

    private static Built build(Context c) {
        AdFilter f = new AdFilter();
        for (String[] l : LISTS) {
            try (InputStream in = open(c, l[0]);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                f.load(r);
            } catch (Throwable ignored) {
                // A list that can't be read is skipped; the others still work.
            }
        }
        Built b = new Built();
        b.filter = f;
        b.script = pageScript(f);
        return b;
    }

    private static InputStream open(Context c, String name) throws java.io.IOException {
        File f = new File(new File(c.getFilesDir(), "adblock"), name + ".txt");
        if (f.length() > 1000) return new FileInputStream(f);
        return new GZIPInputStream(c.getAssets().open("adblock/" + name + ".txt.gz"), 1 << 16);
    }

    // ---- updating --------------------------------------------------------------------------

    /** Refreshes the lists if they are older than three days and the connection isn't metered. */
    static void maybeUpdate(Context c) {
        if (!enabled(c)) return;
        long last = Prefs.sp(c).getLong("adblock_updated", 0);
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return;
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm == null || cm.isActiveNetworkMetered()) return;
        } catch (RuntimeException e) {
            return;
        }
        update(c, null);
    }

    interface Done {
        void finished(int updated, int total);
    }

    /** Downloads every list, in the background. {@code done} runs on the main thread. */
    static void update(Context c, Done done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            File dir = new File(app.getFilesDir(), "adblock");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            int ok = 0;
            for (String[] l : LISTS) {
                File tmp = new File(dir, l[0] + ".tmp");
                try {
                    HttpURLConnection h = Http.open(l[1], "GET", null, null, null);
                    h.setReadTimeout(60000);
                    try {
                        if (h.getResponseCode() != 200) continue;
                        try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                    } finally {
                        h.disconnect();
                    }
                    if (tmp.length() > 1000 && tmp.renameTo(new File(dir, l[0] + ".txt"))) ok++;
                } catch (Throwable ignored) {
                    // Keep the previous copy of this list.
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            if (ok > 0) {
                Prefs.sp(app).edit().putLong("adblock_updated", System.currentTimeMillis()).apply();
                if (filter != null) {
                    Built b = build(app); // swap in the fresh lists for the open browser
                    filter = b.filter;
                    script = b.script;
                }
            }
            final int updated = ok;
            if (done != null) MAIN.post(() -> done.finished(updated, LISTS.length));
        }, "adblock-update").start();
    }

    /** One line for Settings. */
    static String summary(Context c) {
        if (!enabled(c)) return "Off · ads and trackers load normally";
        long last = Prefs.sp(c).getLong("adblock_updated", 0);
        String age;
        if (last == 0) {
            age = "built-in lists";
        } else {
            long days = (System.currentTimeMillis() - last) / (24L * 3600 * 1000);
            age = days == 0 ? "lists updated today" : "lists updated " + days + "d ago";
        }
        return "On · EasyList, EasyPrivacy, uBlock, AdGuard · " + age;
    }

    // ---- page scripts ----------------------------------------------------------------------

    private static String pageScript(AdFilter f) {
        JSONArray classes = new JSONArray(f.genericClassNames());
        JSONArray ids = new JSONArray(f.genericIdNames());
        String css = JSONObject.quote(f.genericStylesheet());
        return "(function(){if(window.__ulab)return;window.__ulab=1;"
                + "var C=new Set(" + classes + "),I=new Set(" + ids + "),css=" + css + ";"
                + "function style(t){var s=document.createElement('style');s.textContent=t;var r=document.head||document.documentElement;if(r)r.appendChild(s)}"
                + "function esc(x){return(window.CSS&&CSS.escape)?CSS.escape(x):x}"
                + "function boot(){if(css)style(css);var done=new Set(),timer=0;"
                + "function scan(){timer=0;var out=[],els=document.querySelectorAll('[class],[id]');"
                + "for(var i=0;i<els.length;i++){var e=els[i],id=e.id;"
                + "if(id&&typeof id==='string'&&I.has(id)){var k='#'+esc(id);if(!done.has(k)){done.add(k);out.push(k)}}"
                + "var cl=e.classList;if(cl)for(var j=0;j<cl.length;j++){var c=cl[j];if(C.has(c)){var k2='.'+esc(c);if(!done.has(k2)){done.add(k2);out.push(k2)}}}}"
                + "if(out.length)style(out.join(',')+'{display:none!important}')}"
                + "function sched(){if(!timer)timer=setTimeout(scan,400)}"
                + "scan();new MutationObserver(sched).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','id']})}"
                + "if(document.documentElement)boot();else{var mo=new MutationObserver(function(){if(document.documentElement){mo.disconnect();boot()}});mo.observe(document,{childList:true})}"
                + "})();";
    }

    /** Hides the selectors that only apply to this site. */
    static String siteScript(AdFilter f, String host) {
        String css = f.siteStylesheet(host);
        if (css.isEmpty()) return null;
        return "(function(){var o=document.getElementById('ul-ab-site');if(o)o.remove();var s=document.createElement('style');s.id='ul-ab-site';"
                + "s.textContent=" + JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s)})()";
    }
}
