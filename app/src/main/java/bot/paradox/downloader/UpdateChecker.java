package bot.paradox.downloader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer release on GitHub at most every 12 hours.
 *
 * <p>Fix from v4.2: the repository owner is the current account, and only APKs
 * hosted under that exact owner are accepted. v4.2 asked the old account name
 * and relied on GitHub's rename redirect.
 */
final class UpdateChecker {
    interface Done {
        void result(boolean shown);
    }

    static final String OWNER = "Paradox-ultra";
    static final String REPO = "paradox-downloader";
    private static final long OK_INTERVAL = 12 * 60 * 60 * 1000L;
    private static final long RETRY_INTERVAL = 2 * 60 * 60 * 1000L;
    private static final Pattern CODE = Pattern.compile("(?i)versionCode\\s*[:=]\\s*(\\d{1,9})");

    private static final class Found {
        long code;
        String name;
        String notes;
        String apk;
        String apkName;
    }

    private UpdateChecker() {}

    static void maybeCheck(Activity a, Done done) {
        SharedPreferences sp = Prefs.sp(a);
        long now = System.currentTimeMillis();
        long next = sp.getLong("updateNext", 0);
        if (now < next && next - now <= OK_INTERVAL) {
            done.result(false);
            return;
        }
        sp.edit().putLong("updateNext", now + RETRY_INTERVAL).apply();
        run(a, false, done);
    }

    static void checkNow(Activity a) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        run(a, true, null);
    }

    private static void run(Activity a, boolean manual, Done done) {
        WeakReference<Activity> ref = new WeakReference<>(a);
        SharedPreferences sp = Prefs.sp(a);
        Thread t = new Thread(() -> {
            Found f = null;
            boolean ok = false;
            try {
                HttpURLConnection c = Http.open("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=10",
                        "GET", null, null, null);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Paradox-Downloader/" + BuildConfig.VERSION_NAME);
                f = pick(Http.text(c, 500_000));
                ok = true;
                sp.edit().putLong("updateNext", System.currentTimeMillis() + OK_INTERVAL).apply();
            } catch (Exception ignored) {
            }
            Found found = f;
            boolean fetched = ok;
            Activity act = ref.get();
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                boolean newer = found != null && found.code > BuildConfig.VERSION_CODE;
                boolean skipped = newer && !manual && sp.getLong("updateSkip", 0) == found.code;
                if (newer && !skipped) {
                    show(act, found);
                    if (done != null) done.result(true);
                    return;
                }
                if (manual) {
                    Toast.makeText(act, fetched ? "You're on the latest version" : "Couldn't check for updates",
                            Toast.LENGTH_SHORT).show();
                }
                if (done != null) done.result(false);
            });
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static Found pick(String json) throws Exception {
        JSONArray releases = new JSONArray(json);
        Found best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
            String body = r.optString("body", "");
            Matcher m = CODE.matcher(body);
            if (!m.find()) continue;
            long code = Long.parseLong(m.group(1));
            if (best != null && code <= best.code) continue;
            JSONArray assets = r.optJSONArray("assets");
            if (assets == null) continue;
            // From v4.4 there is one APK per CPU type; take the one for this phone.
            JSONObject chosen = null;
            int chosenScore = -1;
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.optJSONObject(k);
                if (a == null) continue;
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (!name.toLowerCase().endsWith(".apk")
                        || !url.startsWith("https://github.com/" + OWNER + "/" + REPO + "/releases/download/")) continue;
                int score = abiScore(name);
                if (score > chosenScore) {
                    chosen = a;
                    chosenScore = score;
                }
            }
            if (chosen == null || chosenScore == 0) continue;
            Found f = new Found();
            f.code = code;
            f.apk = chosen.optString("browser_download_url");
            f.apkName = chosen.optString("name");
            String title = r.optString("name", "").trim();
            f.name = title.isEmpty() || title.equals("null") ? r.optString("tag_name", "New version") : title;
            f.notes = notes(body, f.name);
            best = f;
        }
        return best;
    }

    /**
     * 3 = built for this phone's main CPU type, 2 = another type it can run,
     * 1 = a universal APK, 0 = can't run here.
     */
    private static int abiScore(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        String[] known = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};
        String tagged = null;
        for (String abi : known) {
            if (n.contains(abi)) {
                tagged = abi;
                break;
            }
        }
        if (tagged == null) return 1;
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        for (int i = 0; i < abis.length; i++) if (abis[i].equals(tagged)) return i == 0 ? 3 : 2;
        return 0;
    }

    /** First few meaningful lines of the release notes, as plain text. */
    private static String notes(String body, String title) {
        StringBuilder out = new StringBuilder();
        int lines = 0;
        for (String raw : body.replace("\r", "").split("\n")) {
            String l = raw.trim();
            String low = l.toLowerCase();
            if (l.isEmpty() || low.contains("versioncode") || low.contains("sha-256") || low.contains("sha256")) continue;
            l = l.replaceAll("^#+\\s*", "").replaceAll("^[-*•]\\s+", "• ").replace("**", "").replace("`", "")
                    .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").trim();
            if (l.isEmpty() || l.equalsIgnoreCase(title)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(l);
            if (++lines >= 4 || out.length() >= 260) break;
        }
        return out.toString();
    }

    private static void show(Activity a, Found f) {
        new MaterialAlertDialogBuilder(a)
                .setIcon(R.drawable.ic_update)
                .setTitle("Update available")
                .setMessage(f.name + (f.notes.isEmpty() ? "" : "\n\n" + f.notes))
                .setPositiveButton("Download", (d, w) -> {
                    Option o = new Option(f.apk, f.apkName);
                    o.name = f.apkName;
                    DownloadService.add(a, o, f.apk);
                    Toast.makeText(a, "Downloading the update. Tap it when it's done to install.", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Skip this version", (d, w) -> Prefs.sp(a).edit().putLong("updateSkip", f.code).apply())
                .setNegativeButton("Later", null)
                .show();
    }
}
