package bot.paradox.downloader;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends AppCompatActivity implements JobStore.Listener, JobAdapter.Actions {
    static final String ACTION_SHOW_JOB = "bot.paradox.downloader.SHOW_JOB";
    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final int FILTER_ALL = 0, FILTER_ACTIVE = 1, FILTER_DONE = 2;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private JobStore store;
    private JobAdapter adapter;
    private TextInputLayout inputLayout;
    private TextInputEditText input;
    private RecyclerView list;
    private View empty;
    private TextView emptyTitle, emptyBody, stats;
    private View tips, paneHome, paneDownloads;
    private BottomNavigationView nav;
    private int tab; // 0 = Home, 1 = Downloads
    private ExtendedFloatingActionButton fab;
    private Chip chipAll, chipActive, chipDone;
    private int filter = FILTER_ALL;
    private AlertDialog busy;
    private Option pendingOption;
    private String pendingSource;
    private List<org.json.JSONObject> pendingSpecs;
    private List<String> pendingNames;
    private TextView busyText;
    private String showJobId;

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });
    private final ActivityResultLauncher<String> storagePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && pendingOption != null) enqueue(pendingOption, pendingSource);
                else if (granted && pendingSpecs != null) enqueueMedia(pendingSpecs, pendingNames, pendingSource);
                else if (!granted) toast("Storage access is needed to save files on this Android version");
                pendingOption = null;
                pendingSpecs = null;
                pendingNames = null;
            });

    @Override
    protected void onCreate(Bundle state) {
        EdgeToEdge.enable(this);
        super.onCreate(state);
        Prefs.applyAmoled(this);
        setContentView(R.layout.activity_main);
        store = JobStore.get(this);

        paneHome = findViewById(R.id.pane_home);
        paneDownloads = findViewById(R.id.pane_downloads);
        nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId() == R.id.nav_downloads ? 1 : 0);
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tab == 1) {
                    showTab(0);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        findViewById(R.id.settings).setOnClickListener(v -> SettingsSheet.show(this));
        stats = findViewById(R.id.stats);
        tips = findViewById(R.id.tips);
        fab = findViewById(R.id.fab);
        // One tap: take the link from the clipboard and start.
        fab.setOnClickListener(v -> {
            if (paste()) start();
        });

        inputLayout = findViewById(R.id.input_layout);
        input = findViewById(R.id.input);
        inputLayout.setEndIconOnClickListener(v -> {
            if (TextUtils.isEmpty(input.getText())) paste();
            else input.setText("");
        });
        input.addTextChangedListener(new SimpleWatcher(s -> {
            inputLayout.setError(null);
            boolean emptyText = s.length() == 0;
            inputLayout.setEndIconDrawable(emptyText ? R.drawable.ic_paste : R.drawable.ic_close);
            inputLayout.setEndIconContentDescription(emptyText ? "Paste" : "Clear");
        }));
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO) {
                start();
                return true;
            }
            return false;
        });
        MaterialButton go = findViewById(R.id.download);
        go.setOnClickListener(v -> start());

        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new JobAdapter(this);
        list.setAdapter(adapter);
        list.setItemAnimator(new SmoothAnimator());
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy > 6 && fab.isExtended()) fab.shrink();
                else if (dy < -6 && !fab.isExtended()) fab.extend();
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(list, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bars.bottom + dp(96));
            return insets;
        });

        empty = findViewById(R.id.empty);
        emptyTitle = findViewById(R.id.empty_title);
        emptyBody = findViewById(R.id.empty_body);

        ChipGroup chips = findViewById(R.id.filters);
        chipAll = findViewById(R.id.chip_all);
        chipActive = findViewById(R.id.chip_active);
        chipDone = findViewById(R.id.chip_done);
        chips.setOnCheckedStateChangeListener((g, ids) -> {
            int id = ids.isEmpty() ? R.id.chip_all : ids.get(0);
            filter = id == R.id.chip_active ? FILTER_ACTIVE : id == R.id.chip_done ? FILTER_DONE : FILTER_ALL;
            render();
        });

        showTab(state == null ? 0 : state.getInt("tab", 0));
        receive(getIntent());
        if (state == null) {
            UpdateChecker.maybeCheck(this, shown -> {
                if (!shown) ChannelCard.maybeShow(this);
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    /** Home (paste a link) or Downloads (the list). */
    private void showTab(int t) {
        tab = t;
        paneHome.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        paneDownloads.setVisibility(t == 1 ? View.VISIBLE : View.GONE);
        int want = t == 1 ? R.id.nav_downloads : R.id.nav_home;
        if (nav.getSelectedItemId() != want) nav.setSelectedItemId(want);
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.addListener(this);
        render();
        DownloadService.kick(this);
        // Keep yt-dlp's site support fresh (at most once a day, in the background).
        Context app = getApplicationContext();
        new Thread(() -> Media.updateYtdlp(app), "ytdlp-update").start();
    }

    @Override
    protected void onStop() {
        store.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receive(intent);
    }

    @Override
    public void onJobsChanged() {
        render();
    }

    // ---- list ------------------------------------------------------------------

    private void render() {
        List<Job> all = store.snapshot();
        List<Job> shown = new ArrayList<>();
        int active = 0, done = 0, running = 0;
        long speed = 0;
        for (Job j : all) {
            boolean isDone = Job.DONE.equals(j.state);
            if (isDone) done++;
            else active++;
            if (Job.DOWNLOADING.equals(j.state)) {
                running++;
                speed += j.speed;
            }
            if (filter == FILTER_ALL || (filter == FILTER_DONE) == isDone) shown.add(j);
        }
        chipAll.setText(all.isEmpty() ? "All" : "All · " + all.size());
        chipActive.setText(active == 0 ? "In progress" : "In progress · " + active);
        chipDone.setText(done == 0 ? "Finished" : "Finished · " + done);
        if (running > 0) {
            stats.setText(running + (running == 1 ? " downloading · " : " downloading · ") + Fmt.speed(speed));
        } else if (active > 0) {
            stats.setText(active + (active == 1 ? " waiting" : " waiting or paused"));
        } else if (done > 0) {
            stats.setText(done + (done == 1 ? " file saved" : " files saved") + " · all done");
        } else {
            stats.setText("Nothing yet");
        }

        adapter.submitList(shown, () -> {
            if (showJobId != null) {
                int pos = adapter.positionOf(showJobId);
                if (pos >= 0) list.smoothScrollToPosition(pos);
                Job j = store.find(showJobId);
                showJobId = null;
                if (j != null && Job.FAILED.equals(j.state)) showError(j);
            }
        });

        boolean nothing = shown.isEmpty();
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        BadgeDrawable badge = nav.getOrCreateBadge(R.id.nav_downloads);
        badge.setVisible(active > 0);
        if (active > 0) badge.setNumber(active);
        if (nothing) {
            if (filter == FILTER_ACTIVE && !all.isEmpty()) {
                emptyTitle.setText("Nothing in progress");
                emptyBody.setText("Downloads you start will show here with live speed and progress.");
            } else if (filter == FILTER_DONE && !all.isEmpty()) {
                emptyTitle.setText("No finished downloads yet");
                emptyBody.setText("Finished files are saved in Download/" + Saver.FOLDER + ".");
            } else {
                emptyTitle.setText("No downloads yet");
                emptyBody.setText("Paste a link on the Home tab to get started.");
            }
        }
    }

    // ---- incoming links ----------------------------------------------------------

    private void receive(Intent i) {
        if (i == null) return;
        if (ACTION_SHOW_JOB.equals(i.getAction())) {
            showJobId = i.getStringExtra("id");
            showTab(1);
            if (filter != FILTER_ALL) ((ChipGroup) findViewById(R.id.filters)).check(R.id.chip_all);
            render();
            return;
        }
        // Files shared from another app (Telegram, a gallery, a file manager): save them as they are.
        if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction()) || (Intent.ACTION_SEND.equals(i.getAction())
                && i.hasExtra(Intent.EXTRA_STREAM))) {
            java.util.List<android.net.Uri> files = ShareImport.files(i);
            if (!files.isEmpty()) {
                showTab(1);
                ShareImport.run(this, files);
                return;
            }
        }
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) text = i.getStringExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) text = i.getDataString();
        String url = firstLink(text);
        if (url != null) {
            showTab(0);
            input.setText(url);
            input.setSelection(url.length());
            start();
        }
    }

    private static String firstLink(String text) {
        if (text == null) return null;
        Matcher m = LINK.matcher(text);
        return m.find() ? m.group().replaceAll("[),.;!?\\]]+$", "") : null;
    }

    /** Puts the clipboard's link in the box; false (with a message) if there is none. */
    private boolean paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        String url = firstLink(text == null ? null : text.toString());
        if (url == null) {
            toast(text == null ? "Clipboard is empty" : "No link in the clipboard");
            return false;
        }
        input.setText(url);
        input.setSelection(url.length());
        return true;
    }

    // ---- resolving -------------------------------------------------------------------

    private void start() {
        String url = firstLink(input.getText() == null ? null : input.getText().toString());
        if (url == null) {
            inputLayout.setError(TextUtils.isEmpty(input.getText()) ? "Paste a link first" : "Links must start with http:// or https://");
            return;
        }
        resolve(url, null);
    }

    private void resolve(String source, Option mirror) {
        showBusy(mirror == null ? "Finding the file…" : "Opening " + mirror.label + "…");
        bg.execute(() -> {
            if (mirror == null && media(source)) return;
            try {
                List<Option> options = mirror == null ? Resolver.resolve(source) : Resolver.resolve(mirror);
                if (mirror == null && options.isEmpty() && ytdlp(source)) return;
                runOnUiThread(() -> {
                    hideBusy();
                    handle(source, options);
                });
            } catch (Exception e) {
                if (mirror == null && ytdlp(source)) return;
                failed(source, e);
            }
        });
    }

    /**
     * Videos, music and playlists get the quality sheet. Runs on the background
     * thread; returns true when it has taken care of the link.
     */
    private boolean media(String source) {
        Media.Kind kind = Media.classify(source);
        try {
            switch (kind) {
                case SPOTIFY: {
                    busy("Reading the Spotify list…");
                    Media.Playlist pl = Media.spotify(source);
                    runOnUiThread(() -> showPlaylist(source, pl));
                    return true;
                }
                case NP_PLAYLIST: {
                    busy("Reading the playlist…");
                    Media.Playlist pl = Media.newPipePlaylist(source);
                    runOnUiThread(() -> showPlaylist(source, pl));
                    return true;
                }
                case NP_STREAM: {
                    busy("Getting video qualities…");
                    try {
                        Media.Probe p = Media.newPipe(source);
                        runOnUiThread(() -> showProbe(source, p));
                        return true;
                    } catch (Exception e) {
                        if (ytdlp(source)) return true;
                        throw e;
                    }
                }
                case YTDLP_FIRST:
                    return ytdlp(source);
                default:
                    return false;
            }
        } catch (Exception e) {
            failed(source, e);
            return true;
        }
    }

    /** Asks yt-dlp about the link; true if it found a video or audio. */
    private boolean ytdlp(String source) {
        busy("Looking for videos with yt-dlp…");
        try {
            Media.Probe p = Media.ytdlp(this, source);
            runOnUiThread(() -> showProbe(source, p));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void failed(String source, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        runOnUiThread(() -> {
            hideBusy();
            if (!isFinishing()) resolveFailed(source, msg);
        });
    }

    private void showProbe(String source, Media.Probe p) {
        hideBusy();
        if (isFinishing()) return;
        MediaSheet.show(this, p, (specs, names) -> enqueueMedia(specs, names, source));
    }

    private void showPlaylist(String source, Media.Playlist pl) {
        hideBusy();
        if (isFinishing()) return;
        MediaSheet.show(this, pl, (specs, names) -> enqueueMedia(specs, names, source));
    }

    private void enqueueMedia(List<org.json.JSONObject> specs, List<String> names, String source) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSpecs = specs;
            pendingNames = names;
            pendingSource = source;
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        for (int i = 0; i < specs.size(); i++) DownloadService.addMedia(this, specs.get(i), source, names.get(i));
        input.setText("");
        showTab(1); // show the new download where it lives
        if (filter == FILTER_DONE) ((ChipGroup) findViewById(R.id.filters)).check(R.id.chip_all);
        list.scrollToPosition(0);
        String what = specs.size() == 1 ? names.get(0) : specs.size() + " items";
        Snackbar.make(list, "Downloading " + (what == null ? "video" : what), Snackbar.LENGTH_SHORT).show();
        askPermissionsOnce();
    }

    private void handle(String source, List<Option> options) {
        if (isFinishing()) return;
        if (options.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_globe)
                    .setTitle("No direct file here")
                    .setMessage("This page doesn't link straight to a file. Open it in the built-in browser and tap its "
                            + "download button, or use Scan links there.")
                    .setPositiveButton("Open browser", (d, w) -> BrowserActivity.open(this, source))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (options.size() == 1 && options.get(0).ready) {
            enqueue(options.get(0), source);
        } else {
            choose(source, options);
        }
    }

    private void choose(String source, List<Option> options) {
        boolean mirrors = false;
        for (Option o : options) if (!o.ready) mirrors = true;
        View head = LayoutInflater.from(this).inflate(R.layout.dialog_title, null);
        ((TextView) head.findViewById(R.id.title)).setText(mirrors ? "Choose a server" : "Choose a file");
        ((TextView) head.findViewById(R.id.subtitle)).setText(
                options.size() + (options.size() == 1 ? " option found" : " options found"));
        new MaterialAlertDialogBuilder(this)
                .setCustomTitle(head)
                .setAdapter(new OptionList(options), (d, which) -> {
                    Option o = options.get(which);
                    if (o.ready) enqueue(o, source);
                    else resolve(source, o);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resolveFailed(String source, String message) {
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_error)
                .setTitle("Couldn't get this link")
                .setMessage(message)
                .setPositiveButton("Open in browser", (d, w) -> BrowserActivity.open(this, source))
                .setNegativeButton("Close", null)
                .show();
    }

    private void enqueue(Option o, String source) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingOption = o;
            pendingSource = source;
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        DownloadService.add(this, o, source);
        input.setText("");
        showTab(1); // show the new download where it lives
        if (filter == FILTER_DONE) ((ChipGroup) findViewById(R.id.filters)).check(R.id.chip_all);
        list.scrollToPosition(0);
        String name = o.name != null ? o.name : o.label;
        Snackbar.make(list, "Downloading " + (name == null ? "file" : name), Snackbar.LENGTH_SHORT).show();
        askPermissionsOnce();
    }

    private void askPermissionsOnce() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Prefs.flag(this, "askedNotifications")) {
            Prefs.flag(this, "askedNotifications", true);
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !Prefs.flag(this, "askedBattery")) {
            Prefs.flag(this, "askedBattery", true);
            new MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_battery)
                    .setTitle("Keep downloads running?")
                    .setMessage("So big downloads don't pause when you leave the app or the screen turns off, let "
                            + "Downloader Ultra run in the background.")
                    .setPositiveButton("Allow", (d, w) -> requestBatteryExemption())
                    .setNegativeButton("Not now", null)
                    .show();
        }
    }

    void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---- job actions -------------------------------------------------------------------

    @Override
    public void primary(Job j) {
        switch (j.state) {
            case Job.QUEUED:
            case Job.CONNECTING:
            case Job.DOWNLOADING:
                DownloadService.pause(this, j.id);
                break;
            case Job.PAUSED:
            case Job.FAILED:
                DownloadService.resume(this, j.id);
                break;
            case Job.DONE:
                open(j);
                break;
            default:
                break;
        }
    }

    @Override
    public void card(Job j) {
        if (Job.DONE.equals(j.state)) open(j);
        else if (Job.FAILED.equals(j.state)) showError(j);
        else primary(j);
    }

    @Override
    public void more(Job j, View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.setForceShowIcon(true);
        boolean done = Job.DONE.equals(j.state);
        if (done) {
            m.getMenu().add(0, 1, 0, "Open").setIcon(R.drawable.ic_open);
            m.getMenu().add(0, 2, 0, "Share").setIcon(R.drawable.ic_share);
        } else if (Job.PAUSED.equals(j.state)) {
            m.getMenu().add(0, 3, 0, "Resume").setIcon(R.drawable.ic_play);
        } else if (Job.FAILED.equals(j.state)) {
            m.getMenu().add(0, 3, 0, "Retry").setIcon(R.drawable.ic_refresh);
            m.getMenu().add(0, 9, 0, "Show error").setIcon(R.drawable.ic_error);
        } else {
            m.getMenu().add(0, 4, 0, "Pause").setIcon(R.drawable.ic_pause);
        }
        if (j.source != null || j.url != null) m.getMenu().add(0, 5, 0, "Copy link").setIcon(R.drawable.ic_copy);
        if (done) {
            m.getMenu().add(0, 6, 0, "Delete file").setIcon(R.drawable.ic_delete);
            m.getMenu().add(0, 7, 0, "Remove from list").setIcon(R.drawable.ic_close);
        } else {
            m.getMenu().add(0, 8, 0, "Cancel download").setIcon(R.drawable.ic_delete);
        }
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: open(j); break;
                case 2: share(j); break;
                case 3: DownloadService.resume(this, j.id); break;
                case 4: DownloadService.pause(this, j.id); break;
                case 5: copyLink(j); break;
                case 6: confirmDelete(j); break;
                case 7: store.remove(j.id); break;
                case 8: confirmCancel(j); break;
                case 9: showError(j); break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    private void open(Job j) {
        if (j.uri == null) {
            toast("File unavailable");
            return;
        }
        Uri uri = Uri.parse(j.uri);
        if (!Saver.exists(this, j.uri)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("File not found")
                    .setMessage(j.name + " was moved or deleted outside the app.")
                    .setPositiveButton("Remove from list", (d, w) -> store.remove(j.id))
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        String mime = Opener.mime(this, uri, j.name, j.mime);
        if (Opener.APK.equals(mime) && !getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_info)
                    .setTitle("Allow installing apps?")
                    .setMessage("To install this APK, allow Downloader Ultra to install unknown apps, then tap Open again.")
                    .setPositiveButton("Allow", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        try {
            startActivity(Opener.view(this, uri, j.name, j.mime));
        } catch (ActivityNotFoundException e) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No app can open this file")
                    .setMessage(j.name + "\n\nYou can share it to another app instead.")
                    .setPositiveButton("Share", (d, w) -> share(j))
                    .setNegativeButton("Close", null)
                    .show();
        }
    }

    private void share(Job j) {
        try {
            startActivity(Opener.share(this, Uri.parse(j.uri), j.name, j.mime));
        } catch (Exception e) {
            toast("Couldn't share this file");
        }
    }

    private void copyLink(Job j) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        String link = j.source != null ? j.source : j.url;
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Link", link));
        if (Build.VERSION.SDK_INT < 33) toast("Link copied");
    }

    private void confirmDelete(Job j) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this file?")
                .setMessage(j.name + "\n\nIt will be removed from your phone and from this list.")
                .setPositiveButton("Delete", (d, w) -> {
                    boolean gone = Saver.delete(this, j.uri);
                    store.remove(j.id);
                    Snackbar.make(list, gone ? "File deleted" : "Removed from list (file was already gone)",
                            Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmCancel(Job j) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Cancel this download?")
                .setMessage("The part downloaded so far will be deleted.")
                .setPositiveButton("Cancel download", (d, w) -> DownloadService.cancel(this, j.id))
                .setNegativeButton("Keep", null)
                .show();
    }

    private void showError(Job j) {
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_error)
                .setTitle("Download failed")
                .setMessage((j.name == null ? "" : j.name + "\n\n") + (j.error == null ? "Unknown error" : j.error))
                .setPositiveButton("Retry", (d, w) -> DownloadService.resume(this, j.id))
                .setNeutralButton("Open in browser", (d, w) -> BrowserActivity.open(this, j.source != null ? j.source : j.url))
                .setNegativeButton("Close", null)
                .show();
    }

    // ---- helpers --------------------------------------------------------------------------

    private void showBusy(String text) {
        hideBusy();
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_busy, null);
        busyText = v.findViewById(R.id.busy_text);
        busyText.setText(text);
        busy = new MaterialAlertDialogBuilder(this).setView(v).setCancelable(false).show();
    }

    /** Updates the "working…" text from any thread. */
    private void busy(String text) {
        runOnUiThread(() -> {
            if (busyText != null) busyText.setText(text);
        });
    }

    private void hideBusy() {
        if (busy != null && busy.isShowing() && !isFinishing()) busy.dismiss();
        busy = null;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Two-line rows for the server/file picker. */
    private final class OptionList extends BaseAdapter {
        private final List<Option> items;

        OptionList(List<Option> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_option, parent, false);
            Option o = items.get(i);
            ((TextView) v.findViewById(R.id.option_title)).setText(o.label == null ? "Download" : o.label);
            ((TextView) v.findViewById(R.id.option_detail)).setText(o.detail());
            ((android.widget.ImageView) v.findViewById(R.id.option_icon))
                    .setImageResource(o.ready ? R.drawable.ic_download : R.drawable.ic_globe);
            return v;
        }
    }
}
