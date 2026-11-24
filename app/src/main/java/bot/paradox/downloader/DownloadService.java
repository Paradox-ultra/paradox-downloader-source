package bot.paradox.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs downloads in the background.
 *
 * <p>Every state change goes through {@link JobStore#update} with a check of
 * the current state, so a Pause or Cancel can never be overwritten by a worker
 * thread (v4.2 bugs 2 and 3). Jobs interrupted by the system are re-queued on
 * the next start, whatever stage they were in (bug 8).
 */
public final class DownloadService extends Service {
    static final String CH_ACTIVE = "active";
    static final String CH_DONE = "finished";
    private static final int FG_ID = 7;
    private static final String A_ADD = "add", A_PAUSE = "pause", A_RESUME = "resume", A_CANCEL = "cancel",
            A_PAUSE_ALL = "pause_all", A_KICK = "kick";

    private final Map<String, Runner> running = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobStore store;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean ticking;

    private static final class Runner {
        final ChunkEngine engine = new ChunkEngine();
        long lastDone;
        long lastTime;
        double speed;
    }

    // ---- public API ----------------------------------------------------------

    static String add(Context c, Option o, String source) {
        Job j = new Job();
        j.id = UUID.randomUUID().toString();
        j.url = o.url;
        j.source = source;
        j.name = o.name != null ? o.name : (o.ready && o.label != null && Fmt.ext(o.label).length() > 0 ? o.label : null);
        j.nameFixed = o.name != null;
        j.cookie = o.cookie;
        j.referer = o.referer;
        j.total = o.size;
        j.created = System.currentTimeMillis();
        JobStore.get(c).add(j);
        send(c, A_ADD, j.id);
        return j.id;
    }

    static void pause(Context c, String id) {
        send(c, A_PAUSE, id);
    }

    static void resume(Context c, String id) {
        send(c, A_RESUME, id);
    }

    static void cancel(Context c, String id) {
        send(c, A_CANCEL, id);
    }

    /** Starts queued work left over from a previous run (called when the app opens). */
    static void kick(Context c) {
        if (!JobStore.get(c).idsInState(Job.QUEUED, Job.CONNECTING, Job.DOWNLOADING).isEmpty()) send(c, A_KICK, null);
    }

    private static void send(Context c, String action, String id) {
        Intent i = new Intent(c, DownloadService.class).setAction(action).putExtra("id", id);
        try {
            c.startForegroundService(i);
        } catch (Exception e) {
            // Not allowed from the background; the app will resume work when it opens.
        }
    }

    // ---- lifecycle -------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        store = JobStore.get(this);
        createChannels(this);
        // Anything that was mid-flight when the process died starts again (bug 8).
        for (String id : store.idsInState(Job.CONNECTING, Job.DOWNLOADING)) {
            store.update(id, j -> {
                j.state = Job.QUEUED;
                j.speed = 0;
                return true;
            });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? A_KICK : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra("id");
        if (A_PAUSE.equals(action)) doPause(id);
        else if (A_RESUME.equals(action)) doResume(id);
        else if (A_CANCEL.equals(action)) doCancel(id);
        else if (A_PAUSE_ALL.equals(action)) {
            for (String j : store.idsInState(Job.QUEUED, Job.CONNECTING, Job.DOWNLOADING)) doPause(j);
        }
        pump();
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 limits dataSync services to 6 hours a day: pause cleanly.
        for (String j : store.idsInState(Job.QUEUED, Job.CONNECTING, Job.DOWNLOADING)) doPause(j);
        stopNow();
    }

    @Override
    public void onDestroy() {
        for (Runner r : running.values()) r.engine.stop.set(true);
        releaseLocks();
        store.flush();
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- actions ---------------------------------------------------------------

    private void doPause(String id) {
        store.update(id, j -> {
            if (!j.active()) return false;
            j.state = Job.PAUSED;
            j.speed = 0;
            return true;
        });
        Runner r = running.get(id);
        if (r != null) r.engine.stop.set(true);
    }

    private void doResume(String id) {
        store.update(id, j -> {
            if (!Job.PAUSED.equals(j.state) && !Job.FAILED.equals(j.state)) return false;
            j.state = Job.QUEUED;
            j.error = null;
            return true;
        });
    }

    private void doCancel(String id) {
        Runner r = running.remove(id);
        if (r != null) r.engine.stop.set(true);
        store.remove(id);
        deleteTemp(id);
        cancelNotice(id);
    }

    /** Starts queued jobs, oldest first, up to the parallel limit. */
    private void pump() {
        List<String> queued = store.idsInState(Job.QUEUED);
        int limit = Prefs.parallel(this);
        for (int i = queued.size() - 1; i >= 0 && running.size() < limit; i--) {
            String id = queued.get(i);
            if (running.containsKey(id)) continue;
            Job claimed = store.update(id, j -> {
                if (!Job.QUEUED.equals(j.state)) return false;
                j.state = Job.CONNECTING;
                j.speed = 0;
                return true;
            });
            if (claimed == null) continue;
            Runner r = new Runner();
            running.put(id, r);
            pool.execute(() -> work(claimed, r));
        }
        if (!running.isEmpty()) {
            holdLocks();
            startTicker();
        } else if (store.idsInState(Job.QUEUED).isEmpty()) {
            stopNow();
        }
    }

    // ---- the work --------------------------------------------------------------

    private void work(Job job, Runner r) {
        String id = job.id;
        File part = partFile(id);
        File journal = new File(part.getPath() + ".journal");
        try {
            Http.Info info = Http.probe(job.url, job.cookie, job.referer);
            if (r.engine.stop.get()) return;
            if (Http.isPage(info.type)) throw new java.io.IOException("This link opens a web page, not a file");

            String name = job.nameFixed && job.name != null ? job.name : withExtension(info.name, info.type);
            Job started = store.update(id, j -> {
                if (!Job.CONNECTING.equals(j.state)) return false; // paused or cancelled meanwhile
                j.state = Job.DOWNLOADING;
                j.name = Http.safeName(name);
                j.total = info.size;
                j.ranges = info.ranges;
                j.mime = info.type;
                return true;
            });
            if (started == null) return;

            r.lastTime = System.currentTimeMillis();
            r.engine.run(info, part, journal, id, Prefs.connections(this));

            Job now = store.find(id);
            if (now == null) return; // cancelled while finishing
            Uri uri = Saver.publish(this, part, now.name, info.type);
            long size = r.engine.total > 0 ? r.engine.total : part.length();
            deleteTemp(id);
            Job finished = store.update(id, j -> {
                j.state = Job.DONE;
                j.uri = uri.toString();
                j.done = size;
                j.total = size;
                j.speed = 0;
                j.error = null;
                return true;
            });
            if (finished != null) main.post(() -> doneNotice(finished));
        } catch (InterruptedException e) {
            // Paused or cancelled; the state was already set by doPause/doCancel.
        } catch (Exception e) {
            if (r.engine.stop.get()) return;
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Job failed = store.update(id, j -> {
                if (!j.active()) return false;
                j.state = Job.FAILED;
                j.error = msg;
                j.speed = 0;
                return true;
            });
            if (failed != null) main.post(() -> failNotice(failed));
        } finally {
            boolean restarts = r.engine.restartsOnResume;
            if (restarts) store.update(id, j -> {
                if (!Job.PAUSED.equals(j.state)) return false;
                j.done = 0; // single-stream downloads can't resume (bug 9): be honest about it
                return true;
            });
            main.post(() -> {
                if (running.get(id) == r) running.remove(id);
                pump();
            });
        }
    }

    /** Adds an extension from the content type when the server's name has none. */
    private static String withExtension(String name, String type) {
        if (name == null) name = "download";
        if (!Fmt.ext(name).isEmpty() || type == null) return name;
        String mime = type.split(";")[0].trim();
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return ext == null ? name : name + "." + ext;
    }

    private File partFile(String id) {
        File dir = new File(getFilesDir(), "parts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, id + ".part");
    }

    private void deleteTemp(String id) {
        File part = partFile(id);
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(part.getPath() + ".journal").delete();
    }

    // ---- progress ----------------------------------------------------------------

    private void startTicker() {
        if (ticking) return;
        ticking = true;
        main.postDelayed(this::tick, 500);
    }

    private void tick() {
        if (running.isEmpty()) {
            ticking = false;
            return;
        }
        long now = System.currentTimeMillis();
        long sumDone = 0, sumTotal = 0, sumSpeed = 0;
        boolean unknown = false;
        for (Map.Entry<String, Runner> e : running.entrySet()) {
            Runner r = e.getValue();
            long done = r.engine.done.get();
            long total = r.engine.total;
            if (r.lastTime > 0) {
                double dt = Math.max(1, now - r.lastTime) / 1000.0;
                double inst = Math.max(0, (done - r.lastDone) / dt);
                // Smooth the speed so time-left doesn't jump around (bug 6).
                r.speed = r.speed == 0 ? inst : r.speed * 0.7 + inst * 0.3;
            }
            r.lastDone = done;
            r.lastTime = now;
            long speed = (long) r.speed;
            store.progress(e.getKey(), j -> {
                if (!Job.DOWNLOADING.equals(j.state)) return false;
                j.done = done;
                if (total > 0) j.total = total;
                j.speed = speed;
                return true;
            });
            sumDone += done;
            if (total > 0) sumTotal += total;
            else unknown = true;
            sumSpeed += speed;
        }
        updateForeground(running.size(), sumDone, unknown ? -1 : sumTotal, sumSpeed);
        main.postDelayed(this::tick, 1000);
    }

    // ---- notifications -------------------------------------------------------------

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel active = new NotificationChannel(CH_ACTIVE, "Downloads in progress", NotificationManager.IMPORTANCE_LOW);
        active.setShowBadge(false);
        nm.createNotificationChannel(active);
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private boolean goForeground() {
        try {
            Notification n = foregroundNotice("Preparing downloads…", 0, 0, false);
            if (Build.VERSION.SDK_INT >= 29) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(FG_ID, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateForeground(int count, long done, long total, long speed) {
        String title = count == 1 ? "Downloading 1 file" : "Downloading " + count + " files";
        String text = Fmt.size(done) + (total > 0 ? " of " + Fmt.size(total) : "") + " · " + Fmt.speed(speed);
        int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        Notification n = new Notification.Builder(this, CH_ACTIVE)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, pct, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(appIntent(null))
                .addAction(new Notification.Action.Builder(null, "Pause all", serviceIntent(A_PAUSE_ALL)).build())
                .build();
        getSystemService(NotificationManager.class).notify(FG_ID, n);
    }

    private Notification foregroundNotice(String text, int max, int progress, boolean indeterminate) {
        return new Notification.Builder(this, CH_ACTIVE)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setProgress(max, progress, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(appIntent(null))
                .build();
    }

    private void doneNotice(Job j) {
        Uri uri = Uri.parse(j.uri);
        Intent open = Opener.view(this, uri, j.name, j.mime);
        PendingIntent pi = open.resolveActivity(getPackageManager()) != null
                ? PendingIntent.getActivity(this, noticeId(j.id), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : appIntent(j.id);
        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText(j.name)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        getSystemService(NotificationManager.class).notify(noticeId(j.id), n);
    }

    private void failNotice(Job j) {
        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText((j.name == null ? "Download" : j.name) + " · " + j.error)
                .setStyle(new Notification.BigTextStyle().bigText((j.name == null ? "" : j.name + "\n") + j.error))
                .setAutoCancel(true)
                .setContentIntent(appIntent(j.id))
                .build();
        getSystemService(NotificationManager.class).notify(noticeId(j.id), n);
    }

    private void cancelNotice(String id) {
        getSystemService(NotificationManager.class).cancel(noticeId(id));
    }

    private static int noticeId(String id) {
        return (id.hashCode() & 0x07ffffff) + 1000;
    }

    private PendingIntent appIntent(String jobId) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (jobId != null) i.setAction(MainActivity.ACTION_SHOW_JOB).putExtra("id", jobId);
        return PendingIntent.getActivity(this, jobId == null ? 0 : noticeId(jobId), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent serviceIntent(String action) {
        Intent i = new Intent(this, DownloadService.class).setAction(action);
        return PendingIntent.getForegroundService(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---- locks & stopping ----------------------------------------------------------

    private void holdLocks() {
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Paradox:download");
            wake.setReferenceCounted(false);
        }
        if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
        if (wifi == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                //noinspection deprecation
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Paradox:download");
                wifi.setReferenceCounted(false);
            }
        }
        if (wifi != null && !wifi.isHeld()) wifi.acquire();
    }

    private void releaseLocks() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
    }

    private void stopNow() {
        releaseLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
