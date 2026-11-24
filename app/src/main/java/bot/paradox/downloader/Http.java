package bot.paradox.downloader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small HTTP helpers shared by the resolver and the download engine. */
final class Http {
    static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

    private static final Pattern FN_STAR =
            Pattern.compile("filename\\*\\s*=\\s*(?:([\\w!#$%&+^`{}~-]+)'[^']*')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FN =
            Pattern.compile("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", Pattern.CASE_INSENSITIVE);

    private Http() {}

    /** What a probe learned about a URL. */
    static final class Info {
        String url;
        String name;
        String type;
        long size = -1;
        boolean ranges;
        String cookie;
        String referer;
    }

    static HttpURLConnection open(String url, String method, String range, String cookie, String referer)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) c.setRequestProperty("Range", range);
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        return c;
    }

    /**
     * Finds the final URL, size, name, type and whether byte ranges work.
     * HEAD is tried first, then a 1-byte ranged GET confirms range support,
     * because many servers answer HEAD wrongly or not at all.
     */
    static Info probe(String url, String cookie, String referer) throws IOException {
        Info info = new Info();
        info.url = url;
        info.cookie = cookie;
        info.referer = referer;

        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD", null, cookie, referer);
            if (c.getResponseCode() < 400) {
                info.url = c.getURL().toString();
                info.name = filename(c.getHeaderField("Content-Disposition"));
                info.type = c.getContentType();
                info.size = number(c.getHeaderField("Content-Length"));
            }
        } catch (IOException ignored) {
            // Fall through to the GET probe.
        } finally {
            if (c != null) c.disconnect();
        }

        c = open(info.url, "GET", "bytes=0-0", cookie, referer);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Server answered HTTP " + code);
            String cr = c.getHeaderField("Content-Range");
            if (code == 206 && cr != null && cr.startsWith("bytes 0-0/")) {
                info.ranges = true;
                long total = number(cr.substring(cr.lastIndexOf('/') + 1));
                if (total > 0) info.size = total;
            } else {
                info.ranges = false;
                long len = number(c.getHeaderField("Content-Length"));
                if (len >= 0) info.size = len;
            }
            info.url = c.getURL().toString();
            String n = filename(c.getHeaderField("Content-Disposition"));
            if (n != null) info.name = n;
            if (c.getContentType() != null) info.type = c.getContentType();
        } finally {
            c.disconnect();
        }

        if (info.name == null || info.name.isEmpty()) info.name = nameFromUrl(info.url);
        info.name = safeName(info.name);
        return info;
    }

    /** Reads a text/HTML response, up to {@code limit} bytes. Returns "" for non-HTML. */
    static String html(String url, String cookie, String referer, int limit) throws IOException {
        HttpURLConnection c = open(url, "GET", null, cookie, referer);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Server answered HTTP " + code);
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("html")) return "";
            return read(c.getInputStream(), limit);
        } finally {
            c.disconnect();
        }
    }

    /** Reads any text response (JSON APIs). */
    static String text(HttpURLConnection c, int limit) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Server answered HTTP " + code);
            return read(c.getInputStream(), limit);
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream is = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1 && out.size() < limit) {
                out.write(buf, 0, Math.min(n, limit - out.size()));
            }
        }
        return out.toString("UTF-8");
    }

    static boolean isPage(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase(Locale.ROOT);
        return t.contains("text/html") || t.contains("application/xhtml");
    }

    /** Parses Content-Disposition. Prefers RFC 5987 {@code filename*=}. */
    static String filename(String cd) {
        if (cd == null) return null;
        Matcher m = FN_STAR.matcher(cd);
        if (m.find()) {
            String charset = m.group(1) == null ? "UTF-8" : m.group(1);
            String v = percentDecode(m.group(2).trim(), charset);
            if (!v.isEmpty()) return v;
        }
        m = FN.matcher(cd);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            v = v.trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    static String nameFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            return percentDecode(last, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Percent-decoding that keeps '+' as a literal plus (unlike URLDecoder). */
    static String percentDecode(String s, String charset) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), charset);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    static long number(String s) {
        if (s == null) return -1;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A name safe for any file system, keeping the extension when shortened. */
    static String safeName(String name) {
        if (name == null) return "download";
        String n = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").replaceAll("^[.\\s]+", "").trim();
        if (n.length() > 120) {
            String ext = Fmt.ext(n);
            int keep = 120 - (ext.isEmpty() ? 0 : ext.length() + 1);
            n = n.substring(0, keep).trim() + (ext.isEmpty() ? "" : "." + ext);
        }
        return n.isEmpty() ? "download" : n;
    }
}
