package bot.paradox.downloader;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.util.Locale;

/** Intents to open and share saved files. */
final class Opener {
    static final String APK = "application/vnd.android.package-archive";

    private Opener() {}

    static String mime(Context c, Uri uri, String name, String fallback) {
        String t = null;
        try {
            t = c.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (t == null || t.contains("octet-stream")) {
            String byExt = fromName(name);
            if (byExt != null) t = byExt;
        }
        if (t == null) t = fallback;
        return t == null ? "*/*" : t;
    }

    static String fromName(String name) {
        String ext = Fmt.ext(name);
        if (ext.isEmpty()) return null;
        switch (ext) {
            case "apk": return APK;
            case "mkv": return "video/x-matroska";
            case "7z": return "application/x-7z-compressed";
            case "rar": return "application/vnd.rar";
            case "flac": return "audio/flac";
            case "opus": return "audio/ogg";
            default: return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        }
    }

    static Intent view(Context c, Uri uri, String name, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime(c, uri, name, mime));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static Intent share(Context c, Uri uri, String name, String mime) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime(c, uri, name, mime));
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(ClipData.newRawUri(name, uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(i, name);
    }
}
