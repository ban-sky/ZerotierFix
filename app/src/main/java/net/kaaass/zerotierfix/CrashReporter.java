package net.kaaass.zerotierfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Persists the stack trace of an uncaught exception so the next launch can show
 * it to the user (this device has no adb access, so the report has to surface
 * inside the app itself).
 */
public final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static final String PREFS = "crash_reporter";
    private static final String KEY = "last_crash";
    private static final int MAX_LENGTH = 60000;

    private CrashReporter() {
    }

    public static void install(Context context) {
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                save(appContext, throwable);
            } catch (Throwable t) {
                Log.e(TAG, "Unable to persist crash report", t);
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    public static void save(Context context, Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        String text = writer.toString();
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH) + "\n...(truncated)";
        }
        save(context, text);
    }

    public static void save(Context context, String text) {
        prefs(context).edit().putString(KEY, text).commit();
    }

    public static boolean hasReport(Context context) {
        return prefs(context).contains(KEY);
    }

    public static String readReport(Context context) {
        return prefs(context).getString(KEY, "");
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
