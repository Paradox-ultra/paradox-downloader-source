package bot.paradox.downloader;

import android.content.Context;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Video, music and playlist support.
 *
 * <p>Two engines: NewPipe Extractor for YouTube, YouTube Music, SoundCloud,
 * Bandcamp and PeerTube (fast, and downloads with the app's own multi-connection
 * engine), and yt-dlp for everything else. Separate video and audio streams are
 * joined with the ffmpeg build that ships with yt-dlp, so the highest quality the
 * creator uploaded (4K, 8K, VP9, AV1) can be saved as one file.
 *
 * <p>A download is described by a small JSON "spec" stored on the {@link Job}:
 * <pre>
 *   e      engine: "np" (NewPipe), "yt" (yt-dlp) or "find" (search YouTube Music first)
 *   page   link to the video/track page        q   search text for "find"
 *   audio  true for audio only                 h   max video height, 0 = best
 *   fmt    audio format: "", "opus" or "m4a"   thumb, subs   extras
 *   title, artist   tags for music
 * </pre>
 */
final class Media {
    private Media() {}

    // ---- what the quality sheet shows ------------------------------------------------

    static final class Quality {
        final String label;
        final String detail;
        final int height;      // video: max height, 0 = best
        final String fmt;      // audio: "", "opus", "m4a"

        Quality(String label, String detail, int height, String fmt) {
            this.label = label;
            this.detail = detail;
            this.height = height;
            this.fmt = fmt;
        }
    }

    static final class Probe {
        String engine;
        String page;
        String title;
        String uploader;
        long duration;
        boolean musicFirst;           // open in Audio mode (music sites)
        String thumb;                 // preview image for the sheet and the card
        final List<Quality> video = new ArrayList<>();
        final List<Quality> audio = new ArrayList<>();
        String subsLabel;             // null when there are no captions
    }

    static final class Track {
        String page;   // known video link, or null to search
        String query;  // search text when page is null
        String title;
        String artist;
        String thumb;
    }

    static final class Playlist {
        String name;
        String from;          // "YouTube", "YouTube Music", "Spotify", …
        boolean music;
        boolean spotify;
        final List<Track> tracks = new ArrayList<>();
    }

    // ---- which engine for which link ----------------------------------------------------

    enum Kind { SPOTIFY, NP_PLAYLIST, NP_STREAM, YTDLP_FIRST, OTHER }

    /** Sites where a page is almost always a video, so yt-dlp goes before the file resolver. */
    private static final String[] VIDEO_HOSTS = {
            "instagram.com", "facebook.com", "fb.watch", "x.com", "twitter.com", "tiktok.com", "vimeo.com",
            "dailymotion.com", "dai.ly", "reddit.com", "redd.it", "twitch.tv", "pinterest.com", "pin.it",
            "threads.net", "linkedin.com", "bilibili.com", "rumble.com", "streamable.com", "vk.com", "ok.ru",
            "tumblr.com", "imgur.com", "9gag.com", "mixcloud.com", "audiomack.com", "snapchat.com", "likee.video",
            "sharechat.com", "mojapp.in", "jiosaavn.com", "gaana.com", "ted.com", "nicovideo.jp", "kick.com"};

    static Kind classify(String url) {
        String host;
        try {
            host = new URL(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return Kind.OTHER;
        }
        if (is(host, "open.spotify.com") || is(host, "spotify.link")) return Kind.SPOTIFY;
        try {
            Resolver.initNewPipe();
            StreamingService s = NewPipe.getServiceByUrl(url);
            if (s.getStreamLHFactory().acceptUrl(url)) return Kind.NP_STREAM;
            if (s.getPlaylistLHFactory() != null && s.getPlaylistLHFactory().acceptUrl(url)) return Kind.NP_PLAYLIST;
        } catch (Exception ignored) {
            // Not a NewPipe site.
        }
        for (String h : VIDEO_HOSTS) if (is(host, h)) return Kind.YTDLP_FIRST;
        return Kind.OTHER;
    }

    private static boolean is(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean musicHost(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("music.youtube.com") || u.contains("soundcloud.com") || u.contains("bandcamp.com");
    }

    // ---- NewPipe: one video or song ----------------------------------------------------------

    static Probe newPipe(String url) throws Exception {
        Resolver.initNewPipe();
        StreamInfo info = StreamInfo.getInfo(url);
        Probe p = new Probe();
        p.engine = "np";
        p.page = url;
        p.title = nonEmpty(info.getName(), "video");
        p.uploader = info.getUploaderName();
        p.duration = info.getDuration();
        p.musicFirst = musicHost(url);
        p.thumb = bestImage(info.getThumbnails());

        List<VideoStream> videos = videoStreams(info);
        TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
        for (VideoStream v : videos) if (v.getHeight() > 0) heights.add(v.getHeight());
        if (!heights.isEmpty()) {
            VideoStream top = pickVideo(videos, 0);
            p.video.add(new Quality("Best available", label(top) + " · as uploaded", 0, ""));
            for (int h : heights) {
                VideoStream v = pickVideo(videos, h);
                p.video.add(new Quality(h + "p", label(v), h, ""));
            }
        }

        List<AudioStream> audios = audioStreams(info);
        if (!audios.isEmpty()) {
            AudioStream best = pickAudio(audios, "");
            p.audio.add(new Quality("Best available", audioLabel(best), 0, ""));
            AudioStream opus = pickAudio(audios, "opus");
            AudioStream m4a = pickAudio(audios, "m4a");
            if (opus != null && isOpus(opus)) p.audio.add(new Quality("Opus", audioLabel(opus) + " · smaller, best quality", 0, "opus"));
            if (m4a != null && !isOpus(m4a)) p.audio.add(new Quality("M4A", audioLabel(m4a) + " · plays everywhere", 0, "m4a"));
        }
        if (p.video.isEmpty() && p.audio.isEmpty()) throw new IOException("No downloadable streams found");

        SubtitlesStream sub = pickSubtitles(info.getSubtitles());
        if (sub != null) p.subsLabel = sub.getDisplayLanguageName() + (sub.isAutoGenerated() ? " (auto)" : "");
        return p;
    }

    static List<VideoStream> videoStreams(StreamInfo info) {
        List<VideoStream> out = new ArrayList<>();
        for (VideoStream v : info.getVideoOnlyStreams()) if (usable(v.isUrl(), v.getDeliveryMethod())) out.add(v);
        for (VideoStream v : info.getVideoStreams()) if (usable(v.isUrl(), v.getDeliveryMethod())) out.add(v);
        return out;
    }

    static List<AudioStream> audioStreams(StreamInfo info) {
        List<AudioStream> out = new ArrayList<>();
        for (AudioStream a : info.getAudioStreams()) if (usable(a.isUrl(), a.getDeliveryMethod())) out.add(a);
        return out;
    }

    private static boolean usable(boolean isUrl, DeliveryMethod m) {
        return isUrl && m == DeliveryMethod.PROGRESSIVE_HTTP;
    }

    /** The best stream no taller than {@code maxHeight} (0 = no limit). */
    static VideoStream pickVideo(List<VideoStream> all, int maxHeight) {
        VideoStream best = null;
        for (VideoStream v : all) {
            if (maxHeight > 0 && v.getHeight() > maxHeight) continue;
            if (best == null || VIDEO_ORDER.compare(v, best) > 0) best = v;
        }
        return best;
    }

    private static final Comparator<VideoStream> VIDEO_ORDER = (a, b) -> {
        if (a.getHeight() != b.getHeight()) return Integer.compare(a.getHeight(), b.getHeight());
        if (a.getFps() != b.getFps()) return Integer.compare(a.getFps(), b.getFps());
        // A single file beats a merge only when it is otherwise just as good.
        if (codecRank(a) != codecRank(b)) return Integer.compare(codecRank(a), codecRank(b));
        if (a.getBitrate() != b.getBitrate()) return Integer.compare(a.getBitrate(), b.getBitrate());
        return Boolean.compare(!a.isVideoOnly(), !b.isVideoOnly());
    };

    /** VP9 first (best quality per byte that every phone decodes), then H.264, then AV1. */
    private static int codecRank(VideoStream v) {
        String c = codec(v);
        if (c.startsWith("vp9") || c.startsWith("vp09")) return 4;
        if (c.startsWith("avc")) return 3;
        if (c.startsWith("hev") || c.startsWith("hvc")) return 2;
        if (c.startsWith("av01")) return 1;
        return 0;
    }

    static String codec(VideoStream v) {
        String c = v.getCodec();
        if (c == null || c.isEmpty()) c = v.getFormat() == null ? "" : v.getFormat().getSuffix();
        if (c.equals("webm")) c = "vp9";
        if (c.equals("mp4")) c = "avc";
        return c.toLowerCase(Locale.ROOT);
    }

    static boolean isVp(VideoStream v) {
        return codec(v).startsWith("vp");
    }

    private static String label(VideoStream v) {
        if (v == null) return "";
        String c = codec(v);
        String name = c.startsWith("vp") ? "VP9" : c.startsWith("av01") ? "AV1" : c.startsWith("hev") || c.startsWith("hvc") ? "HEVC" : "H.264";
        return v.getHeight() + "p" + (v.getFps() > 30 ? v.getFps() : "") + " · " + name;
    }

    /** Best audio, optionally of one format; prefers the original language track over dubs. */
    static AudioStream pickAudio(List<AudioStream> all, String fmt) {
        AudioStream best = null;
        for (AudioStream a : all) {
            if ("opus".equals(fmt) && !isOpus(a)) continue;
            if ("m4a".equals(fmt) && isOpus(a)) continue;
            if (best == null || audioScore(a) > audioScore(best)) best = a;
        }
        return best == null && !fmt.isEmpty() ? pickAudio(all, "") : best;
    }

    private static long audioScore(AudioStream a) {
        boolean original = a.getAudioTrackType() == null || a.getAudioTrackType() == AudioTrackType.ORIGINAL;
        return (original ? 1_000_000L : 0) + Math.max(a.getAverageBitrate(), a.getBitrate() / 1000);
    }

    static boolean isOpus(AudioStream a) {
        String c = a.getCodec() == null ? "" : a.getCodec().toLowerCase(Locale.ROOT);
        String ext = a.getFormat() == null ? "" : a.getFormat().getSuffix();
        return c.contains("opus") || ext.equals("webm") || ext.equals("opus") || ext.equals("ogg");
    }

    private static String audioLabel(AudioStream a) {
        if (a == null) return "";
        int kbps = a.getAverageBitrate() > 0 ? a.getAverageBitrate() : a.getBitrate() / 1000;
        return (isOpus(a) ? "Opus" : "AAC") + (kbps > 0 ? " · " + kbps + " kbps" : "");
    }

    /** Captions in the phone's language, then English, then anything; creator-made before auto. */
    static SubtitlesStream pickSubtitles(List<SubtitlesStream> subs) {
        if (subs == null || subs.isEmpty()) return null;
        String phone = Locale.getDefault().getLanguage();
        SubtitlesStream best = null;
        int bestScore = -1;
        for (SubtitlesStream s : subs) {
            if (!s.isUrl()) continue;
            String lang = s.getLocale() == null ? "" : s.getLocale().getLanguage();
            int score = (lang.equals(phone) ? 40 : lang.equals("en") ? 20 : 0) + (s.isAutoGenerated() ? 0 : 10)
                    + ("vtt".equals(s.getExtension()) ? 2 : "srt".equals(s.getExtension()) ? 1 : 0);
            if (score > bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    static String bestImage(List<Image> images) {
        Image best = null;
        if (images != null) for (Image i : images) if (best == null || i.getHeight() > best.getHeight()) best = i;
        return best == null ? null : best.getUrl();
    }

    // ---- NewPipe: playlists ---------------------------------------------------------------

    static Playlist newPipePlaylist(String url) throws Exception {
        Resolver.initNewPipe();
        StreamingService service = NewPipe.getServiceByUrl(url);
        PlaylistInfo info = PlaylistInfo.getInfo(service, url);
        Playlist p = new Playlist();
        p.name = nonEmpty(info.getName(), "Playlist");
        p.music = musicHost(url);
        p.from = url.contains("music.youtube.com") ? "YouTube Music" : service.getServiceInfo().getName();
        add(p, info.getRelatedItems());
        Page next = info.getNextPage();
        while (Page.isValid(next) && p.tracks.size() < 1000) {
            ListExtractor.InfoItemsPage<StreamInfoItem> more = PlaylistInfo.getMoreItems(service, url, next);
            add(p, more.getItems());
            next = more.getNextPage();
        }
        if (p.tracks.isEmpty()) throw new IOException("This playlist is empty or private");
        return p;
    }

    private static void add(Playlist p, List<StreamInfoItem> items) {
        for (StreamInfoItem i : items) {
            if (i.getUrl() == null) continue;
            Track t = new Track();
            t.page = p.music ? i.getUrl().replace("://www.youtube.com", "://music.youtube.com") : i.getUrl();
            t.title = i.getName();
            t.artist = cleanArtist(i.getUploaderName());
            t.thumb = bestImage(i.getThumbnails());
            p.tracks.add(t);
        }
    }

    private static String cleanArtist(String s) {
        return s == null ? null : s.replaceAll("\\s*-\\s*Topic$", "").trim();
    }

    // ---- Spotify -----------------------------------------------------------------------------

    private static final Pattern SPOTIFY = Pattern.compile(
            "open\\.spotify\\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]{10,40})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);

    /**
     * Reads a Spotify playlist, album or song from its public embed page. Spotify
     * audio is DRM-protected, so each song is then found on YouTube Music by name.
     * Without an API key the embed page lists up to about 100 songs.
     */
    static Playlist spotify(String url) throws Exception {
        String link = url;
        if (link.contains("spotify.link")) link = Http.probe(link, null, null).url; // short links redirect
        Matcher m = SPOTIFY.matcher(link);
        if (!m.find()) throw new IOException("Open a Spotify playlist, album or song link");
        String type = m.group(1).toLowerCase(Locale.ROOT), id = m.group(2);
        String html = Http.html("https://open.spotify.com/embed/" + type + "/" + id, null, null, 4_000_000);
        Matcher data = NEXT_DATA.matcher(html);
        String json = data.find() ? data.group(1) : null;
        if (json == null) throw new IOException("Spotify didn't return the track list. Try again in a minute.");
        JSONObject entity = new JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity");
        Playlist p = new Playlist();
        p.spotify = true;
        p.music = true;
        p.from = "Spotify";
        p.name = nonEmpty(entity.optString("name", entity.optString("title")), "Spotify");
        String cover = spotifyCover(entity);
        JSONArray list = entity.optJSONArray("trackList");
        if (list != null && list.length() > 0) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.getJSONObject(i);
                String artists = t.optString("subtitle", "").replace(' ', ' ');
                Track track = searchTrack(t.optString("title"), artists);
                if ("album".equals(type)) track.thumb = cover; // an album's songs share its art
                p.tracks.add(track);
            }
        } else if ("track".equals(type)) {
            StringBuilder artists = new StringBuilder();
            JSONArray a = entity.optJSONArray("artists");
            for (int i = 0; a != null && i < a.length(); i++) {
                if (artists.length() > 0) artists.append(", ");
                artists.append(a.getJSONObject(i).optString("name"));
            }
            Track track = searchTrack(entity.optString("title", entity.optString("name")), artists.toString());
            track.thumb = cover;
            p.tracks.add(track);
        }
        if (p.tracks.isEmpty()) throw new IOException("No songs found in this Spotify link");
        return p;
    }

    /** The largest cover image Spotify's embed data offers, or null. */
    private static String spotifyCover(JSONObject entity) {
        JSONObject art = entity.optJSONObject("coverArt");
        JSONArray sources = art == null ? null : art.optJSONArray("sources");
        String best = null;
        int bestW = -1;
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            int w = s.optInt("width", 0);
            if (w > bestW) {
                bestW = w;
                best = s.optString("url", null);
            }
        }
        return best;
    }

    private static Track searchTrack(String title, String artists) {
        Track t = new Track();
        t.title = title;
        t.artist = artists;
        String first = artists == null ? "" : artists.split(",")[0].trim();
        // Drop "(feat. …)" and "[From …]" so the search matches the song, not the credits.
        String bare = title == null ? "" : title.replaceAll("\\s*[(\\[](?:feat|ft|with|from)[^)\\]]*[)\\]]", "").trim();
        t.query = (first + " " + bare).trim();
        return t;
    }

    /** Finds a song on YouTube Music and returns its link. */
    static String findOnYouTubeMusic(String query) throws Exception {
        Resolver.initNewPipe();
        SearchExtractor s = ServiceList.YouTube.getSearchExtractor(query,
                Collections.singletonList(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "");
        s.fetchPage();
        for (InfoItem i : s.getInitialPage().getItems()) {
            if (i instanceof StreamInfoItem && i.getUrl() != null) return i.getUrl();
        }
        // Fall back to normal YouTube search.
        SearchExtractor y = ServiceList.YouTube.getSearchExtractor(query + " audio");
        y.fetchPage();
        for (InfoItem i : y.getInitialPage().getItems()) {
            if (i instanceof StreamInfoItem && i.getUrl() != null) return i.getUrl();
        }
        throw new IOException("Couldn't find this song on YouTube Music");
    }

    // ---- yt-dlp ------------------------------------------------------------------------------

    private static volatile boolean ytdlReady;

    /** Unpacks Python, yt-dlp and ffmpeg on first use (a few seconds, once per app update). */
    static synchronized void ytdl(Context c) throws Exception {
        if (ytdlReady) return;
        Context app = c.getApplicationContext();
        try {
            YoutubeDL.getInstance().init(app);
            FFmpeg.getInstance().init(app);
        } catch (Throwable t) {
            // An Error here (e.g. a broken unzip) must fail the download, never crash the app.
            throw new IOException("Couldn't start the video engine: " + t, t);
        }
        ytdlReady = true;
    }

    /** Keeps yt-dlp's site support current; sites change often. At most once a day. */
    static void updateYtdlp(Context c) {
        long last = Prefs.sp(c).getLong("ytdlpUpdated", 0);
        if (System.currentTimeMillis() - last < TimeUnit.HOURS.toMillis(24)) return;
        try {
            ytdl(c);
            YoutubeDL.getInstance().updateYoutubeDL(c.getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
            Prefs.sp(c).edit().putLong("ytdlpUpdated", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
            // Try again next start; a background update must never crash the app.
        }
    }

    static Probe ytdlp(Context c, String url) throws Exception {
        ytdl(c);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--no-playlist");
        VideoInfo info = YoutubeDL.getInstance().getInfo(r);
        Probe p = new Probe();
        p.engine = "yt";
        p.page = info.getWebpageUrl() != null ? info.getWebpageUrl() : url;
        p.title = nonEmpty(info.getTitle(), "video");
        p.uploader = info.getUploader();
        p.duration = info.getDuration();
        p.thumb = info.getThumbnail();
        p.musicFirst = musicHost(url);

        int bestH = 0, bestFps = 0;
        TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
        boolean audio = false;
        List<VideoFormat> formats = info.getFormats();
        if (formats != null) {
            for (VideoFormat f : formats) {
                boolean hasV = f.getVcodec() != null && !"none".equals(f.getVcodec());
                boolean hasA = f.getAcodec() != null && !"none".equals(f.getAcodec());
                if (hasV && f.getHeight() > 0) {
                    heights.add(f.getHeight());
                    if (f.getHeight() > bestH || (f.getHeight() == bestH && f.getFps() > bestFps)) {
                        bestH = f.getHeight();
                        bestFps = f.getFps();
                    }
                }
                if (hasA || (!hasV && f.getVcodec() == null)) audio = true;
            }
        }
        if (heights.isEmpty() && info.getHeight() > 0) heights.add(bestH = info.getHeight());
        if (!heights.isEmpty()) {
            p.video.add(new Quality("Best available", bestH + "p" + (bestFps > 30 ? bestFps : "") + " · as uploaded", 0, ""));
            for (int h : heights) p.video.add(new Quality(h + "p", "Up to " + h + "p", h, ""));
        } else if (formats == null || formats.isEmpty()) {
            p.video.add(new Quality("Best available", "As uploaded", 0, ""));
        }
        if (audio || !heights.isEmpty()) {
            p.audio.add(new Quality("Best available", "Original audio", 0, ""));
            p.audio.add(new Quality("M4A", "Plays everywhere", 0, "m4a"));
        }
        p.subsLabel = "if available";
        return p;
    }

    // ---- ffmpeg ------------------------------------------------------------------------------

    /** Runs the ffmpeg that ships with yt-dlp. */
    static void ffmpeg(Context c, List<String> args) throws Exception {
        ytdl(c);
        File bin = new File(c.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        File packages = new File(c.getNoBackupFilesDir(), "youtubedl-android/packages");
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", new File(packages, "ffmpeg/usr/lib").getAbsolutePath() + ":"
                + new File(packages, "python/usr/lib").getAbsolutePath());
        env.put("TMPDIR", c.getCacheDir().getAbsolutePath());
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = Http.read(in, 64_000);
        }
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroy();
            throw new IOException("Joining video and audio took too long");
        }
        if (p.exitValue() != 0) {
            String last = out.trim();
            int nl = last.lastIndexOf('\n');
            throw new IOException("ffmpeg: " + (nl >= 0 ? last.substring(nl + 1) : last));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    static String nonEmpty(String s, String fallback) {
        return s == null || s.trim().isEmpty() || "null".equals(s) ? fallback : s.trim();
    }

    static String duration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static JSONObject spec(String engine, String page, boolean audio, int height, String fmt,
                           boolean thumb, boolean subs, String title, String artist) {
        JSONObject o = new JSONObject();
        try {
            o.put("e", engine);
            o.put("page", page);
            o.put("audio", audio);
            o.put("h", height);
            o.put("fmt", fmt == null ? "" : fmt);
            o.put("thumb", thumb);
            o.put("subs", subs);
            if (title != null) o.put("title", title);
            if (artist != null) o.put("artist", artist);
        } catch (Exception ignored) {
            // put() only throws for NaN numbers.
        }
        return o;
    }

    static Map<String, String> tags(String title, String artist) {
        Map<String, String> m = new LinkedHashMap<>();
        if (title != null && !title.isEmpty()) m.put("title", title);
        if (artist != null && !artist.isEmpty()) m.put("artist", artist);
        return m;
    }
}
