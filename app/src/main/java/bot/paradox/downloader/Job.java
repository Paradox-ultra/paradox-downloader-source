package bot.paradox.downloader;

import org.json.JSONException;
import org.json.JSONObject;

/** One download. Instances inside {@link JobStore} are only touched under its lock. */
final class Job {
    static final String QUEUED = "queued";
    static final String CONNECTING = "connecting";
    static final String DOWNLOADING = "downloading";
    static final String PAUSED = "paused";
    static final String FAILED = "failed";
    static final String DONE = "done";

    String id;
    String url;        // the link as resolved, re-probed on every start
    String source;     // what the user pasted, shown as "Copy link"
    String name;
    boolean nameFixed; // name came from the resolver (e.g. video title) and must not be replaced
    String cookie;
    String referer;
    String state = QUEUED;
    long done;
    long total = -1;
    long speed;
    boolean ranges;
    String error;
    String uri;        // saved file, once DONE
    String mime;
    long created;

    boolean active() {
        return QUEUED.equals(state) || CONNECTING.equals(state) || DOWNLOADING.equals(state);
    }

    Job copy() {
        Job j = new Job();
        j.id = id;
        j.url = url;
        j.source = source;
        j.name = name;
        j.nameFixed = nameFixed;
        j.cookie = cookie;
        j.referer = referer;
        j.state = state;
        j.done = done;
        j.total = total;
        j.speed = speed;
        j.ranges = ranges;
        j.error = error;
        j.uri = uri;
        j.mime = mime;
        j.created = created;
        return j;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("url", url);
        o.put("source", source);
        o.put("name", name);
        o.put("nameFixed", nameFixed);
        o.put("cookie", cookie);
        o.put("referer", referer);
        o.put("state", state);
        o.put("done", done);
        o.put("total", total);
        o.put("ranges", ranges);
        o.put("error", error);
        o.put("uri", uri);
        o.put("mime", mime);
        o.put("created", created);
        return o;
    }

    static Job fromJson(JSONObject o) {
        Job j = new Job();
        j.id = o.optString("id");
        j.url = str(o, "url");
        j.source = str(o, "source");
        j.name = str(o, "name");
        j.nameFixed = o.optBoolean("nameFixed");
        j.cookie = str(o, "cookie");
        j.referer = str(o, "referer");
        j.state = o.optString("state", QUEUED);
        j.done = o.optLong("done");
        j.total = o.optLong("total", -1);
        j.ranges = o.optBoolean("ranges");
        j.error = str(o, "error");
        j.uri = str(o, "uri");
        j.mime = str(o, "mime");
        j.created = o.optLong("created");
        return j;
    }

    private static String str(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k, null);
    }
}
