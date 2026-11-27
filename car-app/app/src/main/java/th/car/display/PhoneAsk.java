package th.car.display;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * Lets a phone on the same Wi-Fi (the hotspot) put a question to the co-pilot, so the phone's
 * microphone or keyboard can be used instead of the head unit's microphone.
 *
 *   GET  /?c=CODE          a small page with a question box (the phone keyboard's dictation works in it)
 *   POST /ask  c=CODE&q=…   asks the question; the car speaks the answer and the reply body carries it as text
 *
 * The POST form is what an iOS Shortcut or any other automation can call. Runs while the display
 * overlay runs. Plain HTTP on the local network, guarded by the four-digit code.
 */
final class PhoneAsk {

    static final int PORT = 8766;

    private volatile ServerSocket server;
    private final String code;
    private final Context app;

    PhoneAsk(Context c, String code) { this.app = c.getApplicationContext(); this.code = code; }

    void start() {
        try {
            final ServerSocket s = new ServerSocket(PORT);
            server = s;
            new Thread(new Runnable() { @Override public void run() {
                while (server == s) {
                    try {
                        final Socket client = s.accept();
                        new Thread(new Runnable() { @Override public void run() {       // one thread per request: an answer can take a while
                            try { serve(client); } catch (Throwable ignored) { } finally { try { client.close(); } catch (Throwable ignored) { } }
                        } }).start();
                    } catch (Throwable e) { if (server != s) break; }
                }
            } }, "phone-ask").start();
        } catch (Throwable ignored) { }      // port busy: the feature is simply unavailable this run
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        try { if (s != null) s.close(); } catch (Throwable ignored) { }
    }

    private static String field(String form, String name) throws Exception {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
        }
        return "";
    }

    private void serve(Socket client) throws Exception {
        client.setSoTimeout(10000);
        InputStream in = client.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4 && head.size() < 16000) {
            int b = in.read();
            if (b < 0) break;
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String headers = head.toString("ISO-8859-1");
        String first = headers.split("\r\n")[0];
        String path = first.split(" ").length > 1 ? first.split(" ")[1] : "/";
        int length = 0;
        for (String line : headers.split("\r\n")) if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        String form = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        if (first.startsWith("POST") && length > 0 && length < 20000) {
            byte[] body = new byte[length];
            int got = 0;
            while (got < length) { int n = in.read(body, got, length - got); if (n < 0) break; got += n; }
            form = new String(body, 0, got, "UTF-8");
        }
        boolean allowed = code.equals(field(form, "c"));
        String question = field(form, "q");
        String link = field(form, "link");
        String sent = allowed && !link.isEmpty() ? sendPlace(link, !field(form, "stop").isEmpty()) : "";

        if (path.startsWith("/ask")) {
            String answer = !allowed ? "รหัสไม่ถูกต้อง" : question.isEmpty() ? "ไม่มีคำถาม" : Assistant.get().askAndWait(question, 100000);
            reply(client, "text/plain; charset=utf-8", answer);
            return;
        }
        String answer = allowed && !question.isEmpty() ? Assistant.get().askAndWait(question, 100000) : "";
        String page = "<!doctype html><html lang=th><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>ถามรถ</title>"
                + "<style>body{font:17px/1.5 -apple-system,sans-serif;margin:0;padding:20px;background:#0c1114;color:#f1f5f6}h1{font-size:21px;margin:0 0 6px}p{color:#b4c1c4;margin:0 0 14px}"
                + "textarea,input{width:100%;box-sizing:border-box;font:18px/1.4 inherit;padding:12px;border-radius:12px;border:1px solid #445;background:#151c20;color:#fff}textarea{height:110px}"
                + "label{display:block;margin:12px 0 5px}button{margin-top:16px;width:100%;padding:16px;font-size:19px;border:0;border-radius:12px;background:#3a93ff;color:#fff}.a{padding:14px;border-radius:12px;background:#1d2a33;margin:0 0 16px}</style>"
                + "<h1>ส่งสถานที่ไปที่แผนที่รถ</h1><p>ใน Google Maps บนมือถือ: เปิดสถานที่ แตะ แชร์ แล้วแตะ คัดลอก จากนั้นกลับมาวางในช่องนี้</p>"
                + (sent.isEmpty() ? "" : "<div class=a>" + sent.replace("&", "&amp;").replace("<", "&lt;") + "</div>")
                + "<form method=post action='/'>" + (allowed ? "<input type=hidden name=c value='" + code + "'>" : "<label>รหัส 4 หลักจากเมนูบนจอรถ</label><input name=c inputmode=numeric maxlength=4 required>")
                + "<label>ลิงก์หรือข้อความที่คัดลอกมา</label><textarea name=link required style='height:90px'></textarea>"
                + "<label style='margin-top:14px'><input type=checkbox name=stop value=1 style='width:auto;margin-right:8px'>เพิ่มเป็นจุดแวะ (ไปที่นี่ก่อน แล้วไปจุดหมายเดิมต่อ)</label><button>ส่งไปที่แผนที่รถ</button></form>"
                + "<h1 style='margin-top:34px'>ถามรถ</h1><p>พิมพ์ หรือแตะไมค์บนแป้นพิมพ์แล้วพูด รถจะตอบเป็นเสียง</p>"
                + (answer.isEmpty() ? "" : "<div class=a>" + answer.replace("&", "&amp;").replace("<", "&lt;") + "</div>")
                + "<form method=post action='/'>" + (allowed ? "<input type=hidden name=c value='" + code + "'>" : "<label>รหัส 4 หลักจากเมนูบนจอรถ</label><input name=c inputmode=numeric maxlength=4 required>")
                + "<label>คำถาม</label><textarea name=q required></textarea><button>ถาม</button></form></html>";
        reply(client, "text/html; charset=utf-8", page);
    }

    /** A place shared from a maps app on the phone: find where it is and start guiding there on the car's map. Returns words for the phone. */
    private String sendPlace(String shared, boolean asStop) {
        try {
            PlaceLink.Place p = PlaceLink.resolve(shared);
            String name = p.name.replace("|", " ").trim();
            String ask = p.exact ? String.format(java.util.Locale.US, "%.6f,%.6f", p.lat, p.lon) + (name.isEmpty() ? "" : "|" + name) : name;
            String answer = Assistant.get().guideTo((asStop ? "+" : "") + ask);
            if (answer == null) return "แผนที่บนจอรถยังไม่ทำงาน เปิดจอขับขี่ให้แสดงแผนที่ก่อน แล้วส่งอีกครั้ง";
            org.json.JSONObject j = new org.json.JSONObject(answer);
            if (j.optBoolean("ok")) return (j.optBoolean("stop") ? "เพิ่มจุดแวะแล้ว: " + j.optString("name") + " แล้วไปต่อ " + j.optString("then") + "  รวมระยะ " : "ส่งแล้ว: " + j.optString("name") + "  ระยะ ") + j.optDouble("km") + " กม. ประมาณ " + j.optInt("minutes") + " นาที"
                    + (p.exact ? "" : "  (ลิงก์ไม่มีพิกัด จึงค้นหาจากชื่อ ตรวจบนจอว่าถูกที่)");
            String why = j.optString("why");
            return why.contains("gps") ? "จอรถยังไม่มีสัญญาณ GPS จึงหาเส้นทางไม่ได้ รอสักครู่แล้วส่งอีกครั้ง"
                    : why.startsWith("place") ? "ค้นหาสถานที่นี้บนแผนที่รถไม่พบ: " + name : "หาเส้นทางไม่ได้ ตรวจอินเทอร์เน็ตของจอรถ";
        } catch (IllegalStateException e) {
            return e.getMessage();
        } catch (Throwable e) {
            return "เปิดลิงก์ไม่ได้: " + Http.problem(e);
        }
    }

    private static void reply(Socket client, String type, String text) throws Exception {
        byte[] out = text.getBytes("UTF-8");
        OutputStream os = client.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + out.length + "\r\n\r\n").getBytes("ISO-8859-1"));
        os.write(out);
        os.flush();
    }
}
