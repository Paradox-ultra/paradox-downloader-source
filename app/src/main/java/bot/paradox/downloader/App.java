package bot.paradox.downloader;

import android.app.Application;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // The black-and-white brand theme is used on every Android version
        // (no wallpaper colours), so the app looks the same everywhere.
        Prefs.applyNightMode(this);
        DownloadService.createChannels(this);
    }
}
