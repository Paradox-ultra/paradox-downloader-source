package bot.paradox.downloader;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

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

    // ---- look ----------------------------------------------------------------------------

    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2, THEME_AMOLED = 3;
    static final String[] THEME_NAMES = {"Follow system", "Light", "Dark", "AMOLED black"};

    static int theme(Context c) {
        int t = sp(c).getInt("theme", THEME_SYSTEM);
        return t < 0 || t >= THEME_NAMES.length ? THEME_SYSTEM : t;
    }

    static void theme(Context c, int v) {
        sp(c).edit().putInt("theme", v).apply();
    }

    /** Light/dark for the whole app; call once at start and after the setting changes. */
    static void applyNightMode(Context c) {
        int t = theme(c);
        AppCompatDelegate.setDefaultNightMode(
                t == THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                        : t == THEME_DARK || t == THEME_AMOLED ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** Pure-black surfaces on top of the dark palette; call before setContentView. */
    static void applyAmoled(AppCompatActivity a) {
        if (theme(a) == THEME_AMOLED) a.getTheme().applyStyle(R.style.ThemeOverlay_Ultra_Amoled, true);
    }

    static boolean flag(Context c, String key) {
        return sp(c).getBoolean(key, false);
    }

    static void flag(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
    }
}
