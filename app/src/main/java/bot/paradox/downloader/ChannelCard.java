package bot.paradox.downloader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** The Paradox WhatsApp channel invite: at most once a day, never after "Don't show again" or joining. */
final class ChannelCard {
    static final String URL = "https://whatsapp.com/channel/0029Vb96Yl6IHphOLQa5H10O";
    private static final long GAP = 24 * 60 * 60 * 1000L;

    private ChannelCard() {}

    static void maybeShow(Activity a) {
        if (Prefs.flag(a, "channelHide")) return;
        long now = System.currentTimeMillis();
        long last = Prefs.sp(a).getLong("channelLast", 0);
        if (now - last < GAP) return;
        Prefs.sp(a).edit().putLong("channelLast", now).apply();
        new MaterialAlertDialogBuilder(a)
                .setIcon(R.drawable.ic_chat)
                .setTitle("Join Paradox on WhatsApp")
                .setMessage("Updates, new apps and support, first on the Paradox channel.")
                .setPositiveButton("Join channel", (d, w) -> {
                    Prefs.flag(a, "channelHide", true);
                    open(a);
                })
                .setNeutralButton("Don't show again", (d, w) -> Prefs.flag(a, "channelHide", true))
                .setNegativeButton("Later", null)
                .show();
    }

    static void open(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception e) {
            Toast.makeText(a, "No app can open WhatsApp links", Toast.LENGTH_SHORT).show();
        }
    }
}
