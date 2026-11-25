package th.car.display;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Small HTTPS helper shared by the map, Claude and speech calls. Blocking: call it off the main thread. */
final class Http {

    static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Reply {
        int code;
        byte[] body = new byte[0];
        String text() { return new String(body, UTF8); }
        boolean ok() { return code >= 200 && code < 300; }
    }

    /** headers: name, value, name, value … */
    static Reply send(String method, String url, String[] headers, String contentType, byte[] body, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(readTimeoutMs);
            for (int i = 0; headers != null && i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream out = c.getOutputStream();
                out.write(body);
                out.close();
            }
            Reply r = new Reply();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) { bytes.write(buffer, 0, n); if (bytes.size() > 8000000) break; }
                r.body = bytes.toByteArray();
                in.close();
            }
            return r;
        } finally { c.disconnect(); }
    }

    private Http() { }
}
