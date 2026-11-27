package th.car.display;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Fetches a newer copy of the app and hands it to the system installer.
 *
 * The head unit's old browser keeps serving a remembered copy of the download, so "install the
 * latest" through it can silently install the same version again. This asks for the file itself,
 * with the wanted version in the address so nothing on the way can answer with an older one.
 */
final class Updater {

    private static final String BASE = "https://annonymouse456.github.io/affiliate-factory/car/";
    private static final String APK = "application/vnd.android.package-archive";

    interface Say { void line(String text); }

    private static int installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Throwable e) { return 0; }
    }

    /** The newest published build number, or 0 when it cannot be read. Uses the network; not for the main thread. */
    private static int published() {
        try {
            Http.Reply r = Http.send("GET", BASE + "version.txt?t=" + System.currentTimeMillis(), null, null, null, 12000);
            return Integer.parseInt(new String(r.body, "UTF-8").trim().split("\\s+")[0]);
        } catch (Throwable e) { return 0; }
    }

    /** Quietly look for a newer build and mention it if there is one. */
    static void peek(final Context c, final Say say) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() { @Override public void run() {
            final int newest = published();
            if (newest > installed(c)) ui.post(new Runnable() { @Override public void run() { say.line("มีแอปรุ่นใหม่กว่าที่ลงอยู่ กดปุ่ม อัปเดต ด้านล่างเพื่อติดตั้ง"); } });
        } }).start();
    }

    /** Look for a newer build; if there is one, download it and open the installer. */
    static void run(final Context c, final Say say) {
        final Context app = c.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        say.line("อัปเดต: กำลังตรวจหารุ่นใหม่…");
        new Thread(new Runnable() { @Override public void run() {
            final int newest = published(), mine = installed(app);
            ui.post(new Runnable() { @Override public void run() {
                if (newest == 0) { say.line("อัปเดต: ตรวจไม่ได้ ตรวจอินเทอร์เน็ตของจอรถ"); return; }
                if (newest <= mine) { say.line("อัปเดต: แอปที่ลงอยู่เป็นรุ่นล่าสุดแล้ว"); return; }
                try {
                    final DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Request q = new DownloadManager.Request(Uri.parse(BASE + "app.apk?v=" + newest));
                    q.setMimeType(APK);
                    q.setTitle("จอขับขี่ รุ่นใหม่");
                    q.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    q.setDestinationInExternalFilesDir(app, null, "update-" + newest + ".apk");
                    final long id = dm.enqueue(q);
                    say.line("อัปเดต: กำลังโหลดรุ่นใหม่ เสร็จแล้วหน้าติดตั้งจะขึ้นเอง");
                    app.registerReceiver(new BroadcastReceiver() {
                        @Override public void onReceive(Context ctx, Intent i) {
                            if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                            try { app.unregisterReceiver(this); } catch (Throwable ignored) { }
                            try {
                                Uri file = dm.getUriForDownloadedFile(id);
                                if (file == null) { say.line("อัปเดต: โหลดไม่สำเร็จ ลองอีกครั้ง"); return; }
                                app.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(file, APK)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                                say.line("อัปเดต: โหลดเสร็จ กด ติดตั้ง ในหน้าที่ขึ้นมา (ถ้าจอถามเรื่องแหล่งที่ไม่รู้จัก ให้อนุญาตแอปนี้)");
                            } catch (Throwable e) { say.line("อัปเดต: เปิดหน้าติดตั้งไม่ได้ (" + e.getClass().getSimpleName() + ") ใช้ลิงก์ดาวน์โหลดเดิมแทน"); }
                        }
                    }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                } catch (Throwable e) { say.line("อัปเดต: เริ่มโหลดไม่ได้ (" + e.getClass().getSimpleName() + ") ใช้ลิงก์ดาวน์โหลดเดิมแทน"); }
            } });
        } }).start();
    }
}
