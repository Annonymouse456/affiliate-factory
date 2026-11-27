package th.car.display;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * The map panel: a web view in its own floating window, placed by the dashboard over the space it
 * leaves for the map.
 *
 * It runs in a separate process on purpose. A head unit's built-in web engine can be old or broken,
 * and when it fails it takes its whole process with it; kept apart, the worst case is an empty map
 * space while the speed, music and co-pilot carry on. The dashboard talks to it with small messages.
 */
public class MapService extends Service {

    static final int PLACE = 1, CONFIG = 2, FIX = 3, GO = 4, CLEAR = 5;      // dashboard -> map
    static final int RESULT = 10, STATUS = 11, SPEAK = 12;                                 // map -> dashboard: the page's answer as JSON text; a line about how the map is doing

    /** The full 3D map, as published. Fetched fresh each time, so changes made to it appear without a new app. */
    static final String FULL_MAP = "https://annonymouse456.github.io/affiliate-factory/map/";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private FrameLayout frame;
    private WebView web;
    private boolean shown, ready;
    private boolean want3d = true, is3d;    // asked for the full 3D map; showing the published 3D page now
    private boolean lite3d;                 // showing the built-in tilted map instead: the 3D look for a web engine too old for the published page
    private String loaded = "", why3d = ""; // which page is in the view; why the 3D map could not be used, shown on the light map
    private WindowManager.LayoutParams lastAt;
    private Messenger dashboard;
    private String key = "", colour = "#35c8ff";
    private int look;                       // 0: day or night colours by the clock; 2: night colours now
    private float shade;                    // how dark a veil to lay over the map, 0 to 0.5
    private double lat, lon;
    private float bearing = -1, kmh = -1;
    private boolean haveFix;
    private FilePrefs kept;                 // which way up the map is drawn, remembered between drives

    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            if (m.replyTo != null) dashboard = m.replyTo;
            Bundle b = m.getData();
            try {
                if (m.what == PLACE) place(b.getInt("x"), b.getInt("y"), b.getInt("w"), b.getInt("h"), b.getBoolean("visible"), b.getFloat("radius"));
                else if (m.what == CONFIG) {
                    boolean keyChanged = !key.equals(b.getString("key", "")), modeChanged = want3d != b.getBoolean("full", true);
                    key = b.getString("key", ""); colour = b.getString("colour", colour); want3d = b.getBoolean("full", true);
                    look = b.getInt("look", 0); shade = b.getFloat("shade", 0f);
                    if (modeChanged) why3d = "";              // asked again by hand: try the 3D map afresh
                    if (web != null && (modeChanged || (is3d && keyChanged))) load(); else config();
                }
                else if (m.what == FIX) { lat = b.getDouble("lat"); lon = b.getDouble("lon"); bearing = b.getFloat("bearing"); kmh = b.getFloat("kmh"); haveFix = true; fix(); }
                else if (m.what == GO) run("car.go(" + JSONObject.quote(b.getString("query", "")) + ")");
                else if (m.what == CLEAR) run("car.clear()");
            } catch (Throwable ignored) { }
        }
    });

    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }

    @Override public void onCreate() {
        super.onCreate();
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) kept = new FilePrefs(new File(dir, "map.json"));
        } catch (Throwable ignored) { }
    }

    @Override public void onDestroy() {
        try { if (shown) windows.removeView(frame); } catch (Throwable ignored) { }
        try { if (web != null) web.destroy(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void build(final float radius) {
        frame = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable ground = new android.graphics.drawable.GradientDrawable();
        ground.setColor(0xFF0B0F17);
        ground.setCornerRadius(radius);
        frame.setBackground(ground);
        if (radius > 0) {
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
            });
            frame.setClipToOutline(true);
        }
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0F17);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowUniversalAccessFromFileURLs(true);      // the page is packed inside the app and fetches routes from map services
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void result(final String json) {
                ui.post(new Runnable() { @Override public void run() {
                    if (json != null && json.contains("\"ready\"")) {
                        ready = true; config(); fix();
                        if (is3d) tell("3D เปิดได้ (" + engine() + ")"); else if (lite3d) tell("3D แบบในแอป เปิดได้ (" + engine() + ")"); else if (!why3d.isEmpty()) MapService.this.run("car.note(" + JSONObject.quote(why3d) + ")");
                        return;
                    }
                    try {
                        if (dashboard == null) return;
                        Message m = Message.obtain(null, RESULT);
                        Bundle b = new Bundle();
                        b.putString("json", json);
                        m.setData(b);
                        dashboard.send(m);
                    } catch (Throwable ignored) { }
                } });
            }
            @JavascriptInterface public void fail(final String reason) {           // the 3D page could not start in this web engine
                ui.post(new Runnable() { @Override public void run() {
                    if (!is3d && !lite3d) return;
                    why3d = "แผนที่ 3D เปิดไม่ได้บนจอนี้ ใช้แบบ 2D แทน (" + engine() + ": " + reason + ")";
                    tell(why3d);
                    MapService.this.load();
                } });
            }
            @JavascriptInterface public void status(final String line) { ui.post(new Runnable() { @Override public void run() { tell("3D " + line); } }); }
            @JavascriptInterface public void save(String name, String value) {
                try {
                    if (kept != null && "headingUp".equals(name)) kept.edit().putBoolean("headingUp", "1".equals(value)).apply();
                    else if (kept != null && "voice".equals(name)) kept.edit().putBoolean("voice", "1".equals(value)).apply();
                    else if (kept != null && "saved".equals(name)) kept.edit().putString("saved", value).apply();      // home, work, favourites, the trip under way, today's usage
                } catch (Throwable ignored) { }
            }
            @JavascriptInterface public String load(String name) {
                try { return kept != null && "saved".equals(name) ? kept.getString("saved", "") : ""; } catch (Throwable e) { return ""; }
            }
            @JavascriptInterface public void speak(final String text) {          // a turn instruction to be said aloud; the dashboard's process owns the voice
                ui.post(new Runnable() { @Override public void run() {
                    try {
                        if (dashboard == null) return;
                        Message m = Message.obtain(null, SPEAK);
                        Bundle b = new Bundle();
                        b.putString("text", text);
                        m.setData(b);
                        dashboard.send(m);
                    } catch (Throwable ignored) { }
                } });
            }
        }, "Host");
        web.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                if (!request.isForMainFrame() || !FULL_MAP.equals(request.getUrl().toString())) return null;
                return fullMapPage();
            }
        });
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        load();
    }

    /** "Chrome 63" and the like: which web engine this head unit has. */
    private String engine() {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(web.getSettings().getUserAgentString());
            if (m.find()) return "Chrome " + m.group(1);
        } catch (Throwable ignored) { }
        return "ไม่ทราบรุ่น";
    }

    private int engineVersion() { try { return Integer.parseInt(engine().replace("Chrome ", "")); } catch (Throwable e) { return 0; } }

    /** Put the wanted map in the view: the full 3D one when asked for and not already found unusable, else the light one packed in the app. */
    private void load() {
        ready = false;
        boolean full = want3d && why3d.isEmpty();
        // The published 3D page is written in a newer form of JavaScript than an old engine reads; such a unit gets the tilted map packed in the app.
        boolean old = engineVersion() > 0 && engineVersion() < 80;
        is3d = full && !old;
        lite3d = full && old;
        web.loadUrl(is3d ? FULL_MAP : lite3d ? "file:///android_asset/map/index.html#3d" : "file:///android_asset/map/index.html");
        if (shown && lastAt != null) { focus(lastAt); try { windows.updateViewLayout(frame, lastAt); } catch (Throwable ignored) { } }
    }

    /** The 3D map has a search box, so its window must take the keyboard; the light map must not take anything from the app underneath. */
    private void focus(WindowManager.LayoutParams at) {
        int plain = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        at.flags = plain | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;       // both maps have a search box now, so both take the keyboard
        at.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
    }

    private String asset(String name) throws java.io.IOException {
        java.io.InputStream in = getAssets().open(name);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { in.close(); }
    }

    /** The published 3D page with two small scripts of ours added: one before its own code, one after. Runs on the web view's loading thread. */
    private android.webkit.WebResourceResponse fullMapPage() {
        String page;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(FULL_MAP).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setUseCaches(false);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            page = out.toString("UTF-8");
            int head = page.indexOf("<head>"), end = page.lastIndexOf("</body>");
            if (head < 0 || end < 0) throw new java.io.IOException("unexpected page");
            page = page.substring(0, head + 6) + "<script>window.__key=" + JSONObject.quote(key) + ";\n" + asset("map/bridge-head.js") + "</script>"
                    + page.substring(head + 6, end) + "<script>\n" + asset("map/bridge-tail.js") + "</script>" + page.substring(end);
        } catch (Throwable e) {
            page = "<script>Host.fail(" + JSONObject.quote("โหลดหน้าแผนที่ไม่ได้: " + Http.problem(e)) + ")</script>";
        }
        try { return new android.webkit.WebResourceResponse("text/html", "utf-8", new java.io.ByteArrayInputStream(page.getBytes("UTF-8"))); }
        catch (java.io.UnsupportedEncodingException e) { return null; }
    }

    private void tell(String line) {
        try {
            if (dashboard == null) return;
            Message m = Message.obtain(null, STATUS);
            Bundle b = new Bundle();
            b.putString("line", line);
            m.setData(b);
            dashboard.send(m);
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void place(int x, int y, int w, int h, boolean visible, float radius) {
        if (!visible) {
            if (shown) { windows.removeView(frame); shown = false; }
            return;
        }
        if (frame == null) build(radius);
        WindowManager.LayoutParams at = new WindowManager.LayoutParams(w, h,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        at.gravity = Gravity.TOP | Gravity.START;
        at.x = x;
        at.y = y;
        focus(at);
        lastAt = at;
        if (shown) windows.updateViewLayout(frame, at); else windows.addView(frame, at);
        shown = true;
    }

    private void run(String script) {
        if (!ready || web == null) return;
        if (is3d) script = script.replace("car.", "__dash.");       // the 3D page is driven through our bridge script
        web.evaluateJavascript(script, null);
    }

    private void config() {
        boolean up = kept == null || kept.getBoolean("headingUp", true);
        run("car.config(" + JSONObject.quote(key) + "," + JSONObject.quote(colour) + "," + (up ? 1 : 0) + "," + look + "," + String.format(Locale.US, "%.2f", shade)
                + "," + (kept == null || kept.getBoolean("voice", true) ? 1 : 0) + ")");
    }

    private void fix() { if (haveFix) run(String.format(Locale.US, "car.fix(%.6f,%.6f,%.1f,%.1f)", lat, lon, bearing, kmh)); }
}
