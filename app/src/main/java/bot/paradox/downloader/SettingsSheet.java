package bot.paradox.downloader;

import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Settings, as a bottom sheet over the main screen. */
final class SettingsSheet {
    private SettingsSheet() {}

    static void show(MainActivity a) {
        BottomSheetDialog sheet = new BottomSheetDialog(a);
        View root = LayoutInflater.from(a).inflate(R.layout.sheet_settings, null);
        LinearLayout rows = root.findViewById(R.id.rows);
        sheet.setContentView(root);
        fill(a, sheet, rows);
        sheet.show();
    }

    private static void fill(MainActivity a, BottomSheetDialog sheet, LinearLayout rows) {
        rows.removeAllViews();
        int conns = Prefs.connections(a);
        row(a, rows, R.drawable.ic_bolt, "Connections per download",
                conns == 1 ? "1 · simplest, for picky servers" : conns + " · faster on most servers", v ->
                        pickNumber(a, "Connections per download", Prefs.CONNECTION_CHOICES, conns, n -> {
                            Prefs.connections(a, n);
                            fill(a, sheet, rows);
                        }));
        int par = Prefs.parallel(a);
        row(a, rows, R.drawable.ic_queue, "Downloads at the same time", String.valueOf(par), v ->
                pickNumber(a, "Downloads at the same time", Prefs.PARALLEL_CHOICES, par, n -> {
                    Prefs.parallel(a, n);
                    fill(a, sheet, rows);
                }));

        boolean notif = a.getSystemService(NotificationManager.class).areNotificationsEnabled();
        row(a, rows, R.drawable.ic_bell, "Notifications",
                notif ? "On · progress shows in your notification shade" : "Off · turn on to see progress outside the app",
                v -> a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())));

        PowerManager pm = a.getSystemService(PowerManager.class);
        boolean free = pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName());
        row(a, rows, R.drawable.ic_battery, "Run in background",
                free ? "Allowed · downloads keep going with the screen off" : "Restricted · big downloads may pause",
                v -> {
                    sheet.dismiss();
                    a.requestBatteryExemption();
                });

        row(a, rows, R.drawable.ic_folder, "Save location", "Download / Paradox Downloader", v -> {
            try {
                a.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
            } catch (Exception e) {
                a.toast("No Downloads app found");
            }
        });
        row(a, rows, R.drawable.ic_chat, "WhatsApp channel", "Updates, new apps and support", v -> ChannelCard.open(a));
        row(a, rows, R.drawable.ic_update, "Check for updates", "Version " + BuildConfig.VERSION_NAME, v -> {
            sheet.dismiss();
            UpdateChecker.checkNow(a);
        });
    }

    private interface OnNumber {
        void picked(int n);
    }

    private static void pickNumber(MainActivity a, String title, int[] choices, int current, OnNumber cb) {
        String[] labels = new String[choices.length];
        int sel = 0;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = String.valueOf(choices[i]);
            if (choices[i] == current) sel = i;
        }
        new MaterialAlertDialogBuilder(a)
                .setTitle(title)
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    cb.picked(choices[w]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void row(MainActivity a, LinearLayout rows, int icon, String title, String detail, View.OnClickListener click) {
        View v = LayoutInflater.from(a).inflate(R.layout.item_setting, rows, false);
        ((ImageView) v.findViewById(R.id.setting_icon)).setImageResource(icon);
        ((TextView) v.findViewById(R.id.setting_title)).setText(title);
        ((TextView) v.findViewById(R.id.setting_detail)).setText(detail);
        v.setOnClickListener(click);
        rows.addView(v);
    }
}
