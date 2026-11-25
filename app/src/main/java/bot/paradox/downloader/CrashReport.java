package bot.paradox.downloader;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves the stack trace of a crash to a private file so the next launch can show it
 * (see {@link CrashActivity}). Nothing is sent anywhere.
 */
final class CrashReport {
    private static final String FILE = "last-crash.txt";
    /** A stack overflow prints about a thousand frames; this keeps the report copyable. */
    private static final int MAX_CHARS = 20_000;

    private CrashReport() {}

    /** Call once, first thing in the application class. */
    static void install(Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(app, thread, error);
            } catch (Throwable ignored) {
                // Reporting must never hide the real crash.
            }
            // Let Android finish the crash as usual (logcat, closing the process).
            if (next != null) next.uncaughtException(thread, error);
        });
    }

    static boolean pending(Context c) {
        return file(c).length() > 0;
    }

    static String read(Context c) {
        try {
            return new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "The crash report couldn't be read: " + e.getMessage();
        }
    }

    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    private static void save(Context c, Thread thread, Throwable error) throws IOException {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String body = trace.toString();
        if (body.length() > MAX_CHARS) {
            // Keep both ends: the top says what failed, the bottom ("Caused by") says why.
            int half = MAX_CHARS / 2;
            body = body.substring(0, half) + "\n… shortened …\n" + body.substring(body.length() - half);
        }
        String report = "Downloader Ultra " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · " + Build.SUPPORTED_ABIS[0] + "\n"
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date())
                + " · thread " + thread.getName() + "\n\n" + body;
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
}
