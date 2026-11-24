package bot.paradox.downloader;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings. */
final class Prefs {
    static final int[] CONNECTION_CHOICES = {1, 4, 8, 12, 16};
    static final int[] PARALLEL_CHOICES = {1, 2, 3, 4};

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static int connections(Context c) {
        return sp(c).getInt("connections", 8);
    }

    static void connections(Context c, int v) {
        sp(c).edit().putInt("connections", v).apply();
    }

    static int parallel(Context c) {
        return sp(c).getInt("parallel", 2);
    }

    static void parallel(Context c, int v) {
        sp(c).edit().putInt("parallel", v).apply();
    }

    static boolean flag(Context c, String key) {
        return sp(c).getBoolean(key, false);
    }

    static void flag(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
    }
}
