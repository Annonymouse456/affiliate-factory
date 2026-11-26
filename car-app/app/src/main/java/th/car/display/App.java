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

    /** Megabytes free on the head unit's internal storage, or -1 when it cannot be read. */
    static long freeMegabytes(Context c) {
        try { return new android.os.StatFs(android.os.Environment.getDataDirectory().getPath()).getAvailableBytes() / (1024 * 1024); } catch (Throwable e) { return -1; }
    }

    /**
     * Why the app cannot save, found by actually writing a small file: storage full and a broken
     * app folder look the same from outside but need different fixes. Returns null when saving works.
     */
    static String whyCannotSave(Context c) {
        java.io.File dir = c.getFilesDir();
        try {
            if (dir == null) return "ระบบไม่ให้โฟลเดอร์ข้อมูลแอป";
            if (!dir.exists() && !dir.mkdirs()) return "สร้างโฟลเดอร์ข้อมูลแอปไม่ได้ (" + dir.getPath() + ")";
            java.io.File probe = new java.io.File(dir, "probe.tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(probe);
            out.write(new byte[4096]);
            out.getFD().sync();
            out.close();
            probe.delete();
            return null;
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("ENOSPC") || m.contains("No space")) return "พื้นที่เก็บข้อมูลของจอเต็ม";
            if (m.contains("EACCES") || m.contains("Permission")) return "โฟลเดอร์ข้อมูลแอปเสีย (ไม่มีสิทธิ์เขียน) ต้องถอนการติดตั้งแอปแล้วลงใหม่";
            return "เขียนไฟล์ไม่ได้: " + (m.length() > 90 ? m.substring(0, 90) : m);
        }
    }

    /**
     * When the app was first installed and last updated. If the two match after an update, the head
     * unit replaced the app instead of updating it, which also erases its saved settings and keys.
     */
    static String installHistory(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM HH:mm", Locale.US);
            return "ติดตั้งครั้งแรก " + f.format(new java.util.Date(p.firstInstallTime)) + " · อัปเดตล่าสุด " + f.format(new java.util.Date(p.lastUpdateTime))
                    + (Math.abs(p.firstInstallTime - p.lastUpdateTime) < 5000 ? " (จอนี้ลงแอปใหม่แทนการอัปเดต ข้อมูลเดิมจึงหาย)" : " (อัปเดตทับ ข้อมูลเดิมควรอยู่)");
        } catch (Throwable e) { return "อ่านประวัติการติดตั้งไม่ได้"; }
    }

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
