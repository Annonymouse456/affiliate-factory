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
    static final int RESULT = 10;                                              // map -> dashboard: the page's answer, as JSON text

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private FrameLayout frame;
    private WebView web;
    private boolean shown, ready;
    private Messenger dashboard;
    private String key = "", colour = "#35c8ff";
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
                else if (m.what == CONFIG) { key = b.getString("key", ""); colour = b.getString("colour", colour); config(); }
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
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowUniversalAccessFromFileURLs(true);      // the page is packed inside the app and fetches routes from map services
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void result(final String json) {
                ui.post(new Runnable() { @Override public void run() {
                    if (json != null && json.contains("\"ready\"")) { ready = true; config(); fix(); return; }
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
            @JavascriptInterface public void save(String name, String value) {
                try { if (kept != null && "headingUp".equals(name)) kept.edit().putBoolean("headingUp", "1".equals(value)).apply(); } catch (Throwable ignored) { }
            }
        }, "Host");
        web.setWebViewClient(new WebViewClient());
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/map/index.html");
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
        if (shown) windows.updateViewLayout(frame, at); else windows.addView(frame, at);
        shown = true;
    }

    private void run(String script) { if (ready && web != null) web.evaluateJavascript(script, null); }

    private void config() {
        boolean up = kept == null || kept.getBoolean("headingUp", true);
        run("car.config(" + JSONObject.quote(key) + "," + JSONObject.quote(colour) + "," + (up ? 1 : 0) + ")");
    }

    private void fix() { if (haveFix) run(String.format(Locale.US, "car.fix(%.6f,%.6f,%.1f,%.1f)", lat, lon, bearing, kmh)); }
}
