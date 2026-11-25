package bot.paradox.downloader;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Download cards. Uses DiffUtil with stable IDs and a "progress" payload, so a
 * speed update only touches two text views and a bar instead of rebuilding
 * every card (v4.2 bug 1).
 */
final class JobAdapter extends ListAdapter<Job, JobAdapter.VH> {
    interface Actions {
        void primary(Job j);

        void card(Job j);

        void more(Job j, View anchor);
    }

    private static final Object PROGRESS = new Object();
    private final Actions actions;

    JobAdapter(Actions actions) {
        super(DIFF);
        this.actions = actions;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).id.hashCode();
    }

    int positionOf(String id) {
        List<Job> items = getCurrentList();
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
        return -1;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_job, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.bind(getItem(position));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.get(payloads.size() - 1) == PROGRESS) h.bindProgress(getItem(position));
        else h.bind(getItem(position));
    }

    final class VH extends RecyclerView.ViewHolder {
        final TextView ext, name, status, badge, pct;
        final ImageView thumb;
        final View progressRow;
        final LinearProgressIndicator bar;
        final MaterialButton primary, more;
        Job job;

        VH(View v) {
            super(v);
            ext = v.findViewById(R.id.ext);
            name = v.findViewById(R.id.name);
            status = v.findViewById(R.id.status);
            bar = v.findViewById(R.id.bar);
            thumb = v.findViewById(R.id.thumb);
            badge = v.findViewById(R.id.badge);
            pct = v.findViewById(R.id.pct);
            progressRow = v.findViewById(R.id.progress_row);
            primary = v.findViewById(R.id.primary);
            more = v.findViewById(R.id.more);
            v.setOnClickListener(x -> {
                if (job != null) actions.card(job);
            });
            v.setOnLongClickListener(x -> {
                if (job != null) actions.more(job, more);
                return job != null;
            });
            primary.setOnClickListener(x -> {
                if (job != null) actions.primary(job);
            });
            more.setOnClickListener(x -> {
                if (job != null) actions.more(job, more);
            });
        }

        void bind(Job j) {
            job = j;
            name.setText(j.name == null || j.name.isEmpty() ? hostOf(j) : j.name);
            String e = Fmt.ext(j.name);
            ext.setText(e.isEmpty() ? "FILE" : e.toUpperCase(Locale.ROOT));
            tint(j, e);
            // Video thumbnail or cover art when there is one; the file-type tile otherwise.
            if (j.thumb != null && !j.thumb.equals(thumb.getTag(R.id.thumb_url))) {
                thumb.setVisibility(View.GONE);
                Thumbs.load(thumb, j.thumb, 200, ok -> thumb.setVisibility(ok ? View.VISIBLE : View.GONE));
            } else if (j.thumb == null) {
                thumb.setTag(R.id.thumb_url, null);
                thumb.setVisibility(View.GONE);
            }
            badge.setText(j.badge);
            badge.setVisibility(j.badge == null || j.badge.isEmpty() || Job.FAILED.equals(j.state) ? View.GONE : View.VISIBLE);

            int icon;
            String what;
            switch (j.state) {
                case Job.PAUSED: icon = R.drawable.ic_play; what = "Resume"; break;
                case Job.FAILED: icon = R.drawable.ic_refresh; what = "Retry"; break;
                case Job.DONE: icon = R.drawable.ic_open; what = "Open"; break;
                default: icon = R.drawable.ic_pause; what = "Pause"; break;
            }
            primary.setIconResource(icon);
            primary.setContentDescription(what + " " + name.getText());
            more.setContentDescription("More options for " + name.getText());
            bindProgress(j);
        }

        void bindProgress(Job j) {
            job = j;
            status.setText(statusText(j));
            boolean failed = Job.FAILED.equals(j.state);
            status.setTextColor(MaterialColors.getColor(status,
                    failed ? androidx.appcompat.R.attr.colorError : com.google.android.material.R.attr.colorOnSurfaceVariant));

            boolean showBar = !Job.DONE.equals(j.state) && !failed;
            if (!showBar) {
                progressRow.setVisibility(View.GONE);
                return;
            }
            progressRow.setVisibility(View.VISIBLE);
            boolean indeterminate = Job.QUEUED.equals(j.state) || Job.CONNECTING.equals(j.state)
                    || (Job.DOWNLOADING.equals(j.state) && j.total <= 0);
            if (bar.isIndeterminate() != indeterminate) {
                // Switching modes on a visible bar needs it hidden for a moment.
                bar.setVisibility(View.INVISIBLE);
                bar.setIndeterminate(indeterminate);
                bar.setVisibility(View.VISIBLE);
            }
            if (!indeterminate) {
                int permille = j.total > 0 ? (int) Math.min(1000, j.done * 1000 / j.total) : 0;
                bar.setProgressCompat(permille, true);
                pct.setText((permille / 10) + "%");
            } else {
                pct.setText("");
            }
            bar.setAlpha(Job.PAUSED.equals(j.state) ? 0.4f : 1f);
            pct.setAlpha(Job.PAUSED.equals(j.state) ? 0.5f : 1f);
        }

        private void tint(Job j, String e) {
            int bg, fg;
            if (Job.FAILED.equals(j.state)) {
                bg = com.google.android.material.R.attr.colorErrorContainer;
                fg = com.google.android.material.R.attr.colorOnErrorContainer;
            } else if (e.matches("mp4|mkv|webm|mov|avi|m4v|3gp|ts")) {
                bg = com.google.android.material.R.attr.colorPrimaryContainer;
                fg = com.google.android.material.R.attr.colorOnPrimaryContainer;
            } else if (e.matches("mp3|m4a|aac|flac|wav|ogg|opus")) {
                bg = com.google.android.material.R.attr.colorSecondaryContainer;
                fg = com.google.android.material.R.attr.colorOnSecondaryContainer;
            } else if (e.matches("apk|zip|rar|7z|tar|gz|iso|exe|xapk|apks")) {
                bg = com.google.android.material.R.attr.colorTertiaryContainer;
                fg = com.google.android.material.R.attr.colorOnTertiaryContainer;
            } else {
                bg = com.google.android.material.R.attr.colorSurfaceContainerHighest;
                fg = com.google.android.material.R.attr.colorOnSurfaceVariant;
            }
            ext.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(ext, bg)));
            ext.setTextColor(MaterialColors.getColor(ext, fg));
        }
    }

    static String statusText(Job j) {
        String size = j.total > 0 ? Fmt.size(j.total) : null;
        switch (j.state) {
            case Job.QUEUED:
                return "Waiting to start" + (size != null ? " · " + size : "");
            case Job.CONNECTING:
                return "Connecting…";
            case Job.DOWNLOADING: {
                // Video/music jobs: "Joining video and audio…", "Finding on YouTube Music…"
                if (j.stage != null && !j.stage.isEmpty() && (j.speed <= 0 || j.total <= 0)) {
                    return j.stage + (size != null && j.done > 0 ? " · " + Fmt.size(j.done) + " of " + size : "");
                }
                StringBuilder s = new StringBuilder(Fmt.size(j.done));
                if (size != null) s.append(" of ").append(size);
                s.append(" · ").append(Fmt.speed(j.speed));
                String eta = j.total > 0 ? Fmt.eta(j.total - j.done, j.speed) : "";
                if (!eta.isEmpty()) s.append(" · ").append(eta);
                return s.toString();
            }
            case Job.PAUSED:
                if (j.done > 0 && size != null) {
                    return "Paused · " + Fmt.size(j.done) + " of " + size + " · " + (j.done * 100 / j.total) + "%";
                }
                return j.ranges || j.done > 0 ? "Paused" : "Paused · this server restarts from the beginning";
            case Job.FAILED:
                return "Failed · " + (j.error == null ? "tap for details" : j.error);
            case Job.DONE:
                return (size != null ? size + " · " : "") + "Saved to Downloads";
            default:
                return "";
        }
    }

    private static String hostOf(Job j) {
        try {
            return new java.net.URL(j.url).getHost();
        } catch (Exception e) {
            return "Download";
        }
    }

    private static final DiffUtil.ItemCallback<Job> DIFF = new DiffUtil.ItemCallback<Job>() {
        @Override
        public boolean areItemsTheSame(@NonNull Job a, @NonNull Job b) {
            return a.id.equals(b.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Job a, @NonNull Job b) {
            return sameLook(a, b) && a.done == b.done && a.total == b.total && a.speed == b.speed
                    && Objects.equals(a.stage, b.stage);
        }

        @Override
        public Object getChangePayload(@NonNull Job a, @NonNull Job b) {
            return sameLook(a, b) ? PROGRESS : null;
        }

        private boolean sameLook(Job a, Job b) {
            return Objects.equals(a.state, b.state) && Objects.equals(a.name, b.name)
                    && Objects.equals(a.error, b.error) && Objects.equals(a.uri, b.uri)
                    && Objects.equals(a.thumb, b.thumb) && Objects.equals(a.badge, b.badge);
        }
    };
}
