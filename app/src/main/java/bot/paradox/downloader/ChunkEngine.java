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
 * Downloads one file, in parallel pieces when the server supports byte ranges,
 * or as a single stream otherwise.
 *
 * <p>Speed (v4.6): pieces are sized to the file (1-8 MB) so a connection
 * streams for seconds instead of a fraction of one; finished connections go
 * back to the keep-alive pool instead of being closed, so the next piece skips
 * the TCP/TLS handshake and slow-start; and the resume journal is written at
 * most every two seconds without a disk sync (it used to sync after every
 * piece while holding the lock that every other connection needs).
 *
 * <p>Fixes from v4.2: the resume journal is keyed to the job and file size, not
 * to the redirected URL (which changes on every start for most CDNs, so resume
 * used to restart from zero); progress is counted per read, not per finished
 * piece, so speed and time-left are smooth; a failed piece takes back the bytes
 * it had counted.
 */
final class ChunkEngine {
    private static final int MIN_PIECE = 1024 * 1024, MAX_PIECE = 8 * 1024 * 1024, STEP = 256 * 1024;
    private static final int BUF = 128 * 1024;
    private static final long JOURNAL_EVERY_NS = 2_000_000_000L;
    private static final int ATTEMPTS = 5;

    /** Set by Pause/Cancel. Shared with the caller so one flag stops every stage of a job. */
    final AtomicBoolean stop;
    /** Set when one piece fails for good, to stop the others; unlike {@link #stop} it is not a pause. */
    private final AtomicBoolean halt = new AtomicBoolean();
    final AtomicLong done = new AtomicLong();
    volatile long total = -1;
    /** True once a single-stream download has started; such downloads can't resume. */
    volatile boolean restartsOnResume;
    private volatile int piece = MIN_PIECE;
    private long lastJournal;

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

    /** About six pieces per connection, so the tail is short and every stream runs long. */
    static int pieceSize(long size, int connections) {
        long p = size / (Math.max(1, connections) * 6L);
        p = (p + STEP - 1) / STEP * STEP;
        return (int) Math.max(MIN_PIECE, Math.min(MAX_PIECE, p));
    }

    private void segmented(Http.Info info, File part, File journal, String key, int connections) throws Exception {
        final long size = info.size;
        total = size;
        piece = pieceSize(size, connections);
        long count = (size + piece - 1) / piece;
        if (count <= 0 || count > Integer.MAX_VALUE / 2) throw new IOException("File too large");
        final int pieces = (int) count;
        final String journalKey = key + "\n" + size + "\n" + piece;
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
                        byte[] buf = new byte[BUF];
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
                                long now = System.nanoTime();
                                if (now - lastJournal > JOURNAL_EVERY_NS) {
                                    lastJournal = now;
                                    saveJournal(journal, journalKey, pieces, have);
                                }
                            }
                        }
                    }));
                }
                for (Future<?> f : futures) f.get();
            } finally {
                pool.shutdownNow();
                synchronized (journalLock) {
                    saveJournal(journal, journalKey, pieces, have); // so a pause or crash keeps every finished piece
                }
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
        final long start = (long) idx * piece;
        final long end = Math.min(size - 1, start + piece - 1);
        for (int attempt = 1; ; attempt++) {
            long counted = 0;
            boolean clean = false;
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
                clean = true; // body fully read: closing the stream returns the socket to the keep-alive pool
                return;
            } catch (IOException e) {
                done.addAndGet(-counted);
                if (attempt >= ATTEMPTS || halted()) throw e;
                Thread.sleep(400L * attempt * attempt);
            } finally {
                if (c != null && !clean) c.disconnect();
            }
        }
    }

    private long pieceLength(int idx, long size) {
        return Math.min(piece, size - (long) idx * piece);
    }

    private static void saveJournal(File journal, String key, int pieces, BitSet have) {
        try {
            writeJournal(journal, key, pieces, have);
        } catch (IOException ignored) {
            // Losing a journal write only costs a re-download of those pieces.
        }
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
                byte[] buf = new byte[BUF];
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
