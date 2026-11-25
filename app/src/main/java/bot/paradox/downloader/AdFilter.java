package bot.paradox.downloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * An ad and tracker filter engine that reads the lists uBlock Origin and Adblock Plus use
 * (EasyList, EasyPrivacy, uBlock filters, AdGuard Mobile, cookie-notice lists) and plain hosts files.
 *
 * <p>Network rules are matched against a request URL with a token index (as uBlock does), so a request
 * is tested against a handful of rules, not tens of thousands. Element-hiding rules are split into
 * three: simple class/id names (applied lazily by a page script), other generic selectors (one static
 * stylesheet) and per-site selectors (looked up by the page's host). Rules that need a scriptlet,
 * a redirect resource or a response rewrite are skipped. This class has no Android dependencies.
 */
final class AdFilter {
    // Request kinds, as a bit mask.
    static final int SCRIPT = 1, IMAGE = 2, CSS = 4, XHR = 8, SUBDOC = 16, FONT = 32, MEDIA = 64, OTHER = 128,
            DOC = 256, POPUP = 512;
    private static final int SUBRESOURCE = SCRIPT | IMAGE | CSS | XHR | SUBDOC | FONT | MEDIA | OTHER;

    private static final Set<String> USELESS_TOKENS = new HashSet<>(java.util.Arrays.asList(
            "http", "https", "www", "com", "html", "json", "net", "org", "php", "htm"));

    /** One network rule. */
    private static final class Rule {
        String[] parts;
        boolean start, host, end;
        int types;      // 0 = every sub-resource kind
        byte party;     // 0 any, 1 third-party only, 2 first-party only
        String[] inc, exc;
    }

    private static final class Index {
        final HashMap<String, ArrayList<Rule>> byToken = new HashMap<>();
        final ArrayList<Rule> loose = new ArrayList<>();

        void add(Rule r, String token) {
            if (token == null) {
                loose.add(r);
                return;
            }
            ArrayList<Rule> l = byToken.get(token);
            if (l == null) byToken.put(token, l = new ArrayList<>(2));
            l.add(r);
        }
    }

    // Domain-level rules: a host and all its subdomains.
    private final HashSet<String> blockHosts = new HashSet<>(131072);
    private final HashSet<String> allowHosts = new HashSet<>();
    private final Index block = new Index(), allow = new Index(), nav = new Index();

    // Element hiding.
    private final HashSet<String> genericClasses = new HashSet<>(), genericIds = new HashSet<>();
    private final java.util.LinkedHashSet<String> genericCss = new java.util.LinkedHashSet<>();
    private final HashMap<String, ArrayList<String>> siteCss = new HashMap<>();

    int networkRules, cosmeticRules;

    // ---- loading ---------------------------------------------------------------------------

    /** Reads an Adblock-syntax list or a hosts file. */
    void load(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.length() < 2) continue;
            char c = line.charAt(0);
            if (c == '!' || c == '[') continue; // list comment / header
            if (c == '#' && !line.startsWith("##")) continue; // hosts-file comment (or an unsupported #@# / #?# rule)
            try {
                parse(line);
            } catch (RuntimeException ignored) {
                // A malformed rule must never take the list down with it.
            }
        }
    }

    private void parse(String line) {
        char c = line.charAt(0);
        // hosts file: "0.0.0.0 example.com" / "127.0.0.1 example.com"
        if (c == '0' || c == '1') {
            if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                String h = line.substring(line.indexOf(' ') + 1).trim();
                int sp = h.indexOf(' ');
                if (sp > 0) h = h.substring(0, sp);
                if (h.indexOf('.') > 0 && !h.equals("0.0.0.0") && !h.startsWith("localhost")) {
                    blockHosts.add(h.toLowerCase(Locale.ROOT));
                    networkRules++;
                }
                return;
            }
        }
        int hash = line.indexOf('#');
        if (hash >= 0 && (line.startsWith("##", hash) || line.startsWith("#@#", hash)
                || line.startsWith("#?#", hash) || line.startsWith("#$#", hash) || line.startsWith("#%#", hash))) {
            if (line.startsWith("##", hash)) cosmetic(line.substring(0, hash), line.substring(hash + 2));
            return;
        }
        network(line);
    }

    // ---- element hiding --------------------------------------------------------------------

    private static final String[] BAD_SELECTOR = {":-abp-", ":has-text", ":contains", ":xpath", ":matches-", ":upward",
            ":min-text", ":nth-ancestor", ":remove", ":style(", ":watch-attr", ":others", ":not(:", "+js(", "{", "}",
            "\\", ":is(", ":where("};

    private void cosmetic(String domains, String selector) {
        if (selector.isEmpty() || selector.length() > 160 || selector.charAt(0) == '^') return;
        for (String bad : BAD_SELECTOR) if (selector.contains(bad)) return;
        if (domains.isEmpty()) {
            if (isSimple(selector)) {
                if (selector.charAt(0) == '.') genericClasses.add(selector.substring(1));
                else genericIds.add(selector.substring(1));
            } else if (genericCss.size() < 6000) {
                genericCss.add(selector);
            }
            cosmeticRules++;
            return;
        }
        boolean any = false;
        for (String d : domains.split(",")) {
            if (d.isEmpty() || d.charAt(0) == '~' || d.indexOf('*') >= 0) continue;
            ArrayList<String> l = siteCss.get(d);
            if (l == null) siteCss.put(d, l = new ArrayList<>(2));
            l.add(selector);
            any = true;
        }
        if (any) cosmeticRules++;
    }

    private static boolean isSimple(String s) {
        char c = s.charAt(0);
        if ((c != '.' && c != '#') || s.length() < 2) return false;
        for (int i = 1; i < s.length(); i++) {
            char x = s.charAt(i);
            if (!(x >= 'a' && x <= 'z' || x >= 'A' && x <= 'Z' || x >= '0' && x <= '9' || x == '-' || x == '_')) return false;
        }
        return true;
    }

    /** Class names for the page script to look for. */
    Set<String> genericClassNames() {
        return genericClasses;
    }

    Set<String> genericIdNames() {
        return genericIds;
    }

    /** Selectors that apply to every page and can't be looked up by name. */
    String genericStylesheet() {
        StringBuilder sb = new StringBuilder();
        for (String s : genericCss) sb.append(s).append("{display:none!important}");
        return sb.toString();
    }

    /** Selectors for one page, from its host and every parent domain. */
    String siteStylesheet(String host) {
        if (host == null) return "";
        StringBuilder sb = new StringBuilder();
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            ArrayList<String> l = siteCss.get(h);
            if (l != null) for (String s : l) sb.append(s).append("{display:none!important}");
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        return sb.toString();
    }

    // ---- network rules ---------------------------------------------------------------------

    private void network(String line) {
        boolean exception = line.startsWith("@@");
        if (exception) line = line.substring(2);
        String pattern = line, opts = null;
        int d = line.lastIndexOf('$');
        if (d >= 0 && !(line.startsWith("/") && line.endsWith("/"))) {
            pattern = line.substring(0, d);
            opts = line.substring(d + 1);
        }
        if (pattern.isEmpty() || pattern.startsWith("/") && pattern.endsWith("/") && pattern.length() > 2) return; // regex rules are skipped

        Rule r = new Rule();
        int pos = 0, positive = 0, negative = 0;
        if (opts != null) {
            for (String o : opts.split(",")) {
                boolean neg = o.startsWith("~");
                if (neg) o = o.substring(1);
                int eq = o.indexOf('=');
                String name = eq < 0 ? o : o.substring(0, eq);
                switch (name) {
                    case "third-party": case "3p": r.party = (byte) (neg ? 2 : 1); break;
                    case "first-party": case "1p": r.party = (byte) (neg ? 1 : 2); break;
                    case "domain": {
                        ArrayList<String> in = new ArrayList<>(), ex = new ArrayList<>();
                        for (String x : o.substring(eq + 1).split("\\|")) {
                            if (x.startsWith("~")) ex.add(x.substring(1).toLowerCase(Locale.ROOT));
                            else if (!x.isEmpty()) in.add(x.toLowerCase(Locale.ROOT));
                        }
                        if (!in.isEmpty()) r.inc = in.toArray(new String[0]);
                        if (!ex.isEmpty()) r.exc = ex.toArray(new String[0]);
                        break;
                    }
                    case "script": if (neg) negative |= SCRIPT; else positive |= SCRIPT; break;
                    case "image": if (neg) negative |= IMAGE; else positive |= IMAGE; break;
                    case "stylesheet": case "css": if (neg) negative |= CSS; else positive |= CSS; break;
                    case "xmlhttprequest": case "xhr": if (neg) negative |= XHR; else positive |= XHR; break;
                    case "subdocument": case "frame": if (neg) negative |= SUBDOC; else positive |= SUBDOC; break;
                    case "font": if (neg) negative |= FONT; else positive |= FONT; break;
                    case "media": case "object": case "ping": case "websocket": case "other":
                        if (neg) negative |= OTHER | MEDIA; else positive |= name.equals("media") ? MEDIA : OTHER; break;
                    case "document": case "doc": if (!neg) positive |= DOC; break;
                    case "popup": if (!neg) positive |= POPUP; break;
                    case "all": positive |= SUBRESOURCE | DOC | POPUP; break;
                    case "important": case "match-case": case "empty": break;
                    default: return; // redirect, removeparam, csp, replace, badfilter, elemhide, ... are not supported
                }
            }
        }
        if (positive != 0) r.types = positive;
        else if (negative != 0) r.types = SUBRESOURCE & ~negative;

        boolean navOnly = r.types != 0 && (r.types & SUBRESOURCE) == 0;
        if (navOnly && exception) return;

        // Anchors.
        if (pattern.startsWith("||")) {
            r.host = true;
            pos = 2;
        } else if (pattern.startsWith("|")) {
            r.start = true;
            pos = 1;
        }
        int endPos = pattern.length();
        if (endPos > pos && pattern.charAt(endPos - 1) == '|') {
            r.end = true;
            endPos--;
        }
        String body = pattern.substring(pos, endPos).toLowerCase(Locale.ROOT);
        if (body.isEmpty()) return;

        // Plain "||domain^" blocks a host and its subdomains: one hash lookup.
        if (r.host && opts == null) {
            String h = body.endsWith("^") ? body.substring(0, body.length() - 1) : body;
            if (isHost(h)) {
                (exception ? allowHosts : blockHosts).add(h);
                networkRules++;
                return;
            }
        }

        // Leading/trailing wildcards add nothing.
        while (body.startsWith("*")) {
            body = body.substring(1);
            r.start = false;
            r.host = false;
        }
        while (body.endsWith("*")) {
            body = body.substring(0, body.length() - 1);
            r.end = false;
        }
        if (body.isEmpty()) return;
        r.parts = body.split("\\*", -1);
        String token = token(body, r.start || r.host, r.end);
        Index target = navOnly ? nav : exception ? allow : block;
        target.add(r, token);
        networkRules++;
    }

    private static boolean isHost(String h) {
        if (h.length() < 4 || h.indexOf('.') < 1) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-')) return false;
        }
        return true;
    }

    /** The longest run of letters/digits that a URL token must equal, or null. */
    private static String token(String body, boolean anchoredStart, boolean anchoredEnd) {
        String best = null;
        int n = body.length();
        int i = 0;
        while (i < n) {
            if (!alnum(body.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && alnum(body.charAt(j))) j++;
            boolean leftOk = i == 0 ? anchoredStart : body.charAt(i - 1) != '*';
            boolean rightOk = j == n ? anchoredEnd : body.charAt(j) != '*';
            if (leftOk && rightOk && j - i >= 3 && !USELESS_TOKENS.contains(body.substring(i, j))
                    && (best == null || j - i > best.length())) {
                best = body.substring(i, j);
            }
            i = j;
        }
        return best;
    }

    private static boolean alnum(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }

    // ---- matching --------------------------------------------------------------------------

    /**
     * Should this request be blocked?
     *
     * @param url      the request URL
     * @param pageHost host of the top-level page, or null
     * @param types    kinds the request could be (bit mask)
     */
    boolean blocks(String url, String pageHost, int types) {
        String u = url.toLowerCase(Locale.ROOT);
        int hs = u.indexOf("://");
        if (hs < 0) return false;
        hs += 3;
        int he = u.length();
        for (int i = hs; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':' && i > hs) {
                he = i;
                break;
            }
        }
        int at = u.lastIndexOf('@', he);
        if (at >= hs && at < he) hs = at + 1;
        String host = u.substring(hs, he);
        String page = pageHost == null ? null : pageHost.toLowerCase(Locale.ROOT);
        boolean third = page != null && !sameSite(host, page);

        boolean hit = hostListed(blockHosts, host);
        if (hit && hostListed(allowHosts, host)) return false;
        if (!hit) hit = scan(block, u, hs, he, page, third, types);
        if (!hit) return false;
        return !(hostListed(allowHosts, host) || scan(allow, u, hs, he, page, third, types));
    }

    /** Domain-only check, for page navigations and pop-ups. */
    boolean blocksNavigation(String url, String pageHost, boolean popup) {
        String u = url.toLowerCase(Locale.ROOT);
        int hs = u.indexOf("://");
        if (hs < 0) return false;
        hs += 3;
        int he = u.length();
        for (int i = hs; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                he = i;
                break;
            }
        }
        String host = u.substring(hs, he);
        if (hostListed(allowHosts, host)) return false;
        if (hostListed(blockHosts, host)) return true;
        String page = pageHost == null ? null : pageHost.toLowerCase(Locale.ROOT);
        return scan(nav, u, hs, he, page, page != null && !sameSite(host, page), popup ? POPUP : DOC);
    }

    private static boolean hostListed(Set<String> set, String host) {
        String h = host;
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
            if (h.indexOf('.') < 0) return false; // never match a bare TLD
        }
    }

    private boolean scan(Index idx, String u, int hs, int he, String page, boolean third, int types) {
        int n = u.length(), i = 0;
        while (i < n) {
            if (!alnum(u.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && alnum(u.charAt(j))) j++;
            if (j - i >= 3) {
                ArrayList<Rule> l = idx.byToken.get(u.substring(i, j));
                if (l != null) for (int k = 0; k < l.size(); k++) if (test(l.get(k), u, hs, he, page, third, types)) return true;
            }
            i = j;
        }
        ArrayList<Rule> l = idx.loose;
        for (int k = 0; k < l.size(); k++) if (test(l.get(k), u, hs, he, page, third, types)) return true;
        return false;
    }

    private static boolean test(Rule r, String u, int hs, int he, String page, boolean third, int types) {
        if (r.types != 0 ? (r.types & types) == 0 : (types & SUBRESOURCE) == 0) return false;
        if (r.party == 1 && !third) return false;
        if (r.party == 2 && third) return false;
        if (r.inc != null || r.exc != null) {
            if (page == null) return r.inc == null;
            if (r.exc != null) for (String d : r.exc) if (page.equals(d) || page.endsWith("." + d)) return false;
            if (r.inc != null) {
                boolean ok = false;
                for (String d : r.inc) if (page.equals(d) || page.endsWith("." + d)) ok = true;
                if (!ok) return false;
            }
        }
        return matches(r, u, hs, he);
    }

    private static boolean matches(Rule r, String u, int hs, int he) {
        String[] p = r.parts;
        int n = u.length();
        if (r.start) return tail(r, u, matchAt(p[0], u, 0), 1);
        if (r.host) {
            for (int i = hs; i < he; i++) {
                if (i != hs && u.charAt(i - 1) != '.') continue;
                if (tail(r, u, matchAt(p[0], u, i), 1)) return true;
            }
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (tail(r, u, matchAt(p[0], u, i), 1)) return true;
        }
        return false;
    }

    /** After the first part matched ending at {@code at}, find the rest in order. */
    private static boolean tail(Rule r, String u, int at, int from) {
        if (at < 0) return false;
        String[] p = r.parts;
        int n = u.length();
        for (int k = from; k < p.length; k++) {
            boolean last = k == p.length - 1;
            int found = -1;
            for (int i = at; i <= n; i++) {
                int e = matchAt(p[k], u, i);
                if (e >= 0 && (!last || !r.end || e == n)) {
                    found = e;
                    break;
                }
            }
            if (found < 0) return false;
            at = found;
        }
        return !r.end || at == n;
    }

    /** Matches one wildcard-free part at {@code pos}; '^' is a separator. Returns the end index or -1. */
    private static int matchAt(String part, String u, int pos) {
        int n = u.length();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '^') {
                if (pos == n) continue;
                char x = u.charAt(pos);
                if (alnum(x) || x == '_' || x == '-' || x == '.' || x == '%') return -1;
                pos++;
            } else {
                if (pos >= n || u.charAt(pos) != c) return -1;
                pos++;
            }
        }
        return pos;
    }

    // ---- sites -----------------------------------------------------------------------------

    /** Roughly "same registrable domain". */
    static boolean sameSite(String a, String b) {
        return registrable(a).equals(registrable(b));
    }

    static String registrable(String host) {
        String[] l = host.split("\\.");
        int n = l.length;
        if (n <= 2) return host;
        String sld = l[n - 2], tld = l[n - 1];
        boolean twoLevel = tld.length() == 2 && (sld.equals("co") || sld.equals("com") || sld.equals("org")
                || sld.equals("net") || sld.equals("gov") || sld.equals("edu") || sld.equals("ac") || sld.equals("or") || sld.equals("ne"));
        int keep = twoLevel ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - keep; i < n; i++) sb.append(i == n - keep ? "" : ".").append(l[i]);
        return sb.toString();
    }

    /** Request kinds a WebView request could be, from what the platform tells us. */
    static int guessTypes(String url, String accept, boolean mainFrame) {
        if (mainFrame) return DOC;
        String a = accept == null ? "" : accept.toLowerCase(Locale.ROOT);
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        path = path.toLowerCase(Locale.ROOT);
        if (a.contains("text/html")) return SUBDOC;
        if (a.startsWith("image/") || a.contains("image/avif") || a.contains("image/webp")) return IMAGE;
        if (a.contains("text/css")) return CSS;
        if (path.endsWith(".js") || path.endsWith(".mjs")) return SCRIPT;
        if (path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf")) return FONT;
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mp3")) return MEDIA;
        return SCRIPT | XHR | OTHER;
    }

    int looseRules() {
        return block.loose.size();
    }
}
