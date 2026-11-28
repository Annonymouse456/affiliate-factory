package th.car.display;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches a newer copy of the app and hands it to the system installer.
 *
 * The head unit's old browser keeps serving a remembered copy of the download, so "install the
 * latest" through it can silently install the same version again. This asks for the file itself,
 * with the wanted version in the address so nothing on the way can answer with an older one.
 * Every step says what it is doing, on screen: a silent key looks like a broken one.
 */
final class Updater {

    private static final String BASE = "https://annonymouse456.github.io/affiliate-factory/car/";
    private static final String APK = "application/vnd.android.package-archive";
    private static volatile boolean working;

    interface Say { void line(String text); }

    private static int installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Throwable e) { return 0; }
    }

    /** The newest published build: { number, name }, or null when it cannot be read. Uses the network; not for the main thread. */
    private static String[] published() {
        try {
            Http.Reply r = Http.send("GET", BASE + "version.txt?t=" + System.currentTimeMillis(), null, null, null, 12000);
            String[] parts = new String(r.body, "UTF-8").trim().split("\\s+");
            Integer.parseInt(parts[0]);
            return new String[]{parts[0], parts.length > 1 ? parts[1] : parts[0]};
        } catch (Throwable e) { return null; }
    }

    /** Quietly look for a newer build and mention it if there is one. */
    static void peek(final Context c, final Say say) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() { @Override public void run() {
            final String[] newest = published();
            if (newest != null && Integer.parseInt(newest[0]) > installed(c))
                ui.post(new Runnable() { @Override public void run() { say.line("มีแอปรุ่นใหม่ " + newest[1] + "\nกดปุ่ม อัปเดต ด้านล่างเพื่อติดตั้ง"); } });
        } }).start();
    }

    /** Look for a newer build; if there is one, download it and open the installer. Every step is reported through say, on the main thread. */
    static void run(Context c, final Say out) {
        final Context app = c.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        final Say say = new Say() { @Override public void line(final String text) { ui.post(new Runnable() { @Override public void run() { out.line(text); } }); } };
        if (working) { say.line("อัปเดต: กำลังทำอยู่ รอสักครู่"); return; }
        working = true;
        say.line("อัปเดต: กำลังตรวจหารุ่นใหม่…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                String[] newest = published();
                if (newest == null) { say.line("อัปเดต: ตรวจไม่ได้\nตรวจว่าจอรถต่ออินเทอร์เน็ตอยู่ แล้วกดอีกครั้ง"); return; }
                int build = Integer.parseInt(newest[0]);
                if (build <= installed(app)) { say.line("อัปเดต: แอปที่ลงอยู่เป็นรุ่นล่าสุดแล้ว (" + newest[1] + ")"); return; }

                File dir = app.getExternalFilesDir(null);
                if (dir == null) throw new java.io.IOException("ไม่มีที่เก็บไฟล์");
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (f.getName().startsWith("update-")) f.delete();        // earlier downloads are of no more use
                File file = new File(dir, "update-" + build + ".apk");
                HttpURLConnection h = (HttpURLConnection) new URL(BASE + "app.apk?v=" + build).openConnection();
                h.setConnectTimeout(15000);
                h.setReadTimeout(30000);
                h.setUseCaches(false);
                if (h.getResponseCode() != 200) throw new java.io.IOException("เซิร์ฟเวอร์ตอบ " + h.getResponseCode());
                long total = h.getContentLength(), got = 0;
                int shown = -1;
                InputStream in = h.getInputStream();
                FileOutputStream to = new FileOutputStream(file);
                byte[] buf = new byte[32768];
                for (int n; (n = in.read(buf)) > 0; ) {
                    to.write(buf, 0, n);
                    got += n;
                    int percent = total > 0 ? (int) (got * 100 / total) : -1;
                    if (percent / 10 != shown / 10) { shown = percent; say.line("อัปเดต: กำลังโหลดรุ่น " + newest[1] + "  " + (percent >= 0 ? percent + "%" : (got / 1024) + " KB")); }
                }
                to.close();
                in.close();
                if (total > 0 && got != total) throw new java.io.IOException("โหลดไม่ครบ");
                final File ready = file;
                final String name = newest[1];
                ui.post(new Runnable() { @Override public void run() { install(app, ready, name, out); } });
            } catch (Throwable e) {
                say.line("อัปเดต: โหลดไม่สำเร็จ (" + Http.problem(e) + ")\nกดอีกครั้ง หรือใช้ลิงก์ดาวน์โหลดแทน");
            } finally { working = false; }
        } }).start();
    }

    /** Open the system installer on the downloaded file. Two ways are tried, since head units differ in which they allow. */
    private static void install(final Context app, File file, String name, final Say say) {
        String done = "อัปเดต: โหลดรุ่น " + name + " เสร็จแล้ว\nกด ติดตั้ง ในหน้าที่ขึ้นมา\n(ถ้าจอถามเรื่องแหล่งที่ไม่รู้จัก ให้อนุญาตแอปนี้ แล้วกดอัปเดตอีกครั้ง)";
        try {
            // A plain file address is refused by newer Android unless this check is relaxed; the file is in this app's own shared folder, which the installer can read.
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
            app.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), APK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            say.line(done);
            return;
        } catch (Throwable first) { /* fall through to the system download manager, which hands out an address the installer always accepts */ }
        try {
            final DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
            final long id = dm.addCompletedDownload("จอขับขี่ รุ่น " + name, "ไฟล์ติดตั้ง", false, APK, file.getAbsolutePath(), file.length(), true);
            Uri address = dm.getUriForDownloadedFile(id);
            if (address == null) throw new IllegalStateException("no address");
            app.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(address, APK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            say.line(done);
        } catch (Throwable second) {
            say.line("อัปเดต: โหลดเสร็จ แต่เปิดหน้าติดตั้งไม่ได้ (" + second.getClass().getSimpleName() + ")\nเปิดแอปจัดการไฟล์ของจอ ไปที่ Android/data/th.car.display/files แล้วแตะไฟล์ " + file.getName());
        }
    }
}
