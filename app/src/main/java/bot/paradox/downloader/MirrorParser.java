package bot.paradox.downloader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds download buttons, mirrors and redirects on a web page. */
final class MirrorParser {
    static final class Result {
        final List<Option> choices = new ArrayList<>();
        String next;
    }

    private static final Pattern SERVERS = Pattern.compile(
            "(?i)(fsl\\s*(?:v?2|ii)?|hubic|hubcloud|cloud\\s*pixel|pixel\\s*(?:server|drain)|zip\\s*(?:disk|drive)"
                    + "|google\\s*drive|one\\s*drive|10\\s*gbps|server|mirror|download|direct)");
    private static final Pattern FILE = Pattern.compile(
            "(?i)(?:\\.(?:zip|rar|7z|apk|pdf|mp4|mkv|mp3|flac|iso|epub|exe)(?:[?#]|$)|/api/file/|[?&](?:download|dl)=1|/download(?:[/?#]|$))");
    private static final Pattern HOSTS = Pattern.compile(
            "(?i).*?(?:pixeldrain|drive\\.google|drive\\.usercontent\\.google|1drv\\.ms|onedrive\\.live|mediafire|gofile|fsl|zipdisk|zipdrive).*");
    private static final Pattern JS = Pattern.compile(
            "(?is)(?:window\\.|document\\.|top\\.)?location(?:\\.href)?\\s*=\\s*(['\"])(.*?)\\1"
                    + "|(?:window\\.)?location\\.(?:assign|replace)\\s*\\(\\s*(['\"])(.*?)\\3\\s*\\)"
                    + "|window\\.open\\s*\\(\\s*(['\"])(.*?)\\5");
    private static final Pattern REFRESH = Pattern.compile(
            "(?i)(?:^|;)\\s*\\d*(?:\\.\\d+)?\\s*;?\\s*url\\s*=\\s*['\"]?([^'\";]+)");

    private MirrorParser() {}

    static Result parse(String base, String html) {
        Result r = new Result();
        Document doc = Jsoup.parse(html, base);
        Set<String> seen = new LinkedHashSet<>();

        for (Element e : doc.select("a[href],area[href],button,input[type=button],input[type=submit],"
                + "[data-url],[data-href],[data-link],video[src],source[src]")) {
            if (r.choices.size() >= 40) break;
            String target = null;
            for (String attr : new String[]{"href", "data-url", "data-href", "data-link", "src"}) {
                if (e.hasAttr(attr)) {
                    target = e.attr(attr);
                    break;
                }
            }
            if (target != null && target.regionMatches(true, 0, "javascript:", 0, 11)) {
                target = jsTarget(target.substring(11));
            }
            if (target == null || target.isEmpty()) target = jsTarget(e.attr("onclick"));
            if ((target == null || target.isEmpty()) && e.parent() != null && "a".equals(e.parent().normalName())) {
                target = e.parent().attr("href");
            }
            String text = e.text() + " " + e.attr("value") + " " + e.attr("title") + " " + e.attr("aria-label");
            add(r, seen, base, target, text);
        }
        if (!r.choices.isEmpty()) return r;

        Element meta = doc.selectFirst("meta[http-equiv=refresh][content]");
        if (meta != null) {
            Matcher m = REFRESH.matcher(meta.attr("content"));
            if (m.find()) r.next = absolute(base, m.group(1).trim());
        }
        if (r.next == null) {
            for (Element s : doc.select("script:not([src])")) {
                String t = jsTarget(s.data());
                if (t != null && (r.next = absolute(base, t)) != null) break;
            }
        }
        if (r.next == null) {
            for (Element form : doc.select("form[action]")) {
                if (form.attr("method").equalsIgnoreCase("post")) continue;
                String hint = form.text() + " " + form.attr("id") + " " + form.attr("class");
                if (!SERVERS.matcher(hint).find()
                        && !form.attr("action").matches("(?i).*(?:generate|download|redirect|/dl/).*")) continue;
                String action = absolute(base, form.attr("action"));
                if (action == null) continue;
                StringBuilder url = new StringBuilder(action);
                for (Element in : form.select("input[name][value]")) {
                    String type = in.attr("type");
                    if (!type.isEmpty() && !type.equalsIgnoreCase("hidden")) continue;
                    url.append(url.indexOf("?") < 0 ? '?' : '&');
                    try {
                        url.append(URLEncoder.encode(in.attr("name"), "UTF-8")).append('=')
                                .append(URLEncoder.encode(in.attr("value"), "UTF-8"));
                    } catch (Exception ignored) {
                    }
                }
                r.next = url.toString();
                break;
            }
        }
        return r;
    }

    private static void add(Result r, Set<String> seen, String base, String target, String text) {
        String abs = absolute(base, target);
        if (abs == null || abs.equals(base)) return;
        if (!SERVERS.matcher(text).find() && !FILE.matcher(abs).find() && !HOSTS.matcher(abs).matches()) return;
        if (!seen.add(abs)) return;
        Option o = new Option(abs, label(text, abs));
        o.ready = false;
        r.choices.add(o);
    }

    static String absolute(String base, String href) {
        if (href == null) return null;
        href = href.trim();
        if (href.isEmpty() || href.startsWith("#") || href.matches("(?is)^(?:javascript|data|mailto|tel|intent):.*")) return null;
        try {
            String u = new URL(new URL(base), href.replace("&amp;", "&")).toExternalForm();
            return u.startsWith("https://") || u.startsWith("http://") ? u : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsTarget(String js) {
        if (js == null) return null;
        Matcher m = JS.matcher(js);
        while (m.find()) {
            String v = m.group(2) != null ? m.group(2) : m.group(4) != null ? m.group(4) : m.group(6);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String label(String text, String url) {
        String t = Jsoup.parse(text == null ? "" : text).text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if (t.length() > 75) t = t.substring(0, 75).trim() + "…";
        if (!t.isEmpty()) return t;
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "Download";
        }
    }
}
