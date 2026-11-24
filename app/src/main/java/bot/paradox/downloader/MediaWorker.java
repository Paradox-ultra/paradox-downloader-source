package bot.paradox.downloader;

import android.content.Context;
import android.net.Uri;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one video/music job: finds the streams again (their links expire), downloads
 * them with {@link ChunkEngine} (or lets yt-dlp do it), joins video and audio with
 * ffmpeg, adds tags and cover art, and saves the result plus any thumbnail and
 * captions to Downloads.
 */
final class MediaWorker {
    /** What a finished job saved; the main file is shown in the list. */
    static final class Result {
        Uri uri;
        String name;
        String mime;
        long size;
    }

    /** Progress across several stages, read by the service's ticker. */
    static final class Progress {
        final AtomicBoolean stop;
        volatile ChunkEngine engine;
        volatile long base;          // bytes finished in earlier stages
        volatile long grand = -1;    // all bytes expected, -1 if unknown
        volatile double percent = -1; // yt-dlp progress, 0-100

        Progress(AtomicBoolean stop) {
            this.stop = stop;
        }

        long done() {
            ChunkEngine e = engine;
            if (percent >= 0 && grand > 0) return (long) (grand * percent / 100);
            return base + (e == null ? 0 : e.done.get());
        }

        long total() {
            if (grand > 0) return grand;
            ChunkEngine e = engine;
            return e == null ? -1 : e.total;
        }
    }

    interface Stage {
        void set(String text, String name);
    }

    private final Context c;
    private final String id;
    private final JSONObject spec;
    private final Progress progress;
    private final Stage stage;
    private final int connections;
    private final File work;

    MediaWorker(Context c, String id, JSONObject spec, Progress progress, Stage stage) {
        this.c = c.getApplicationContext();
        this.id = id;
        this.spec = spec;
        this.progress = progress;
        this.stage = stage;
        this.connections = Prefs.connections(c);
        this.work = workDir(c, id);
    }

    static File workDir(Context c, String id) {
        return new File(new File(c.getFilesDir(), "media"), id);
    }

    Result run() throws Exception {
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        String engine = spec.optString("e", "np");
        String page = spec.optString("page", null);
        if ("find".equals(engine)) {
            stage.set("Finding on YouTube Music…", null);
            page = Media.findOnYouTubeMusic(spec.optString("q"));
            spec.put("page", page);
            engine = "np";
        }
        checkStop();
        if ("yt".equals(engine)) return ytdlp(page);
        try {
            return newPipe(page);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (progress.stop.get()) throw new InterruptedException();
            // YouTube changes often; yt-dlp is the second opinion.
            stage.set("Trying yt-dlp…", null);
            progress.base = 0;
            progress.grand = -1;
            progress.engine = null;
            try {
                return ytdlp(page);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception second) {
                throw e;
            }
        }
    }

    // ---- NewPipe + ChunkEngine + ffmpeg ---------------------------------------------------

    private Result newPipe(String page) throws Exception {
        stage.set("Getting video details…", null);
        Resolver.initNewPipe();
        StreamInfo info = StreamInfo.getInfo(page);
        checkStop();
        boolean audioOnly = spec.optBoolean("audio");
        String title = spec.optString("title", null);
        if (title == null || title.isEmpty()) title = Media.nonEmpty(info.getName(), "video");
        String artist = spec.has("artist") ? spec.optString("artist") : info.getUploaderName();
        String base = Http.safeName(spec.has("artist") && !spec.optString("artist").isEmpty() && audioOnly
                ? firstArtist(artist) + " - " + title : title);

        List<AudioStream> audios = Media.audioStreams(info);
        if (audioOnly) {
            AudioStream a = Media.pickAudio(audios, spec.optString("fmt"));
            if (a == null) throw new IOException("This has no separate audio stream");
            boolean opus = Media.isOpus(a);
            String finalName = base + (opus ? ".opus" : ".m4a");
            stage.set(null, finalName);
            Http.Info ai = Http.probe(a.getContent(), null, null);
            progress.grand = ai.size;
            File af = download(ai, "a");
            File cover = spec.optBoolean("thumb") ? fetchSmall(Media.bestImage(info.getThumbnails()), "cover") : null;
            checkStop();
            stage.set("Adding tags…", null);
            File out = new File(work, "out." + (opus ? "opus" : "m4a"));
            Map<String, String> tags = Media.tags(title, firstArtist(artist));
            boolean coverEmbedded = false;
            try {
                tagAudio(af, cover, out, tags);
                coverEmbedded = cover != null;
            } catch (Exception withCover) {
                try {
                    tagAudio(af, null, out, tags);
                } catch (Exception plain) {
                    // Keep the stream as downloaded rather than fail the whole job.
                    out = af;
                    finalName = base + (opus ? ".webm" : ".m4a");
                }
            }
            if (cover != null && !coverEmbedded) Saver.publish(c, cover, base + imageExt(cover), null);
            return finish(out, finalName, opus ? "audio/ogg" : "audio/mp4");
        }

        List<VideoStream> videos = Media.videoStreams(info);
        VideoStream v = Media.pickVideo(videos, spec.optInt("h"));
        if (v == null) v = Media.pickVideo(videos, 0);
        if (v == null) throw new IOException("No video stream found");
        boolean merge = v.isVideoOnly();
        AudioStream a = null;
        if (merge) {
            a = Media.pickAudio(audios, Media.isVp(v) ? "opus" : "m4a");
            if (a == null) throw new IOException("This video has no audio stream to add");
        }
        String ext;
        if (!merge) ext = v.getFormat() == null ? "mp4" : v.getFormat().getSuffix();
        else if (Media.isVp(v)) ext = Media.isOpus(a) ? "webm" : "mkv";
        else ext = "mp4";
        String finalName = base + "." + ext;
        stage.set(null, finalName);

        Http.Info vi = Http.probe(v.getContent(), null, null);
        Http.Info ai = merge ? Http.probe(a.getContent(), null, null) : null;
        progress.grand = vi.size > 0 && (ai == null || ai.size > 0) ? vi.size + (ai == null ? 0 : ai.size) : -1;
        File vf = download(vi, "v");
        File out = vf;
        if (merge) {
            progress.base = vi.size > 0 ? vi.size : vf.length();
            File af = download(ai, "a");
            checkStop();
            stage.set("Joining video and audio…", null);
            out = new File(work, "out." + ext);
            List<String> args = new ArrayList<>(Arrays.asList("-y", "-i", vf.getPath(), "-i", af.getPath(),
                    "-map", "0:v:0", "-map", "1:a:0", "-c", "copy"));
            if (ext.equals("mp4")) args.addAll(Arrays.asList("-movflags", "+faststart"));
            for (Map.Entry<String, String> t : Media.tags(title, artist).entrySet()) {
                args.add("-metadata");
                args.add(t.getKey() + "=" + t.getValue());
            }
            args.add(out.getPath());
            try {
                Media.ffmpeg(c, args);
            } catch (Exception e) {
                if (ext.equals("mkv")) throw e;
                // Matroska takes any codec pair.
                ext = "mkv";
                finalName = base + ".mkv";
                stage.set("Joining video and audio…", finalName);
                out = new File(work, "out.mkv");
                args.set(args.size() - 1, out.getPath());
                args.removeAll(Arrays.asList("-movflags", "+faststart"));
                Media.ffmpeg(c, args);
            }
        }
        checkStop();
        if (spec.optBoolean("thumb")) {
            File t = fetchSmall(Media.bestImage(info.getThumbnails()), "thumb");
            if (t != null) Saver.publish(c, t, base + imageExt(t), null);
        }
        if (spec.optBoolean("subs")) {
            SubtitlesStream s = Media.pickSubtitles(info.getSubtitles());
            if (s != null) {
                File f = fetchSmall(s.getContent(), "subs");
                String lang = s.getLocale() == null ? "sub" : s.getLocale().getLanguage();
                if (f != null) Saver.publish(c, f, base + "." + lang + "." + s.getExtension(), "text/vtt");
            }
        }
        String mime = ext.equals("webm") ? "video/webm" : ext.equals("mkv") ? "video/x-matroska" : "video/mp4";
        return finish(out, finalName, mime);
    }

    private static String firstArtist(String artist) {
        if (artist == null) return "";
        return artist.split(",")[0].replaceAll("\\s*-\\s*Topic$", "").trim();
    }

    /** Copies the audio into its final container with tags and, when possible, cover art. */
    private void tagAudio(File in, File cover, File out, Map<String, String> tags) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList("-y", "-i", in.getPath()));
        if (cover != null) args.addAll(Arrays.asList("-i", cover.getPath()));
        args.addAll(Arrays.asList("-map", "0:a:0"));
        if (cover != null) args.addAll(Arrays.asList("-map", "1:v:0", "-disposition:v:0", "attached_pic"));
        args.addAll(Arrays.asList("-c", "copy"));
        for (Map.Entry<String, String> t : tags.entrySet()) {
            args.add("-metadata");
            args.add(t.getKey() + "=" + t.getValue());
        }
        args.add(out.getPath());
        Media.ffmpeg(c, args);
    }

    /** Downloads one stream into the job's folder; resumes across pauses. */
    private File download(Http.Info info, String part) throws Exception {
        checkStop();
        File f = new File(work, part + ".bin");
        File journal = new File(work, part + ".journal");
        File unfinished = new File(work, part + ".partial");
        boolean complete = f.exists() && info.size > 0 && f.length() == info.size && !unfinished.exists();
        if (!complete) {
            ChunkEngine e = new ChunkEngine(progress.stop);
            progress.engine = e;
            //noinspection ResultOfMethodCallIgnored
            unfinished.createNewFile();
            e.run(info, f, journal, id + "-" + part, connections);
            //noinspection ResultOfMethodCallIgnored
            unfinished.delete();
            //noinspection ResultOfMethodCallIgnored
            journal.delete();
        }
        checkStop();
        return f;
    }

    /** Thumbnails and caption files: small, one request. */
    private File fetchSmall(String url, String name) {
        if (url == null) return null;
        try {
            HttpURLConnection conn = Http.open(url, "GET", null, null, null);
            try {
                if (conn.getResponseCode() / 100 != 2) return null;
                String type = conn.getContentType() == null ? "" : conn.getContentType();
                String ext = type.contains("png") ? ".png" : type.contains("webp") ? ".webp" : type.contains("jpeg") || type.contains("jpg") ? ".jpg" : "";
                File f = new File(work, name + ext);
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total > 20_000_000) break;
                    }
                }
                return f;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null; // extras never fail the download
        }
    }

    private static String imageExt(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot) : ".jpg";
    }

    // ---- yt-dlp ----------------------------------------------------------------------------------

    private Result ytdlp(String page) throws Exception {
        stage.set("Starting yt-dlp…", null);
        Media.ytdl(c);
        File dir = new File(work, "yt");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        boolean audioOnly = spec.optBoolean("audio");
        int h = spec.optInt("h");
        String cap = h > 0 ? "[height<=" + h + "]" : "";
        YoutubeDLRequest r = new YoutubeDLRequest(page);
        r.addOption("--no-playlist");
        r.addOption("--no-mtime");
        r.addOption("--continue");
        r.addOption("-P", dir.getAbsolutePath());
        r.addOption("-o", "%(title).150B [%(id)s].%(ext)s");
        r.addOption("--concurrent-fragments", String.valueOf(Math.max(1, Math.min(8, connections))));
        if (audioOnly) {
            r.addOption("-f", "m4a".equals(spec.optString("fmt")) ? "ba[ext=m4a]/ba/b" : "ba/b");
            // Extras are separate files: embedding needs Python modules some sites' formats lack.
            if (spec.optBoolean("thumb")) {
                r.addOption("--write-thumbnail");
                r.addOption("--convert-thumbnails", "jpg");
            }
        } else {
            r.addOption("-f", "bv*" + cap + "+ba/b" + cap + "/bv*+ba/b");
            r.addOption("--merge-output-format", "mp4/mkv");
            if (spec.optBoolean("thumb")) {
                r.addOption("--write-thumbnail");
                r.addOption("--convert-thumbnails", "jpg");
            }
            if (spec.optBoolean("subs")) {
                String phone = Locale.getDefault().getLanguage();
                r.addOption("--write-subs");
                r.addOption("--write-auto-subs");
                r.addOption("--sub-langs", phone + ".*," + "en.*");
                r.addOption("--convert-subs", "srt");
            }
        }
        progress.percent = 0;
        stage.set("Downloading with yt-dlp…", null);
        try {
            YoutubeDL.getInstance().execute(r, id, (pct, eta, line) -> {
                if (pct != null && pct >= 0) {
                    progress.percent = pct;
                    if (progress.grand <= 0) stage.set("Downloading · " + Math.round(pct) + "%", null);
                }
                return kotlin.Unit.INSTANCE;
            });
        } catch (YoutubeDL.CanceledException e) {
            throw new InterruptedException();
        }
        checkStop();
        progress.percent = -1;

        File main = null;
        List<File> extras = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp")) continue;
            if (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".srt") || n.endsWith(".vtt")) {
                extras.add(f);
            } else if (main == null || f.length() > main.length()) {
                main = f;
            }
        }
        if (main == null) throw new IOException("yt-dlp finished without a file");
        for (File e : extras) Saver.publish(c, e, Http.safeName(stripId(e.getName())), null);
        String name = Http.safeName(stripId(main.getName()));
        return finish(main, name, null);
    }

    /** "Title [abc123].mp4" → "Title.mp4" */
    private static String stripId(String name) {
        return name.replaceFirst(" \\[[A-Za-z0-9_-]{4,}](\\.)", "$1");
    }

    // ---- common --------------------------------------------------------------------------------

    private Result finish(File out, String name, String mime) throws IOException {
        stage.set("Saving…", name);
        Result r = new Result();
        r.size = out.length();
        r.name = name;
        r.mime = mime != null ? mime : Opener.fromName(name);
        r.uri = Saver.publish(c, out, name, r.mime);
        return r;
    }

    private void checkStop() throws InterruptedException {
        if (progress.stop.get()) throw new InterruptedException();
    }

    static void cleanUp(Context c, String id) {
        delete(workDir(c, id));
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
