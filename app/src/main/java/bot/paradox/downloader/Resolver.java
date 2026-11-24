package bot.paradox.downloader;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns whatever the user pasted into one or more downloadable options. */
final class Resolver {
    /** Gofile's public website token (sent by its own web app). */
    private static final String GOFILE_WT = "4fd6sg89d7s6";
    private static volatile boolean newPipeReady;

    private Resolver() {}

    static List<Option> resolve(String url) throws Exception {
        return resolve(url, null, null, new HashSet<>(), 0);
    }

    /** Resolves a mirror option that was picked from a server list. */
    static List<Option> resolve(Option o) throws Exception {
        return resolve(o.url, o.cookie, o.referer, new HashSet<>(), 0);
    }

    private static List<Option> resolve(String raw, String cookie, String referer, Set<String> seen, int depth)
            throws Exception {
        String url = raw.trim();
        if (depth > 6 || !seen.add(url)) throw new IOException("Too many redirects. Try the built-in browser.");
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("Only http and https links work");
        URL u = new URL(url);
        String host = u.getHost().toLowerCase(Locale.ROOT);

        if (is(host, "mega.nz") || is(host, "mega.io")) {
            throw new IOException("Mega files are encrypted and aren't supported yet");
        }
        if (isMediaSite(url)) return video(url);
        if (is(host, "dropbox.com") || is(host, "dropboxusercontent.com")) return one(dropbox(url), "Dropbox");
        if (is(host, "pixeldrain.com")) {
            String id = match(u.getPath(), "/(?:u|api/file)/([A-Za-z0-9_-]+)");
            if (id != null) return one("https://pixeldrain.com/api/file/" + id + "?download", "Pixeldrain");
        }
        if (is(host, "catbox.moe")) return one(url, "Catbox");
        if (is(host, "drive.google.com") || is(host, "docs.google.com")) {
            List<Option> d = drive(url, seen, depth);
            if (!d.isEmpty()) return d;
        }
        if (is(host, "1drv.ms") || is(host, "onedrive.live.com") || is(host, "onedrive.com") || is(host, "sharepoint.com")) {
            List<Option> o = oneDrive(url, cookie, referer);
            if (!o.isEmpty()) return o;
            return page(url, cookie, referer, seen, depth);
        }
        if (is(host, "mediafire.com")) {
            String html = Http.html(url, cookie, referer, 1_500_000);
            String href = match(html, "id=[\"']downloadButton[\"'][^>]*href=[\"']([^\"']+)");
            if (href == null) href = match(html, "href=[\"']([^\"']+)[\"'][^>]*id=[\"']downloadButton");
            if (href != null) return one(MirrorParser.absolute(url, href), "MediaFire");
        }
        if (is(host, "gofile.io")) {
            try {
                List<Option> g = gofile(u);
                if (!g.isEmpty()) return g;
            } catch (Exception ignored) {
                // Fall back to the generic page path below.
            }
        }

        Http.Info info = Http.probe(url, cookie, referer);
        if (Http.isPage(info.type)) return page(info.url, cookie, referer, seen, depth);
        Option o = new Option(info.url, info.name);
        o.size = info.size;
        o.cookie = cookie;
        o.referer = referer;
        return Collections.singletonList(o);
    }

    /** Host equals {@code domain} or is a subdomain of it. */
    private static boolean is(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    // ---- pages ---------------------------------------------------------------

    private static List<Option> page(String url, String cookie, String referer, Set<String> seen, int depth)
            throws Exception {
        String html = Http.html(url, cookie, referer, 1_000_000);
        if (html.isEmpty()) return Collections.emptyList();
        MirrorParser.Result r = MirrorParser.parse(url, html);
        if (!r.choices.isEmpty()) {
            for (Option o : r.choices) {
                o.cookie = cookie;
                o.referer = url;
            }
            return r.choices;
        }
        if (r.next != null) return resolve(r.next, cookie, url, seen, depth + 1);
        return Collections.emptyList();
    }

    /** Links found in a page the user scanned in the built-in browser. */
    static List<Option> scan(String pageUrl, String html, String cookie) {
        MirrorParser.Result r = MirrorParser.parse(pageUrl, html);
        for (Option o : r.choices) {
            o.cookie = cookie;
            o.referer = pageUrl;
        }
        return r.choices;
    }

    // ---- hosts ---------------------------------------------------------------

    private static String dropbox(String url) {
        String s = url.replaceAll("([?&])dl=[^&]*", "$1dl=1");
        if (!s.contains("dl=1")) s += (s.contains("?") ? "&" : "?") + "dl=1";
        return s;
    }

    /** Google Drive now confirms large files on drive.usercontent.google.com. */
    private static List<Option> drive(String url, Set<String> seen, int depth) throws Exception {
        String id = match(url, "(?:/d/|[?&]id=)([-_a-zA-Z0-9]{10,})");
        if (id == null) throw new IOException("This Google Drive link has no file ID");
        String direct = "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t";
        Http.Info info = Http.probe(direct, null, null);
        if (!Http.isPage(info.type)) {
            Option o = new Option(info.url, info.name);
            o.size = info.size;
            return Collections.singletonList(o);
        }
        // Virus-scan or quota page: follow its download form.
        return page(direct, null, null, seen, depth);
    }

    private static List<Option> oneDrive(String url, String cookie, String referer) {
        String[] tries = {
                url + (url.contains("?") ? "&" : "?") + "download=1",
                "https://api.onedrive.com/v1.0/shares/u!"
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8))
                        + "/root/content"
        };
        for (String t : tries) {
            try {
                Http.Info info = Http.probe(t, cookie, referer);
                if (Http.isPage(info.type)) continue;
                Option o = new Option(info.url, info.name);
                o.size = info.size;
                o.cookie = cookie;
                return Collections.singletonList(o);
            } catch (Exception ignored) {
            }
        }
        return Collections.emptyList();
    }

    /** Gofile needs a guest account token, sent as a bearer header and as a cookie on downloads. */
    private static List<Option> gofile(URL u) throws Exception {
        String id = match(u.getPath(), "/d/([a-zA-Z0-9]+)");
        if (id == null) return Collections.emptyList();

        HttpURLConnection c = Http.open("https://api.gofile.io/accounts", "POST", null, null, null);
        c.setDoOutput(true);
        c.getOutputStream().close();
        String token = new JSONObject(Http.text(c, 100_000)).getJSONObject("data").getString("token");

        c = Http.open("https://api.gofile.io/contents/" + id + "?wt=" + GOFILE_WT, "GET", null, null, null);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("X-Website-Token", GOFILE_WT);
        JSONObject data = new JSONObject(Http.text(c, 2_000_000)).getJSONObject("data");

        List<Option> out = new ArrayList<>();
        String cookie = "accountToken=" + token;
        if ("file".equals(data.optString("type"))) {
            addGofile(out, data, cookie);
        } else {
            JSONObject children = data.optJSONObject("children");
            if (children != null) {
                for (Iterator<String> it = children.keys(); it.hasNext(); ) {
                    JSONObject f = children.optJSONObject(it.next());
                    if (f != null && "file".equals(f.optString("type"))) addGofile(out, f, cookie);
                }
            }
        }
        return out;
    }

    private static void addGofile(List<Option> out, JSONObject f, String cookie) {
        String link = f.optString("link");
        if (!link.startsWith("http")) return;
        Option o = new Option(link, f.optString("name", "Gofile file"));
        o.name = f.optString("name", null);
        o.size = f.optLong("size", -1);
        o.cookie = cookie;
        o.referer = "https://gofile.io/";
        out.add(o);
    }

    // ---- video & audio sites (NewPipe) ------------------------------------------

    static synchronized void initNewPipe() {
        if (!newPipeReady) {
            NewPipe.init(new NewPipeHttp());
            newPipeReady = true;
        }
    }

    /** True only for links NewPipe can actually extract (YouTube, SoundCloud, Bandcamp, PeerTube, media.ccc.de). */
    static boolean isMediaSite(String url) {
        try {
            initNewPipe();
            StreamingService s = NewPipe.getServiceByUrl(url);
            return s.getStreamLHFactory().acceptUrl(url);
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Option> video(String url) throws Exception {
        initNewPipe();
        StreamInfo info = StreamInfo.getInfo(url);
        String title = info.getName() == null || info.getName().isEmpty() ? "video" : info.getName();
        List<Option> out = new ArrayList<>();
        for (VideoStream v : info.getVideoStreams()) {
            if (!v.isUrl() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            MediaFormat f = v.getFormat();
            String ext = f == null ? "mp4" : f.getSuffix();
            Option o = new Option(v.getContent(), "Video · " + v.getResolution() + " · " + ext.toUpperCase(Locale.ROOT));
            o.name = Http.safeName(title + "." + ext);
            out.add(o);
        }
        for (AudioStream a : info.getAudioStreams()) {
            if (!a.isUrl() || a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            MediaFormat f = a.getFormat();
            String ext = f == null ? "m4a" : f.getSuffix();
            int kbps = a.getAverageBitrate();
            Option o = new Option(a.getContent(),
                    "Audio · " + (kbps > 0 ? kbps + " kbps · " : "") + ext.toUpperCase(Locale.ROOT));
            o.name = Http.safeName(title + "." + ext);
            out.add(o);
        }
        if (out.isEmpty()) {
            throw new IOException("No single-file stream found. This video only has separate audio and video parts.");
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------------

    private static List<Option> one(String url, String label) {
        if (url == null) return Collections.emptyList();
        return Collections.singletonList(new Option(url, label));
    }

    static String match(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
