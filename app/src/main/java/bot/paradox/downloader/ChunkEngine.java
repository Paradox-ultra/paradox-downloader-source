package bot.paradox.downloader;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads one file, in parallel 2 MB pieces when the server supports byte
 * ranges, or as a single stream otherwise.
 *
 * <p>Fixes from v4.2: the resume journal is keyed to the job and file size, not
 * to the redirected URL (which changes on every start for most CDNs, so resume
 * used to restart from zero); progress is counted per read, not per finished
 * piece, so speed and time-left are smooth; a failed piece takes back the bytes
 * it had counted.
 */
final class ChunkEngine {
    static final int PIECE = 2 * 1024 * 1024;
    private static final int ATTEMPTS = 5;

    /** Set by Pause/Cancel. Shared with the caller so one flag stops every stage of a job. */
    final AtomicBoolean stop;
    /** Set when one piece fails for good, to stop the others; unlike {@link #stop} it is not a pause. */
    private final AtomicBoolean halt = new AtomicBoolean();
    final AtomicLong done = new AtomicLong();
    volatile long total = -1;
    /** True once a single-stream download has started; such downloads can't resume. */
    volatile boolean restartsOnResume;

    ChunkEngine() {
        this(new AtomicBoolean());
    }

    ChunkEngine(AtomicBoolean stop) {
        this.stop = stop;
    }

    private boolean halted() {
        return stop.get() || halt.get();
    }

    void run(Http.Info info, File part, File journal, String key, int connections) throws Exception {
        if (info.ranges && info.size > 0) {
            segmented(info, part, journal, key, Math.max(1, Math.min(16, connections)));
        } else {
            restartsOnResume = true;
            single(info, part);
        }
    }

    // ---- multi-connection ----------------------------------------------------

    private void segmented(Http.Info info, File part, File journal, String key, int connections) throws Exception {
        final long size = info.size;
        total = size;
        long count = (size + PIECE - 1) / PIECE;
        if (count <= 0 || count > Integer.MAX_VALUE / 2) throw new IOException("File too large");
        final int pieces = (int) count;
        final String journalKey = key + "\n" + size;
        final BitSet have = new BitSet(pieces);

        if (journal.exists() && part.exists() && part.length() == size) readJournal(journal, journalKey, pieces, have);

        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            if (raf.length() != size) {
                raf.setLength(size);
                have.clear();
            }
            for (int i = have.nextSetBit(0); i >= 0; i = have.nextSetBit(i + 1)) done.addAndGet(pieceLength(i, size));

            final FileChannel ch = raf.getChannel();
            final AtomicInteger next = new AtomicInteger();
            final AtomicReference<Exception> failure = new AtomicReference<>();
            final Object journalLock = new Object();
            int threads = Math.min(connections, Math.max(1, pieces - have.cardinality()));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        byte[] buf = new byte[64 * 1024];
                        while (!halted() && failure.get() == null) {
                            int idx = next.getAndIncrement();
                            if (idx >= pieces) return;
                            synchronized (journalLock) {
                                if (have.get(idx)) continue;
                            }
                            try {
                                fetchPiece(info, ch, idx, size, buf);
                            } catch (InterruptedException e) {
                                return;
                            } catch (Exception e) {
                                failure.compareAndSet(null, e);
                                halt.set(true);
                                return;
                            }
                            if (halted()) return;
                            synchronized (journalLock) {
                                have.set(idx);
                                try {
                                    writeJournal(journal, journalKey, pieces, have);
                                } catch (IOException ignored) {
                                    // Losing a journal write only costs a re-download of that piece.
                                }
                            }
                        }
                    }));
                }
                for (Future<?> f : futures) f.get();
            } finally {
                pool.shutdownNow();
            }

            Exception e = failure.get();
            if (e != null) throw e;
            if (stop.get()) throw new InterruptedException("Paused");
            synchronized (journalLock) {
                if (have.cardinality() != pieces) throw new IOException("Download incomplete");
            }
            ch.force(true);
        }
    }

    private void fetchPiece(Http.Info info, FileChannel ch, int idx, long size, byte[] buf) throws Exception {
        final long start = (long) idx * PIECE;
        final long end = Math.min(size - 1, start + PIECE - 1);
        for (int attempt = 1; ; attempt++) {
            long counted = 0;
            HttpURLConnection c = null;
            try {
                c = Http.open(info.url, "GET", "bytes=" + start + "-" + end, info.cookie, info.referer);
                int code = c.getResponseCode();
                String cr = c.getHeaderField("Content-Range");
                if (code != 206 || cr == null || !cr.startsWith("bytes " + start + "-")) {
                    throw new IOException(code == 403 || code == 410
                            ? "Link expired (HTTP " + code + "). Tap retry."
                            : "Server stopped sending parts (HTTP " + code + ")");
                }
                long pos = start;
                try (InputStream in = c.getInputStream()) {
                    while (pos <= end) {
                        if (halted()) return;
                        int want = (int) Math.min(buf.length, end - pos + 1);
                        int n = in.read(buf, 0, want);
                        if (n == -1) break;
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                        long at = pos;
                        while (bb.hasRemaining()) at += ch.write(bb, at);
                        pos += n;
                        counted += n;
                        done.addAndGet(n);
                    }
                }
                if (pos != end + 1) throw new IOException("Connection dropped");
                return;
            } catch (IOException e) {
                done.addAndGet(-counted);
                if (attempt >= ATTEMPTS || halted()) throw e;
                Thread.sleep(400L * attempt * attempt);
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private static long pieceLength(int idx, long size) {
        return Math.min(PIECE, size - (long) idx * PIECE);
    }

    private static void readJournal(File journal, String key, int pieces, BitSet have) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(journal))) {
            if (!key.equals(in.readUTF()) || in.readInt() != pieces) return;
            byte[] bits = new byte[in.readInt()];
            in.readFully(bits);
            have.or(BitSet.valueOf(bits));
        } catch (IOException e) {
            have.clear();
        }
    }

    private static void writeJournal(File journal, String key, int pieces, BitSet have) throws IOException {
        File tmp = new File(journal.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(fos)) {
            out.writeUTF(key);
            out.writeInt(pieces);
            byte[] bits = have.toByteArray();
            out.writeInt(bits.length);
            out.write(bits);
            out.flush();
            fos.getFD().sync();
        }
        if (!tmp.renameTo(journal)) throw new IOException("Journal update failed");
    }

    // ---- single stream -------------------------------------------------------

    private void single(Http.Info info, File part) throws Exception {
        total = info.size;
        done.set(0);
        HttpURLConnection c = Http.open(info.url, "GET", null, info.cookie, info.referer);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Server answered HTTP " + code);
            long got = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, false)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (stop.get()) throw new InterruptedException("Paused");
                    out.write(buf, 0, n);
                    got += n;
                    done.set(got);
                }
                out.getFD().sync();
            }
            if (total > 0 && got != total) throw new IOException("Connection dropped (" + got + " of " + total + " bytes)");
            if (total <= 0) total = got;
        } finally {
            c.disconnect();
        }
    }
}
