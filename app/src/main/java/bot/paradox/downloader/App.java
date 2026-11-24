package bot.paradox.downloader;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Material You colours from the wallpaper on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this);
        DownloadService.createChannels(this);
    }
}
