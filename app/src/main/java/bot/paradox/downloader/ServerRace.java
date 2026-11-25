package bot.paradox.downloader;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * For a "hub" page that lists several servers for one file: opens every server to get its direct
 * link, downloads a short sample from each at the same time, and picks the quickest.
 */
final class ServerRace {
    interface Say {
        void say(String text);
    }

    static final class Result {
        final Option option;
        final long bytesPerSecond;
        final int tested;

        Result(Option option, long bytesPerSecond, int tested) {
            this.option = option;
            this.bytesPerSecond = bytesPerSecond;
            this.tested = tested;
        }
    }

    private static final class Measure {
        Option option;
        long bps;
        long size = -1;
        String name;
    }

    private static final int MAX_SERVERS = 10;
    private static final int MAX_DIRECT = 12;
    private static final long SAMPLE_BYTES = 1_500_000;
    private static final long SAMPLE_MS = 2500;

    private ServerRace() {}

    /** True when the list holds mirror pages that still need opening (a hub), not a plain choice of files. */
    static boolean applies(List<Option> options) {
        if (options.size() < 2) return false;
        for (Option o : options) if (!o.ready) return true;
        return false;
    }

    /** Returns the fastest working server, or null when none could be tested. */
    static Result run(List<Option> options, Say say) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            say.say("Opening " + Math.min(options.size(), MAX_SERVERS) + " servers…");
            List<Option> direct = expand(pool, options);
            if (direct.isEmpty()) return null;
            say.say("Testing " + direct.size() + (direct.size() == 1 ? " server…" : " servers…"));
            List<Measure> ms = measure(pool, direct);
            if (ms.isEmpty()) return null;

            // Mirrors of one file agree on its size; drop the odd ones out (ads, other files).
            Map<Long, Integer> votes = new HashMap<>();
            for (Measure m : ms) if (m.size > 0) votes.merge(m.size, 1, Integer::sum);
            long size = -1;
            int best = 1;
            for (Map.Entry<Long, Integer> e : votes.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    size = e.getKey();
                }
            }
            List<Measure> use = new ArrayList<>();
            for (Measure m : ms) if (size < 0 || m.size == size || m.size < 0) use.add(m);
            if (use.isEmpty()) use = ms;
            Collections.sort(use, (a, b) -> Long.compare(b.bps, a.bps));
            Measure top = use.get(0);
            if (top.size > 0) top.option.size = top.size;
            if (top.option.name == null && top.name != null) top.option.name = top.name;
            return new Result(top.option, top.bps, ms.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Opens each mirror page (up to two levels) and returns the direct file links found. */
    private static List<Option> expand(ExecutorService pool, List<Option> options) throws InterruptedException {
        List<Option> direct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Option> pending = new ArrayList<>();
        for (Option o : options) {
            if (o.ready) {
                if (seen.add(o.url) && direct.size() < MAX_DIRECT) direct.add(o);
            } else if (pending.size() < MAX_SERVERS) {
                pending.add(o);
            }
        }
        for (int round = 0; round < 2 && !pending.isEmpty(); round++) {
            List<Callable<List<Option>>> jobs = new ArrayList<>();
            for (Option o : pending) jobs.add(() -> Resolver.resolve(o));
            List<Future<List<Option>>> done = pool.invokeAll(jobs, 12, TimeUnit.SECONDS);
            pending = new ArrayList<>();
            for (Future<List<Option>> f : done) {
                if (f.isCancelled()) continue;
                try {
                    for (Option r : f.get()) {
                        if (r.ready) {
                            if (seen.add(r.url) && direct.size() < MAX_DIRECT) direct.add(r);
                        } else if (round == 0 && pending.size() < MAX_SERVERS) {
                            pending.add(r);
                        }
                    }
                } catch (ExecutionException ignored) {
                    // That server didn't open; the others still count.
                }
            }
        }
        return direct;
    }

    private static List<Measure> measure(ExecutorService pool, List<Option> direct) throws InterruptedException {
        List<Callable<Measure>> jobs = new ArrayList<>();
        for (Option o : direct) jobs.add(() -> sample(o));
        List<Measure> out = new ArrayList<>();
        for (Future<Measure> f : pool.invokeAll(jobs, 10, TimeUnit.SECONDS)) {
            if (f.isCancelled()) continue;
            try {
                Measure m = f.get();
                if (m != null) out.add(m);
            } catch (ExecutionException ignored) {
                // Dead or refused: not a candidate.
            }
        }
        return out;
    }

    /** Downloads the first part of the file and times it. Throws when the link isn't a file. */
    private static Measure sample(Option o) throws Exception {
        HttpURLConnection c = Http.open(o.url, "GET", "bytes=0-" + (SAMPLE_BYTES + 500_000 - 1), o.cookie, o.referer);
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            if (Http.isPage(c.getContentType())) throw new java.io.IOException("Not a file");
            Measure m = new Measure();
            m.option = o;
            m.name = Http.filename(c.getHeaderField("Content-Disposition"));
            String cr = c.getHeaderField("Content-Range");
            if (code == 206 && cr != null && cr.indexOf('/') > 0) m.size = Http.number(cr.substring(cr.lastIndexOf('/') + 1));
            else m.size = Http.number(c.getHeaderField("Content-Length"));
            long start = System.nanoTime();
            long first = -1;
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = c.getInputStream()) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    long now = System.nanoTime();
                    if (first < 0) first = now;
                    total += n;
                    if (total >= SAMPLE_BYTES || (now - first) / 1_000_000 >= SAMPLE_MS) break;
                }
            }
            if (total == 0) throw new java.io.IOException("Empty");
            long end = System.nanoTime();
            // Speed after the first byte; a tiny file can't show speed, so fall back to total time.
            double secs = Math.max(0.2, (end - (total > 200_000 ? first : start)) / 1e9);
            m.bps = (long) (total / secs);
            return m;
        } finally {
            c.disconnect();
        }
    }
}
