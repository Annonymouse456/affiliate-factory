package th.car.display;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Random;

/**
 * Lets the driver send API keys from their phone instead of typing them on the car screen.
 *
 * While the settings dialog is open, the app serves one small web page on the local Wi-Fi (the
 * phone's hotspot). The phone opens it, the driver pastes the keys and the four-digit code shown on
 * the car screen, and the keys are saved here. Nothing goes over the internet. The page closes
 * after a successful save, after five minutes, or when the dialog is dismissed.
 */
final class KeyReceiver {

    static final int PORT = 8765;

    interface Done { void saved(String summary); }

    private volatile ServerSocket server;
    final String code = String.format("%04d", new Random().nextInt(10000));

    /** The head unit's address on the local network, or null when it is not on Wi-Fi. */
    static String localAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    void start(final Context c, final Done done) throws Exception {
        final ServerSocket s = new ServerSocket(PORT);
        server = s;
        final long until = System.currentTimeMillis() + 5 * 60 * 1000;
        new Thread(new Runnable() { @Override public void run() {
            try {
                s.setSoTimeout(2000);
                while (server == s && System.currentTimeMillis() < until) {
                    Socket client;
                    try { client = s.accept(); } catch (java.net.SocketTimeoutException e) { continue; }
                    try { if (serve(c, client, done)) break; } catch (Throwable ignored) { } finally { try { client.close(); } catch (Throwable ignored) { } }
                }
            } catch (Throwable ignored) { }
            stop();
        } }, "key-receiver").start();
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        try { if (s != null) s.close(); } catch (Throwable ignored) { }
    }

    /** Handles one request. Returns true once keys were saved. */
    private boolean serve(Context c, Socket client, Done done) throws Exception {
        client.setSoTimeout(8000);
        InputStream in = client.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4 && head.size() < 16000) {           // read up to the blank line that ends the headers
            int b = in.read();
            if (b < 0) break;
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String headers = head.toString("ISO-8859-1");
        boolean post = headers.startsWith("POST");
        int length = 0;
        for (String line : headers.split("\r\n")) if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        String message = "";
        boolean saved = false;
        if (post && length > 0 && length < 20000) {
            byte[] body = new byte[length];
            int got = 0;
            while (got < length) { int n = in.read(body, got, length - got); if (n < 0) break; got += n; }
            String pin = "", claude = "", openai = "";
            for (String pair : new String(body, 0, got, "UTF-8").split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8").replaceAll("\\s", "");
                String name = pair.substring(0, eq);
                if (name.equals("pin")) pin = value; else if (name.equals("claude")) claude = value; else if (name.equals("openai")) openai = value;
            }
            if (!code.equals(pin)) message = "รหัส 4 หลักไม่ตรงกับที่แสดงบนจอรถ";
            else if (claude.length() < 20 && openai.length() < 20) message = "ยังไม่ได้วางคีย์ หรือคีย์สั้นเกินไป";
            else {
                android.content.SharedPreferences.Editor e = App.prefs(c).edit();
                String summary = "";
                if (claude.length() >= 20) { Assistant.sessionClaudeKey = claude; e.putString("claudeKey", claude); summary += "คีย์ Claude (ลงท้าย " + claude.substring(claude.length() - 4) + ") "; }
                if (openai.length() >= 20) { Assistant.sessionOpenAiKey = openai; e.putString("openaiKey", openai); summary += "คีย์ OpenAI (ลงท้าย " + openai.substring(openai.length() - 4) + ")"; }
                if (e.commit()) {            // written to storage before we say so
                    saved = true;
                    message = "บันทึกลงจอรถแล้ว: " + summary.trim() + " ปิดหน้านี้ได้เลย";
                    done.saved(summary.trim());
                } else {                     // usable until the app closes; say exactly why it could not be kept
                    saved = true;
                    String why = App.whyCannotSave(c);
                    long free = App.freeMegabytes(c);
                    boolean spare = (claude.length() < 20 || App.saveSpareKey(c, "claudeKey", claude)) && (openai.length() < 20 || App.saveSpareKey(c, "openaiKey", openai));
                    String note = spare ? "บันทึกไว้ในที่เก็บสำรองของจอแล้ว จะอยู่ต่อหลังปิดแอป (ที่เก็บปกติใช้ไม่ได้: " + (why == null ? "ไม่ทราบสาเหตุ" : why) + ")"
                            : "ใช้ได้ชั่วคราวจนกว่าจะปิดแอป แต่จอรถบันทึกถาวรไม่ได้: " + (why == null ? "ไม่ทราบสาเหตุ" : why) + (free >= 0 ? " (พื้นที่ว่าง " + free + " MB)" : "");
                    message = "จอรถรับคีย์แล้ว " + note;
                    done.saved(summary.trim() + "\n" + note);
                }
            }
        }
        String page = "<!doctype html><html lang=th><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>ส่งคีย์เข้าจอรถ</title>"
                + "<style>body{font:17px/1.5 -apple-system,sans-serif;margin:0;padding:20px;background:#0c1114;color:#f1f5f6}h1{font-size:21px;margin:0 0 6px}p{color:#b4c1c4;margin:0 0 16px}"
                + "label{display:block;margin:14px 0 5px}input{width:100%;box-sizing:border-box;font:16px monospace;padding:12px;border-radius:10px;border:1px solid #445;background:#151c20;color:#fff}"
                + "button{margin-top:20px;width:100%;padding:15px;font-size:18px;border:0;border-radius:12px;background:#3a93ff;color:#fff}.m{padding:12px;border-radius:10px;background:#1d2a33;color:#fff;margin:0 0 16px}</style>"
                + "<h1>ส่งคีย์เข้าจอรถ</h1><p>คีย์จะถูกส่งจากมือถือไปที่จอรถโดยตรงผ่าน Wi-Fi นี้ ไม่ผ่านอินเทอร์เน็ต</p>"
                + (message.isEmpty() ? "" : "<div class=m>" + message + "</div>")
                + (saved ? "" : "<form method=post autocomplete=off><label>รหัส 4 หลักที่แสดงบนจอรถ</label><input name=pin inputmode=numeric maxlength=4 required>"
                + "<label>คีย์ Claude (เว้นว่างได้ถ้าไม่เปลี่ยน)</label><input name=claude autocapitalize=off autocorrect=off spellcheck=false placeholder='sk-ant-...'>"
                + "<label>คีย์ OpenAI (เว้นว่างได้ถ้าไม่เปลี่ยน)</label><input name=openai autocapitalize=off autocorrect=off spellcheck=false placeholder='sk-...'>"
                + "<button>ส่งเข้าจอรถ</button></form>")
                + "</html>";
        byte[] out = page.getBytes("UTF-8");
        OutputStream os = client.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + out.length + "\r\n\r\n").getBytes("ISO-8859-1"));
        os.write(out);
        os.flush();
        return saved;
    }
}
