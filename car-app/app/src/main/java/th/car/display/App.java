package th.car.display;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

/** Process-wide pieces: crash capture, saved settings, and finding the head unit's dashcam app. */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                try {             // saved and shown at the next start, so a photo of the screen explains the crash
                    StringWriter text = new StringWriter();
                    error.printStackTrace(new PrintWriter(text));
                    String s = text.toString();
                    prefs(App.this).edit().putString("crash", s.length() > 1800 ? s.substring(0, 1800) : s).commit();
                } catch (Throwable ignored) { }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("car", Context.MODE_PRIVATE); }

    /** The head unit's own dashcam app, found by its name on the launcher; null when there is none. */
    static Intent findCameraApp(Context c) {
        try {
            Intent launchers = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo app : c.getPackageManager().queryIntentActivities(launchers, 0)) {
                if (app.activityInfo == null || c.getPackageName().equals(app.activityInfo.packageName)) continue;
                String label = String.valueOf(app.loadLabel(c.getPackageManager())).toLowerCase(Locale.US);
                String pkg = app.activityInfo.packageName.toLowerCase(Locale.US);
                if (label.contains("traffic") || label.contains("dvr") || label.contains("行车") || pkg.contains("dvr") || pkg.contains("carrecord")) {
                    return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .setComponent(new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
