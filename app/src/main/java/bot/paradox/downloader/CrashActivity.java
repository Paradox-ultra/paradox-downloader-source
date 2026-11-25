package bot.paradox.downloader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shows the saved crash report with Copy and Continue.
 *
 * <p>Deliberately plain: a framework activity, built in code, with a framework theme. If the
 * crash was in the app's own theme or layouts, this screen still opens.
 */
public final class CrashActivity extends Activity {
    private static final String EXTRA_RESUME = "bot.paradox.downloader.RESUME";

    /** Opens the report; {@code original} is replayed to the main screen on Continue. */
    static void show(Activity from, Intent original) {
        Intent i = new Intent(from, CrashActivity.class).putExtra(EXTRA_RESUME, new Intent(original));
        // Carry read access to shared files across, so a share that arrives now isn't lost.
        if (original.getClipData() != null) {
            i.setClipData(original.getClipData());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String report = CrashReport.read(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(20), dp(20), dp(20), dp(12));

        TextView title = new TextView(this);
        title.setText("Downloader Ultra closed unexpectedly");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Copy this report and send it to the developer. It stays on your phone otherwise.");
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        TextView trace = new TextView(this);
        trace.setText(report);
        trace.setTypeface(Typeface.MONOSPACE);
        trace.setTextSize(11);
        trace.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(trace);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Crash report", report));
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        Button go = new Button(this);
        go.setText("Continue");
        go.setOnClickListener((View v) -> proceed());

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(go, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);
        setContentView(root);
    }

    private void proceed() {
        CrashReport.clear(this);
        @SuppressWarnings("deprecation")
        Intent original = getIntent().getParcelableExtra(EXTRA_RESUME);
        Intent next = original != null ? new Intent(original) : new Intent();
        next.setClass(this, MainActivity.class);
        if (next.getClipData() != null) next.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(next);
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
