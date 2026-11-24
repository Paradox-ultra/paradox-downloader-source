package bot.paradox.downloader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single source of truth for downloads.
 *
 * <p>Fixes from v4.2: jobs keep a stable order (newest first, by creation), every
 * change is an atomic read-modify-write under one lock, progress is kept in
 * memory and written to disk at most once a second, and the UI is told about
 * changes at most 4 times a second instead of on every chunk.
 */
final class JobStore {
    interface Listener {
        void onJobsChanged();
    }

    /** Changes a job in place. Return false to leave it untouched. */
    interface Mutation {
        boolean apply(Job j);
    }

    private static final String TAG = "JobStore";
    private static final long SAVE_DELAY = 1000;
    private static final long NOTIFY_DELAY = 250;
    private static volatile JobStore instance;

    private final File file;
    private final List<Job> jobs = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean saveQueued;
    private boolean notifyQueued;
    private long lastNotify;

    static JobStore get(Context c) {
        if (instance == null) {
            synchronized (JobStore.class) {
                if (instance == null) instance = new JobStore(c.getApplicationContext());
            }
        }
        return instance;
    }

    private JobStore(Context c) {
        file = new File(c.getFilesDir(), "jobs.json");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("id").isEmpty()) jobs.add(Job.fromJson(o));
            }
            jobs.sort((x, y) -> Long.compare(y.created, x.created));
        } catch (Exception e) {
            Log.w(TAG, "Could not read jobs", e);
        }
    }

    // ---- reads -------------------------------------------------------------

    /** Copies of all jobs, newest first. */
    synchronized List<Job> snapshot() {
        List<Job> out = new ArrayList<>(jobs.size());
        for (Job j : jobs) out.add(j.copy());
        return out;
    }

    synchronized Job find(String id) {
        Job j = locate(id);
        return j == null ? null : j.copy();
    }

    synchronized List<String> idsInState(String... states) {
        List<String> out = new ArrayList<>();
        for (Job j : jobs) {
            for (String s : states) {
                if (s.equals(j.state)) {
                    out.add(j.id);
                    break;
                }
            }
        }
        return out;
    }

    // ---- writes ------------------------------------------------------------

    synchronized void add(Job j) {
        jobs.add(0, j);
        changed(true);
    }

    /** Applies {@code m} atomically. Returns a copy of the job after the change, or null. */
    Job update(String id, Mutation m) {
        return update(id, m, true);
    }

    /** Progress-only updates: kept in memory, saved with the next debounced write. */
    Job progress(String id, Mutation m) {
        return update(id, m, false);
    }

    private synchronized Job update(String id, Mutation m, boolean important) {
        Job j = locate(id);
        if (j == null || !m.apply(j)) return null;
        changed(important);
        return j.copy();
    }

    synchronized void remove(String id) {
        Job j = locate(id);
        if (j != null) {
            jobs.remove(j);
            changed(true);
        }
    }

    /** Writes now (used when the service stops). */
    void flush() {
        String data;
        synchronized (this) {
            saveQueued = false;
            data = serialize();
        }
        write(data);
    }

    // ---- listeners -----------------------------------------------------------

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- internals -----------------------------------------------------------

    private Job locate(String id) {
        if (id == null) return null;
        for (Job j : jobs) if (id.equals(j.id)) return j;
        return null;
    }

    /** Must hold the lock. */
    private void changed(boolean important) {
        if (!saveQueued) {
            saveQueued = true;
            main.postDelayed(this::saveSoon, important ? 0 : SAVE_DELAY);
        }
        if (!notifyQueued) {
            notifyQueued = true;
            long wait = Math.max(0, NOTIFY_DELAY - (SystemClock.uptimeMillis() - lastNotify));
            main.postDelayed(this::dispatch, important ? 0 : wait);
        }
    }

    private void saveSoon() {
        String data;
        synchronized (this) {
            if (!saveQueued) return;
            saveQueued = false;
            data = serialize();
        }
        io.execute(() -> write(data));
    }

    private void dispatch() {
        synchronized (this) {
            notifyQueued = false;
            lastNotify = SystemClock.uptimeMillis();
        }
        for (Listener l : listeners) l.onJobsChanged();
    }

    private String serialize() {
        JSONArray a = new JSONArray();
        for (Job j : jobs) {
            try {
                a.put(j.toJson());
            } catch (Exception ignored) {
            }
        }
        return a.toString();
    }

    /** Disk writes use their own lock so slow storage never blocks progress updates. */
    private void write(String data) {
        synchronized (fileLock) {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception e) {
                Log.w(TAG, "Could not save jobs", e);
                return;
            }
            if (!tmp.renameTo(file)) Log.w(TAG, "Could not replace jobs file");
        }
    }

    private final Object fileLock = new Object();
}
