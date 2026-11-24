package bot.paradox.downloader;

/** One downloadable choice found by the resolver. */
final class Option {
    final String url;
    final String label;
    /** Suggested file name (e.g. "Title.mp4" for videos), or null to use the server's. */
    String name;
    long size = -1;
    String cookie;
    String referer;
    /** False for mirror/server pages that still need resolving when picked. */
    boolean ready = true;

    Option(String url, String label) {
        this.url = url;
        this.label = label;
    }

    String detail() {
        StringBuilder s = new StringBuilder();
        if (!ready) s.append("Opens mirror, then finds the file");
        else {
            try {
                s.append(new java.net.URL(url).getHost());
            } catch (Exception e) {
                s.append("Direct file");
            }
        }
        if (size > 0) s.append(" · ").append(Fmt.size(size));
        return s.toString();
    }
}
