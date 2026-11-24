package bot.paradox.downloader;

import java.util.Locale;

/** Human-readable sizes, speeds and times. */
final class Fmt {
    private Fmt() {}

    static String size(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    static String speed(long bytesPerSec) {
        return size(Math.max(0, bytesPerSec)) + "/s";
    }

    /** "45s left", "3m left", "1h 20m left"; empty when unknown. */
    static String eta(long remaining, long speed) {
        if (remaining <= 0 || speed <= 0) return "";
        long s = remaining / speed;
        if (s < 60) return Math.max(1, s) + "s left";
        if (s < 3600) return (s / 60) + "m left";
        if (s > 99 * 3600) return "";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m left";
    }

    /** Lower-case file extension without the dot, or "". */
    static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot >= name.length() - 1) return "";
        String e = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return e.length() <= 5 && e.matches("[a-z0-9]+") ? e : "";
    }
}
