package bot.paradox.downloader;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.radiobutton.MaterialRadioButton;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Asked before every video, song or playlist download: Video or Audio, which
 * quality (the highest the creator uploaded is picked by default), and whether
 * to also save the thumbnail and captions.
 */
final class MediaSheet {
    interface Go {
        void start(List<JSONObject> specs, List<String> names);
    }

    private static final int[] PLAYLIST_HEIGHTS = {0, 2160, 1440, 1080, 720, 480, 360};

    private final Activity a;
    private final BottomSheetDialog dialog;
    private final LinearLayout box;
    private RadioGroup qualities;
    private MaterialCheckBox thumb, subs;
    private boolean audio;

    private MediaSheet(Activity a) {
        this.a = a;
        dialog = new BottomSheetDialog(a);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(16));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        dialog.setContentView(scroll);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);
    }

    /** One video or song. */
    static void show(Activity a, Media.Probe p, Go go) {
        MediaSheet s = new MediaSheet(a);
        String meta = join(p.uploader, Media.duration(p.duration));
        s.header(p.title, meta.isEmpty() ? (p.engine.equals("yt") ? "Found by yt-dlp" : null) : meta);
        boolean hasVideo = !p.video.isEmpty(), hasAudio = !p.audio.isEmpty();
        boolean startAudio = !hasVideo || (p.musicFirst && hasAudio) || (Prefs.flag(a, "mediaAudio") && hasAudio);
        s.modes(hasVideo, hasAudio, startAudio, () -> s.fill(s.audio ? p.audio : p.video));
        s.extras(p.subsLabel);
        s.fill(s.audio ? p.audio : p.video);
        s.button("Download", () -> {
            Media.Quality q = s.picked(s.audio ? p.audio : p.video);
            s.remember();
            JSONObject spec = Media.spec(p.engine, p.page, s.audio, q.height, q.fmt,
                    s.thumb.isChecked(), !s.audio && s.subs.isEnabled() && s.subs.isChecked(), null, null);
            List<JSONObject> specs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            specs.add(spec);
            names.add(p.title);
            go.start(specs, names);
        });
        s.dialog.show();
    }

    /** A YouTube / YouTube Music / SoundCloud playlist or a Spotify playlist, album or song. */
    static void show(Activity a, Media.Playlist pl, Go go) {
        MediaSheet s = new MediaSheet(a);
        int n = pl.tracks.size();
        String count = n + (n == 1 ? " song" : pl.music ? " songs" : " videos");
        s.header(pl.name, count + " · " + pl.from + (pl.spotify ? " · matched on YouTube Music" : ""));
        List<Media.Quality> video = new ArrayList<>();
        for (int h : PLAYLIST_HEIGHTS) {
            video.add(h == 0 ? new Media.Quality("Best available", "Highest each video was uploaded in", 0, "")
                    : new Media.Quality(h + "p", "Up to " + h + "p", h, ""));
        }
        List<Media.Quality> audio = new ArrayList<>();
        audio.add(new Media.Quality("Best available", "Highest quality audio", 0, ""));
        audio.add(new Media.Quality("Opus", "Smaller files, best quality", 0, "opus"));
        audio.add(new Media.Quality("M4A", "Plays everywhere", 0, "m4a"));
        s.modes(!pl.spotify, true, pl.music || Prefs.flag(a, "mediaAudio"), () -> s.fill(s.audio ? audio : video));
        s.extras(pl.spotify ? null : "if available");
        s.fill(s.audio ? audio : video);
        s.button("Download all " + n, () -> {
            Media.Quality q = s.picked(s.audio ? audio : video);
            s.remember();
            List<JSONObject> specs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Media.Track t : pl.tracks) {
                String engine = t.page != null ? "np" : "find";
                JSONObject spec = Media.spec(engine, t.page, s.audio, q.height, q.fmt, s.thumb.isChecked(),
                        !s.audio && s.subs.isEnabled() && s.subs.isChecked(), pl.music ? t.title : null, pl.music ? t.artist : null);
                if (t.query != null) {
                    try {
                        spec.put("q", t.query);
                    } catch (Exception ignored) {
                        // Strings never fail.
                    }
                }
                specs.add(spec);
                names.add(t.artist != null && !t.artist.isEmpty() && pl.music ? t.artist.split(",")[0].trim() + " - " + t.title : t.title);
            }
            go.start(specs, names);
        });
        s.dialog.show();
    }

    // ---- building blocks ----------------------------------------------------------------

    private void header(String title, String meta) {
        TextView t = new TextView(a);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        t.setText(title);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(0, dp(8), 0, 0);
        box.addView(t);
        if (meta != null && !meta.isEmpty()) {
            TextView m = new TextView(a);
            m.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            m.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            m.setText(meta);
            m.setPadding(0, dp(4), 0, 0);
            box.addView(m);
        }
    }

    private void modes(boolean video, boolean audioOk, boolean startAudio, Runnable changed) {
        audio = startAudio || !video;
        if (!video || !audioOk) return;
        MaterialButtonToggleGroup g = new MaterialButtonToggleGroup(a);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        MaterialButton v = toggle("Video", R.drawable.ic_video), m = toggle("Audio only", R.drawable.ic_audio);
        g.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        g.addView(m, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        g.check(audio ? m.getId() : v.getId());
        g.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            audio = id == m.getId();
            thumb.setText(audio ? "Add cover art" : "Save thumbnail");
            subs.setVisibility(audio ? View.GONE : View.VISIBLE);
            changed.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(g, lp);
    }

    private MaterialButton toggle(String text, int icon) {
        MaterialButton b = new MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setId(View.generateViewId());
        b.setText(text);
        b.setIconResource(icon);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        return b;
    }

    private void extras(String subsLabel) {
        label("Quality");
        qualities = new RadioGroup(a);
        box.addView(qualities);
        label("Also save");
        thumb = new MaterialCheckBox(a);
        thumb.setText(audio ? "Add cover art" : "Save thumbnail");
        thumb.setChecked(audio ? !Prefs.flag(a, "mediaNoCover") : Prefs.flag(a, "mediaThumb"));
        box.addView(thumb);
        subs = new MaterialCheckBox(a);
        subs.setText(subsLabel == null ? "Captions (none found)" : "Captions · " + subsLabel);
        subs.setEnabled(subsLabel != null);
        subs.setChecked(subsLabel != null && Prefs.flag(a, "mediaSubs"));
        subs.setVisibility(audio ? View.GONE : View.VISIBLE);
        box.addView(subs);
    }

    private void label(String text) {
        TextView l = new TextView(a);
        l.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        l.setTextColor(color(androidx.appcompat.R.attr.colorPrimary));
        l.setText(text);
        l.setPadding(0, dp(20), 0, dp(4));
        box.addView(l);
    }

    private void fill(List<Media.Quality> list) {
        qualities.removeAllViews();
        int sub = color(com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < list.size(); i++) {
            Media.Quality q = list.get(i);
            MaterialRadioButton r = new MaterialRadioButton(a);
            r.setId(View.generateViewId());
            SpannableStringBuilder t = new SpannableStringBuilder(q.label);
            t.setSpan(new StyleSpan(Typeface.BOLD), 0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (q.detail != null && !q.detail.isEmpty()) {
                int at = t.length();
                t.append("\n").append(q.detail);
                t.setSpan(new RelativeSizeSpan(0.85f), at, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                t.setSpan(new ForegroundColorSpan(sub), at, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            r.setText(t);
            r.setTag(i);
            r.setPadding(dp(8), dp(6), 0, dp(6));
            qualities.addView(r);
            if (i == 0) qualities.check(r.getId());
        }
        if (thumb != null) thumb.setChecked(audio ? !Prefs.flag(a, "mediaNoCover") : Prefs.flag(a, "mediaThumb"));
    }

    private Media.Quality picked(List<Media.Quality> list) {
        View v = qualities.findViewById(qualities.getCheckedRadioButtonId());
        int i = v == null || !(v.getTag() instanceof Integer) ? 0 : (Integer) v.getTag();
        return list.get(Math.min(i, list.size() - 1));
    }

    private void button(String text, Runnable go) {
        MaterialButton b = new MaterialButton(a);
        b.setText(text);
        b.setIconResource(R.drawable.ic_download);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(20);
        box.addView(b, lp);
        b.setOnClickListener(v -> {
            dialog.dismiss();
            go.run();
        });
    }

    private void remember() {
        Prefs.flag(a, "mediaAudio", audio);
        if (audio) Prefs.flag(a, "mediaNoCover", !thumb.isChecked());
        else {
            Prefs.flag(a, "mediaThumb", thumb.isChecked());
            if (subs.isEnabled()) Prefs.flag(a, "mediaSubs", subs.isChecked());
        }
    }

    private static String join(String a, String b) {
        boolean ha = a != null && !a.isEmpty(), hb = b != null && !b.isEmpty();
        return ha && hb ? a + " · " + b : ha ? a : hb ? b : "";
    }

    private int color(int attr) {
        return MaterialColors.getColor(box, attr);
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
