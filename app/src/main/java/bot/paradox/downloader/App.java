package bot.paradox.downloader;

import android.app.Application;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashReport.install(this); // first, so a crash in anything below is recorded too
        // Keep up to 32 idle sockets so every download connection can be reused for its next piece
        // (the platform default of 5 would close most of them). Must be set before the first request.
        System.setProperty("http.maxConnections", "32");
        // The black-and-white brand theme is used on every Android version
        // (no wallpaper colours), so the app looks the same everywhere.
        Prefs.applyNightMode(this);
        DownloadService.createChannels(this);
    }
}
