package th.car.display;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * The driving display, drawn on top of the head unit's own dashcam app.
 *
 * The dashcam here is not a standard camera: only its own app can show the picture. So this service
 * never reads the camera. It floats over that app in one of two layouts:
 *
 *   Dashboard — covers the screen except for a window cut into it, through which the dashcam app
 *               shows. Big speed, the limit sign, the road name, the phone's music with its
 *               controls, and the co-pilot button. Tap the camera window to switch to:
 *   Camera    — the dashcam app fills the screen, with only a small strip (speed, limit, co-pilot,
 *               back) and the optional lane band over it.
 */
public class OverlayService extends Service {

    static volatile boolean running;

    private static final int[] LIMITS = {0, 60, 80, 90, 100, 110, 120};     // over-speed warning choices; 0 is off
    private static final int INK = 0xFFF1F5F6, INK_DIM = 0xFF9FB0B4, BASE = 0xF50B1014, PLATE = 0x1FFFFFFF, GLASS = 0xD90A0F12,
            BLUE = 0xFF3A93FF, SIGN_RED = 0xFFE5483D, LISTEN = 0xFFE5483D, THINK = 0xFFE0A93B, TALK = 0xFF46DBA0;
    /** Colour themes: three colours each, used for the dial, the orb, the borders and the glows. */
    private static final String[] THEME_NAMES = {"ฟ้าไซเบอร์", "ม่วงนีออน", "ส้มสปอร์ต", "เขียวมินต์"};
    private static final int[][] THEMES = {
            {0xFF22D3EE, 0xFF3A7BFF, 0xFF8B5CF6},
            {0xFFF472B6, 0xFFA855F7, 0xFF6366F1},
            {0xFFFBBF24, 0xFFF97316, 0xFFEF4444},
            {0xFF6EE7B7, 0xFF10B981, 0xFF0EA5E9}};
    /** The chosen theme: one of the presets, or three colours spread from a hue the driver picked. */
    private int[] theme() {
        int i = Math.abs(prefs.getInt("theme", 0)) % (THEMES.length + 1);
        if (i < THEMES.length) return THEMES[i];
        float hue = prefs.getInt("hue", 200);
        return new int[]{android.graphics.Color.HSVToColor(new float[]{hue % 360, 0.72f, 1f}), android.graphics.Color.HSVToColor(new float[]{(hue + 32) % 360, 0.80f, 1f}),
                android.graphics.Color.HSVToColor(new float[]{(hue + 64) % 360, 0.68f, 0.96f})};
    }
    private String themeName() { int i = Math.abs(prefs.getInt("theme", 0)) % (THEMES.length + 1); return i < THEMES.length ? THEME_NAMES[i] : "กำหนดเอง"; }

    private static final String[] COMPASS = {"เหนือ", "ตอ.เฉียงเหนือ", "ตะวันออก", "ตอ.เฉียงใต้", "ใต้", "ตต.เฉียงใต้", "ตะวันตก", "ตต.เฉียงเหนือ"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager windows;
    private int unit, screenW, screenH, limit;
    private boolean cameraMode, hidden;

    // dashboard layout
    private FrameLayout dash;
    private GaugeView gauge;
    private TextView clockD, dateD, noteD, titleD, artistD, aiLabel;
    private IconView playD;
    private OrbView orbD, orbC;
    private EqView eq;
    private DiscView disc;
    private TextView themeKey, voiceKey;
    private android.widget.SeekBar hueBar;
    private PhoneAsk phoneAsk;
    // the map panel lives in its own process; see MapService
    private RectF mapAt;
    private TextView mapNote;
    private android.os.Messenger mapOut;
    private boolean mapBound, mapSeen, mapGaveUp;
    private int mapDrops;
    private String mapKeySent;
    private final java.util.concurrent.LinkedBlockingQueue<String> mapAnswers = new java.util.concurrent.LinkedBlockingQueue<String>();
    private final android.os.Messenger mapIn = new android.os.Messenger(new Handler(android.os.Looper.getMainLooper()) {
        @Override public void handleMessage(android.os.Message m) {
            if (m.what == MapService.RESULT) mapAnswers.offer(String.valueOf(m.getData().getString("json")));
            else if (m.what == MapService.STATUS) {
                String line = String.valueOf(m.getData().getString("line"));
                NowPlaying.get().note("แผนที่: " + line);
                if (line.contains("แทน")) note(line);            // the 3D map could not be used: say why on the screen
                else if (line.contains("eased")) note("แผนที่ 3D วาดช้า ปิดตึก 3D ให้แล้ว ถ้ายังหน่วง แตะปุ่ม 3D มุมขวาบนเพื่อสลับเป็น 2D");
            }
        }
    });
    private final android.content.ServiceConnection mapLink = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, IBinder binder) {
            mapOut = new android.os.Messenger(binder);
            mapSeen = false;
            mapKeySent = null;
            syncMap();
            Location at = lastPlace;
            if (at != null) sendFix(at);
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) {      // the web engine failed and took the map's process with it
            mapOut = null;
            NowPlaying.get().note("แผนที่: ส่วนแสดงเว็บของจอหยุดทำงาน ครั้งที่ " + (++mapDrops));
            if (mapDrops >= 3) {             // stop trying: leave the space with a plain explanation
                mapGaveUp = true;
                try { unbindService(this); } catch (Throwable ignored) { }
                mapBound = false;
                if (mapNote != null) mapNote.setText("แผนที่เปิดไม่ได้บนจอนี้\nส่วนแสดงเว็บของจอหยุดทำงานซ้ำ ๆ");
            }
        }
    };
    private CallWatch calls;
    private LinearLayout callCard;
    private TextView callWho, callState, answerKey;
    private boolean callShown, ringing;
    private long callStarted;
    private TextView phoneKey;
    private LinearLayout settings;
    private TextView warnKey, bootKey, roadKey, briefKey, pathKey;
    // camera layout
    private LinearLayout strip, nudges;
    private TextView speedC, noteC;
    private PathView path;
    private boolean dashShown, stripShown;

    private LocationManager locations;
    private ToneGenerator tone;

    // live figures
    private float speedKmh = -1;
    private long lastFix;
    private Location lastPlace;
    private double tripMetres, briefedAtMetres = -1;
    private long movingMillis, lastFixClock;
    private float maxKmh;
    private String heading = "", roadName = "";
    private long lastTone;

    // road name and posted limit from the map
    private Location lookedUpAt;
    private long lookedUpClock, roadWay, manualWay = -1;
    private boolean lookingUp;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = App.prefs(this);
        limit = prefs.getInt("limit", 0);          // 0: no over-speed warning set
        startForeground(1, notice());
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        unit = Math.max(4, screenH / 100);
        try {
            path = new PathView(this);
            WindowManager.LayoutParams at = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layer(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            path.setVisibility(View.GONE);
            windows.addView(path, at);
            buildDashboard();
            buildStrip();
            labels();
            setCameraMode(prefs.getBoolean("cameraMode", false));
        } catch (Throwable e) {          // e.g. the draw-over-apps permission was withdrawn
            stopSelf();
            return;
        }
        startGps();
        Assistant.get().attach(this, car, aiListener);
        String code = prefs.getString("askCode", "");
        if (code.length() != 4) { code = String.format(Locale.US, "%04d", new java.util.Random().nextInt(10000)); prefs.edit().putString("askCode", code).apply(); }
        phoneAsk = new PhoneAsk(this, code);
        phoneAsk.start();
        try { mapBound = bindService(new Intent(this, MapService.class), mapLink, Context.BIND_AUTO_CREATE); } catch (Throwable ignored) { }
        buildCallCard();
        calls = new CallWatch(this, new CallWatch.Listener() {
            @Override public void call(final int state, final String number, final String name) { ui.post(new Runnable() { @Override public void run() { showCall(state, number, name); } }); }
        });
        calls.start();
        NowPlaying.get().addListener(musicChanged);
        NowPlaying.get().start(this);
        musicChanged.run();
        ui.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        Assistant.get().detach(aiListener);
        if (phoneAsk != null) phoneAsk.stop();
        if (calls != null) calls.stop();
        try { if (mapBound) unbindService(mapLink); } catch (Throwable ignored) { }
        try { if (callShown) windows.removeView(callCard); } catch (Throwable ignored) { }
        NowPlaying.get().removeListener(musicChanged);
        try { if (locations != null) locations.removeUpdates(gps); } catch (Throwable ignored) { }
        try { if (dashShown) windows.removeView(dash); } catch (Throwable ignored) { }
        try { if (stripShown) windows.removeView(strip); } catch (Throwable ignored) { }
        try { if (path != null) windows.removeView(path); } catch (Throwable ignored) { }
        try { if (tone != null) tone.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private Notification notice() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (m != null) m.createNotificationChannel(new NotificationChannel("overlay", "จอขับขี่ซ้อนบนแอปอื่น", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "overlay");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(R.drawable.ic_app).setContentTitle("จอขับขี่").setContentText("กำลังแสดงความเร็วซ้อนบนแอปอื่น").setOngoing(true).build();
    }

    @SuppressWarnings("deprecation")
    private int layer() { return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }

    // ---------------------------------------------------------------- the two layouts

    /** Swap layouts. Each is its own window, so the one not in use takes no touches from the dashcam app. */
    private void setCameraMode(boolean camera) {
        cameraMode = camera;
        prefs.edit().putBoolean("cameraMode", camera).apply();
        try {
            if (camera) {
                if (dashShown) { windows.removeView(dash); dashShown = false; }
                if (!stripShown) {
                    WindowManager.LayoutParams at = new WindowManager.LayoutParams(unit * 66, WindowManager.LayoutParams.WRAP_CONTENT, layer(),
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                    at.gravity = Gravity.TOP | Gravity.START;
                    at.x = unit * 2;
                    at.y = unit * 2;
                    windows.addView(strip, at);
                    stripShown = true;
                }
            } else {
                if (stripShown) { windows.removeView(strip); stripShown = false; }
                if (!dashShown) {
                    WindowManager.LayoutParams at = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layer(),
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                    windows.addView(dash, at);
                    dashShown = true;
                }
                nudges.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) { }
        showPath();
        syncMap();
    }

    /** The lane band belongs to the full camera picture only. */
    private void showPath() { path.setVisibility(cameraMode && !hidden && prefs.getBoolean("pathOn", true) ? View.VISIBLE : View.GONE); }

    /**
     * The ground of the dashboard: a deep gradient with two soft glows in the theme colours and a
     * rounded window cut out of it, edged in a run of the theme colours. Whatever app is underneath
     * shows through the window. Drawn once; it has no animation of its own.
     */
    private static class Surface extends FrameLayout {
        private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG), glowA = new Paint(Paint.ANTI_ALIAS_FLAG), glowB = new Paint(Paint.ANTI_ALIAS_FLAG),
                edge = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final RectF hole;
        private final float radius;
        private final int[] theme;

        Surface(Context c, RectF hole, float radius, int[] theme) {
            super(c);
            this.hole = hole;
            this.radius = radius;
            this.theme = theme;
            setWillNotDraw(false);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(4);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(14);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            ground.setShader(new android.graphics.LinearGradient(0, 0, w, h, new int[]{0xFA0A1020, 0xFA070A12, 0xFA0B0F1A}, null, android.graphics.Shader.TileMode.CLAMP));
            glowA.setShader(new android.graphics.RadialGradient(w * 0.16f, h * 0.30f, h * 0.75f, (theme[1] & 0x00FFFFFF) | 0x55000000, theme[1] & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            glowB.setShader(new android.graphics.RadialGradient(w * 0.80f, h * 0.98f, h * 0.60f, (theme[2] & 0x00FFFFFF) | 0x44000000, theme[2] & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            android.graphics.LinearGradient run = new android.graphics.LinearGradient(hole.left, hole.top, hole.right, hole.bottom, new int[]{theme[0], theme[1], theme[2]}, null, android.graphics.Shader.TileMode.CLAMP);
            edge.setShader(run);
            halo.setShader(run);
            halo.setAlpha(60);
        }

        @Override protected void onDraw(Canvas c) {
            shape.reset();
            shape.setFillType(Path.FillType.EVEN_ODD);
            shape.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            shape.addRoundRect(hole, radius, radius, Path.Direction.CW);
            c.drawPath(shape, ground);
            c.drawPath(shape, glowA);
            c.drawPath(shape, glowB);
            c.drawRoundRect(hole, radius, radius, halo);
            c.drawRoundRect(hole, radius, radius, edge);
        }
    }

    private void place(FrameLayout parent, View v, float left, float top, float width, float height) {
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(width < 0 ? FrameLayout.LayoutParams.WRAP_CONTENT : Math.round(width), height < 0 ? FrameLayout.LayoutParams.WRAP_CONTENT : Math.round(height));
        at.leftMargin = Math.round(left);
        at.topMargin = Math.round(top);
        parent.addView(v, at);
    }

    private GradientDrawable plate(float radius) {              // a card: faint tint of the theme with a lit edge
        int[] t = theme();
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{(t[1] & 0x00FFFFFF) | 0x30000000, (t[2] & 0x00FFFFFF) | 0x18000000});
        d.setCornerRadius(radius);
        d.setStroke(2, 0x2EFFFFFF);
        return d;
    }

    private void buildDashboard() {
        final float u = unit, W = screenW, H = screenH;
        final int[] t = theme();
        float column = W * 0.40f;                                   // width of the settings sheet
        float leftW = W * 0.365f, right = leftW + u * 1.5f;         // left: speed, music, co-pilot. right: camera, clock, map
        final RectF hole = new RectF(right, u * 3, right + u * 39, u * 25);          // a small camera window above the map
        mapAt = new RectF(right, u * 27, W - u * 2.5f, H - u * 3);
        dash = new Surface(this, hole, u * 3, t);

        gauge = new GaugeView(this, t);
        gauge.setLimit(limit);
        place(dash, gauge, (leftW - u * 47) / 2f + u, u, u * 47, u * 47);

        // tap the camera window for the full picture
        View window = new View(this);
        window.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setCameraMode(true); } });
        place(dash, window, hole.left, hole.top, hole.width(), hole.height());

        // beside the camera: the time and date, replaced by the co-pilot's words while it has something to say
        float besideL = hole.right + u * 2, besideW = W - u * 2.5f - besideL;
        clockD = text("--:--", 10.5f, INK, true);
        clockD.setIncludeFontPadding(false);
        clockD.setGravity(Gravity.CENTER_HORIZONTAL);
        place(dash, clockD, besideL, u * 4.5f, besideW, -1);
        dateD = text("", 3.1f, INK_DIM, false);
        dateD.setGravity(Gravity.CENTER_HORIZONTAL);
        place(dash, dateD, besideL, u * 18.5f, besideW, -1);
        noteD = text("", 2.8f, INK, false);
        noteD.setMaxLines(5);
        noteD.setEllipsize(TextUtils.TruncateAt.END);
        noteD.setLineSpacing(0, 1.1f);
        GradientDrawable bubble = round(0xF20A0F16, u * 2.5f);
        bubble.setStroke(2, (t[0] & 0x00FFFFFF) | 0x99000000);
        noteD.setBackground(bubble);
        noteD.setPadding(unit * 2, unit, unit * 2, unit);
        noteD.setVisibility(View.GONE);
        noteD.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { note(null); } });
        place(dash, noteD, besideL, hole.top, besideW, hole.height());

        // top right corner: switch between the full 3D map and the light flat one
        final TextView mapMode = text(prefs.getBoolean("map3d", true) ? "3D" : "2D", 3f, INK, true);
        mapMode.setGravity(Gravity.CENTER);
        mapMode.setBackground(round(0x24FFFFFF, u * 2));
        mapMode.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            boolean full = !prefs.getBoolean("map3d", true);
            prefs.edit().putBoolean("map3d", full).apply();
            mapMode.setText(full ? "3D" : "2D");
            note(full ? "แผนที่ 3D ตัวเต็ม กำลังเปิด…" : "แผนที่ 2D แบบเบา");
            syncMap();
        } });
        place(dash, mapMode, W - u * 2.5f - u * 9, hole.top, u * 9, u * 7);

        // the map's place: the map itself is a separate window laid over this, so these words show only until it arrives
        mapNote = text(mapGaveUp ? "แผนที่เปิดไม่ได้บนจอนี้\nส่วนแสดงเว็บของจอหยุดทำงานซ้ำ ๆ" : "กำลังเปิดแผนที่…", 3.2f, INK_DIM, false);
        mapNote.setGravity(Gravity.CENTER);
        mapNote.setBackground(plate(u * 3));
        place(dash, mapNote, mapAt.left, mapAt.top, mapAt.width(), mapAt.height());

        // music from the phone
        float cardL = u * 2.5f, cardW = leftW - cardL, musicTop = u * 49.5f, musicTall = u * 31.5f;
        LinearLayout music = new LinearLayout(this);
        music.setOrientation(LinearLayout.VERTICAL);
        music.setBackground(plate(u * 3.5f));
        music.setPadding(unit * 2, unit * 2, unit * 2, unit);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        disc = new DiscView(this, t);                              // the cover, turning like a record while the music plays
        LinearLayout.LayoutParams discAt = new LinearLayout.LayoutParams(unit * 13, unit * 13);
        discAt.rightMargin = unit * 2;
        head.addView(disc, discAt);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        titleD = text("ยังไม่มีเพลงจากมือถือ", 3.4f, INK, true);
        titleD.setSingleLine(true);
        titleD.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleD.setMarqueeRepeatLimit(-1);
        titleD.setSelected(true);
        words.addView(titleD);
        artistD = text("ต่อบลูทูธแล้วเปิดเพลงได้เลย", 2.7f, INK_DIM, false);
        artistD.setSingleLine(true);
        artistD.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(artistD);
        head.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        eq = new EqView(this, t);
        LinearLayout.LayoutParams eqAt = new LinearLayout.LayoutParams(unit * 6, unit * 6);
        eqAt.leftMargin = unit;
        head.addView(eq, eqAt);
        music.addView(head);
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        music.addView(keys, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        roundKey(keys, IconView.PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 10, null);
        playD = roundKey(keys, IconView.PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 12, t);
        roundKey(keys, IconView.NEXT, KeyEvent.KEYCODE_MEDIA_NEXT, 10, null);
        place(dash, music, cardL, musicTop, cardW, musicTall);

        // bottom row: the co-pilot, then the menu and full-camera keys
        float top = u * 83, tall = H - top - u * 3, keyW = u * 12.5f, gap = u * 1.5f;
        LinearLayout ai = new LinearLayout(this);
        ai.setGravity(Gravity.CENTER_VERTICAL);
        ai.setPadding(unit, 0, unit, 0);
        ai.setBackground(plate(u * 3.5f));
        orbD = new OrbView(this, t);
        LinearLayout.LayoutParams orbAt = new LinearLayout.LayoutParams(unit * 12, unit * 12);
        orbAt.rightMargin = unit;
        ai.addView(orbD, orbAt);
        aiLabel = text("ถาม AI", 3f, INK, false);
        aiLabel.setMaxLines(2);
        aiLabel.setEllipsize(TextUtils.TruncateAt.END);
        ai.addView(aiLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ai.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Assistant.get().tap(); } });
        place(dash, ai, cardL, top, cardW - (keyW + gap) * 2, tall);

        IconView menu = new IconView(this, IconView.MENU, INK, 0x24FFFFFF, false);
        menu.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); syncMap(); } });
        place(dash, menu, cardL + cardW - keyW * 2 - gap, top, keyW, tall);
        IconView full = new IconView(this, IconView.EXPAND, INK, 0x24FFFFFF, false);
        full.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setCameraMode(true); } });
        place(dash, full, cardL + cardW - keyW, top, keyW, tall);

        // settings, over the left column when open
        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheet = round(0xFF0F1622, u * 3);
        sheet.setStroke(2, (t[0] & 0x00FFFFFF) | 0x66000000);
        settings.setBackground(sheet);
        settings.setPadding(unit * 2, 0, unit * 2, unit);
        settings.setClickable(true);
        themeKey = option(settings, new Runnable() { @Override public void run() { prefs.edit().putInt("theme", (prefs.getInt("theme", 0) + 1) % (THEMES.length + 1)).apply(); ui.post(new Runnable() { @Override public void run() { restyle(); } }); } });
        // own colour: drag along the colour wheel; shown only while the "own" theme is chosen
        hueBar = new android.widget.SeekBar(this);
        hueBar.setMax(359);
        hueBar.setProgress(prefs.getInt("hue", 200));
        hueBar.setPadding(unit * 3, unit, unit * 3, unit);
        int[] wheel = new int[13];
        for (int i = 0; i < wheel.length; i++) wheel[i] = android.graphics.Color.HSVToColor(new float[]{i * 30 % 360, 0.75f, 1f});
        GradientDrawable rainbow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, wheel);
        rainbow.setCornerRadius(unit * 2f);
        hueBar.setBackground(rainbow);
        hueBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) { }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) { prefs.edit().putInt("hue", bar.getProgress()).apply(); ui.post(new Runnable() { @Override public void run() { restyle(); } }); }
        });
        LinearLayout.LayoutParams hueAt = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, unit * 7);
        hueAt.topMargin = unit;
        settings.addView(hueBar, hueAt);
        voiceKey = option(settings, new Runnable() { @Override public void run() { Assistant.sessionFemale = !Assistant.femaleVoice(OverlayService.this); prefs.edit().putBoolean("aiFemale", Assistant.sessionFemale).apply(); } });
        warnKey = option(settings, new Runnable() { @Override public void run() {
            int i = 0;
            for (int n = 0; n < LIMITS.length; n++) if (LIMITS[n] == limit) i = n;
            setLimitNow(LIMITS[(i + 1) % LIMITS.length]);
            manualWay = roadWay;             // keep the hand-set value until the road changes
        } });
        roadKey = option(settings, new Runnable() { @Override public void run() { flip("roads", true); if (!prefs.getBoolean("roads", true)) { roadName = ""; gauge.setRoad(""); } lookedUpAt = null; } });
        briefKey = option(settings, new Runnable() { @Override public void run() { flip("briefing", false); } });
        bootKey = option(settings, new Runnable() { @Override public void run() { flip("autostart", false); } });
        phoneKey = option(settings, new Runnable() { @Override public void run() {
            String address = KeyReceiver.localAddress();
            note(address == null ? "จอรถยังไม่ได้ต่อ Wi-Fi ต่อฮอตสปอตของมือถือก่อน"
                    : "ถามจากมือถือ: เปิด Safari แล้วพิมพ์  " + address + ":" + PhoneAsk.PORT + "  รหัส " + prefs.getString("askCode", ""));
            settings.setVisibility(View.GONE);
        } });
        pathKey = option(settings, new Runnable() { @Override public void run() { settings.setVisibility(View.GONE); prefs.edit().putBoolean("pathOn", true).apply(); setCameraMode(true); nudges.setVisibility(View.VISIBLE); } });
        LinearLayout last = new LinearLayout(this);
        optionIn(last, "ปิดจอซ้อน", new Runnable() { @Override public void run() { stopSelf(); } });
        optionIn(last, "เสร็จ", new Runnable() { @Override public void run() { settings.setVisibility(View.GONE); } });
        settings.addView(last, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, unit * 8));
        settings.setVisibility(View.GONE);
        place(dash, settings, u * 2, u * 2, column - u * 2, H - u * 4);        // tall enough for every row: it covers the dial and part of the music card while open
    }

    /** Rebuild both layouts in the newly chosen colours, keeping whichever one is showing. */
    private void restyle() {
        try {
            if (dashShown) { windows.removeView(dash); dashShown = false; }
            if (stripShown) { windows.removeView(strip); stripShown = false; }
            buildDashboard();
            buildStrip();
            labels();
            setCameraMode(cameraMode);
            settings.setVisibility(cameraMode ? View.GONE : View.VISIBLE);
            gauge.setRoad(roadName);
            musicChanged.run();
        } catch (Throwable ignored) { }
    }

    private IconView roundKey(LinearLayout row, int kind, final int keyCode, int sizeUnits, int[] blend) {
        IconView k = new IconView(this, kind, blend == null ? INK : 0xFFFFFFFF, 0x26FFFFFF, true);
        if (blend != null) k.setBlend(blend);
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { NowPlaying.get().press(keyCode); } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(unit * sizeUnits, unit * sizeUnits);
        at.setMargins(unit * 2, 0, unit * 2, 0);
        row.addView(k, at);
        return k;
    }

    /** The small strip shown over the full camera picture. */
    private void buildStrip() {
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable glass = round(GLASS, unit * 3f);
        glass.setStroke(2, (theme()[0] & 0x00FFFFFF) | 0x88000000);
        row.setBackground(glass);
        row.setPadding(unit * 3, unit, unit * 2, unit);
        speedC = text("--", 12, INK, true);
        speedC.setIncludeFontPadding(false);
        speedC.setMinWidth(unit * 19);
        row.addView(speedC);
        orbC = new OrbView(this, theme());
        orbC.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Assistant.get().tap(); } });
        LinearLayout.LayoutParams aiAt = new LinearLayout.LayoutParams(unit * 13, unit * 13);
        aiAt.rightMargin = unit * 2;
        row.addView(orbC, aiAt);
        IconView back = new IconView(this, IconView.PANEL, INK, PLATE, false);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setCameraMode(false); } });
        row.addView(back, new LinearLayout.LayoutParams(unit * 13, unit * 11));
        strip.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        noteC = text("", 2.9f, INK, false);
        noteC.setMaxLines(5);
        noteC.setEllipsize(TextUtils.TruncateAt.END);
        noteC.setBackground(round(GLASS, unit * 2.5f));
        noteC.setPadding(unit * 2, unit, unit * 2, unit);
        noteC.setVisibility(View.GONE);
        noteC.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { noteC.setVisibility(View.GONE); } });
        LinearLayout.LayoutParams noteAt = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteAt.topMargin = unit;
        strip.addView(noteC, noteAt);

        // shaping the lane band so it sits on the lane of this camera
        nudges = new LinearLayout(this);
        nudges.setOrientation(LinearLayout.VERTICAL);
        nudges.setBackground(round(GLASS, unit * 2.5f));
        nudges.setPadding(unit * 2, unit, unit, unit * 2);
        nudges.addView(text("ปรับแถบเส้นทางให้ตรงเลน", 2.6f, INK_DIM, false));
        LinearLayout a = new LinearLayout(this), b = new LinearLayout(this), c = new LinearLayout(this);
        optionIn(a, "\u25C0 ซ้าย", nudge(-0.02f, 0, 0, 0)); optionIn(a, "ขวา \u25B6", nudge(0.02f, 0, 0, 0)); optionIn(a, "กว้าง", nudge(0, 0.03f, 0, 0)); optionIn(a, "แคบ", nudge(0, -0.03f, 0, 0));
        optionIn(b, "เอียง \u25C0", nudge(0, 0, -0.02f, 0)); optionIn(b, "เอียง \u25B6", nudge(0, 0, 0.02f, 0)); optionIn(b, "ยาว", nudge(0, 0, 0, 0.04f)); optionIn(b, "สั้น", nudge(0, 0, 0, -0.04f));
        optionIn(c, "ซ่อนแถบ", new Runnable() { @Override public void run() { prefs.edit().putBoolean("pathOn", false).apply(); showPath(); nudges.setVisibility(View.GONE); } });
        optionIn(c, "เสร็จ", new Runnable() { @Override public void run() { nudges.setVisibility(View.GONE); } });
        for (LinearLayout r : new LinearLayout[]{a, b, c}) nudges.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, unit * 9));
        nudges.setVisibility(View.GONE);
        LinearLayout.LayoutParams nudgeAt = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nudgeAt.topMargin = unit;
        strip.addView(nudges, nudgeAt);
    }

    private Runnable nudge(final float centre, final float width, final float tilt, final float length) {
        return new Runnable() { @Override public void run() { prefs.edit().putBoolean("pathOn", true).apply(); showPath(); path.nudge(centre, width, tilt, length); } };
    }

    // ---------------------------------------------------------------- the map panel

    private void toMap(int what, Bundle data) {
        android.os.Messenger out = mapOut;
        if (out == null) return;
        try {
            android.os.Message m = android.os.Message.obtain(null, what);
            m.setData(data);
            m.replyTo = mapIn;
            out.send(m);
        } catch (Throwable ignored) { }
    }

    static String mapKey(Context c) { String k = App.prefs(c).getString("tomtomKey", "").trim(); return k.isEmpty() ? sessionMapKey : k; }
    static volatile String sessionMapKey = "";

    /** Show the map over its space on the dashboard, and take it away whenever something else needs that part of the screen. */
    private void syncMap() {
        if (mapOut == null || mapAt == null) return;
        boolean want = dashShown && !cameraMode && !hidden && settings.getVisibility() != View.VISIBLE;
        String key = mapKey(this) + "|" + theme()[0] + "|" + prefs.getBoolean("map3d", true);
        if (!key.equals(mapKeySent)) {             // traffic key or colours changed
            mapKeySent = key;
            Bundle b = new Bundle();
            b.putString("key", mapKey(this));
            b.putString("colour", String.format(Locale.US, "#%06X", theme()[0] & 0xFFFFFF));
            b.putBoolean("full", prefs.getBoolean("map3d", true));
            toMap(MapService.CONFIG, b);
        }
        if (want == mapSeen) return;
        mapSeen = want;
        Bundle b = new Bundle();
        b.putInt("x", Math.round(mapAt.left));
        b.putInt("y", Math.round(mapAt.top));
        b.putInt("w", Math.round(mapAt.width()));
        b.putInt("h", Math.round(mapAt.height()));
        b.putFloat("radius", unit * 3f);
        b.putBoolean("visible", want);
        toMap(MapService.PLACE, b);
    }

    private void sendFix(Location at) {
        if (mapOut == null) return;
        Bundle b = new Bundle();
        b.putDouble("lat", at.getLatitude());
        b.putDouble("lon", at.getLongitude());
        b.putFloat("bearing", at.hasBearing() ? at.getBearing() : -1);
        b.putFloat("kmh", speedKmh);
        toMap(MapService.FIX, b);
    }

    /** Ask the map to find a place and draw the way there. Waits for its answer; null when the map is not running. Not for the main thread. */
    private String guideOnMap(String destination) {
        if (mapOut == null || mapGaveUp) return null;
        mapAnswers.clear();
        ui.post(new Runnable() { @Override public void run() { if (cameraMode) setCameraMode(false); settings.setVisibility(View.GONE); syncMap(); } });
        Bundle b = new Bundle();
        b.putString("query", destination);
        toMap(MapService.GO, b);
        try { return mapAnswers.poll(30, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { return null; }
    }

    // ---------------------------------------------------------------- phone calls

    /** The card that appears for a call: who it is, and keys to answer, hang up, or see the head unit's own call screen. */
    private void buildCallCard() {
        callCard = new LinearLayout(this);
        callCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(0xF50B111A, unit * 3.5f);
        bg.setStroke(3, 0xFF46DBA0);
        callCard.setBackground(bg);
        callCard.setPadding(unit * 4, unit * 2, unit * 4, unit * 3);
        callState = text("สายเรียกเข้า", 3f, INK_DIM, false);
        callCard.addView(callState);
        callWho = text("", 6f, INK, true);
        callWho.setSingleLine(true);
        callWho.setEllipsize(TextUtils.TruncateAt.END);
        callCard.addView(callWho);
        LinearLayout keys = new LinearLayout(this);
        answerKey = callKey(keys, "รับสาย", 0xFF1FA971, new Runnable() { @Override public void run() {
            if (calls == null || !calls.answer()) { callState.setText("รับจากตรงนี้ไม่ได้ กำลังเปิดหน้าจอโทรของเครื่อง"); revealUnitCallScreen(); }
        } });
        callKey(keys, "วางสาย", 0xFFD64545, new Runnable() { @Override public void run() {
            if (calls == null || !calls.hangUp(ringing)) { callState.setText("วางจากตรงนี้ไม่ได้ กำลังเปิดหน้าจอโทรของเครื่อง"); revealUnitCallScreen(); }
        } });
        callKey(keys, "หน้าจอโทร", 0x33FFFFFF, new Runnable() { @Override public void run() { revealUnitCallScreen(); } });
        LinearLayout.LayoutParams keysAt = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, unit * 13);
        keysAt.topMargin = unit * 2;
        callCard.addView(keys, keysAt);
    }

    private TextView callKey(LinearLayout row, String label, int colour, final Runnable action) {
        TextView k = text(label, 3.6f, 0xFFFFFFFF, true);
        k.setGravity(Gravity.CENTER);
        k.setBackground(round(colour, unit * 2.5f));
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { action.run(); } catch (Throwable ignored) { } } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        at.setMargins(0, 0, unit * 2, 0);
        row.addView(k, at);
        return k;
    }

    /** Step aside so the head unit's own call screen, which sits under the dashboard, can be seen and used. */
    private void revealUnitCallScreen() { if (!cameraMode) setCameraMode(true); }

    private void showCall(int state, String number, String name) {
        if (state == CallWatch.ENDED) {
            try { if (callShown) windows.removeView(callCard); } catch (Throwable ignored) { }
            callShown = false;
            return;
        }
        ringing = state == CallWatch.INCOMING || state == CallWatch.WAITING;
        if (ringing) Assistant.get().quiet();                     // never talk over a ringing phone
        if (state == CallWatch.ACTIVE && callStarted == 0) callStarted = SystemClock.elapsedRealtime();
        if (state != CallWatch.ACTIVE) callStarted = 0;
        callWho.setText(name != null && !name.isEmpty() ? name : number == null || number.isEmpty() ? "ไม่ทราบเบอร์" : number);
        callState.setText(ringing ? "สายเรียกเข้า" : state == CallWatch.ACTIVE ? "กำลังคุย" : state == CallWatch.DIALING || state == CallWatch.ALERTING ? "กำลังโทรออก" : "พักสาย");
        answerKey.setVisibility(ringing ? View.VISIBLE : View.GONE);
        if (!callShown) {
            try {
                WindowManager.LayoutParams at = new WindowManager.LayoutParams(unit * 96, WindowManager.LayoutParams.WRAP_CONTENT, layer(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                at.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                at.y = unit * 3;
                windows.addView(callCard, at);
                callShown = true;
            } catch (Throwable ignored) { revealUnitCallScreen(); }      // cannot show ours: at least uncover the unit's
        }
    }

    // ---------------------------------------------------------------- small builders

    private GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(String s, float sizeUnits, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * sizeUnits);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void setLimitNow(int kmh) {
        limit = kmh;
        gauge.setLimit(kmh);
        if (warnKey != null) labels();
        prefs.edit().putInt("limit", kmh).apply();
    }

    private IconView mediaKey(LinearLayout row, int kind, final int keyCode) {
        IconView k = new IconView(this, kind, INK, PLATE, false);
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { NowPlaying.get().press(keyCode); } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        at.rightMargin = unit * 2;
        row.addView(k, at);
        return k;
    }

    private TextView option(LinearLayout column, final Runnable action) {
        TextView k = text("", 2.7f, INK, false);
        k.setGravity(Gravity.CENTER_VERTICAL);
        k.setSingleLine(true);
        k.setPadding(unit * 2, 0, unit * 2, 0);
        k.setBackground(round(PLATE, unit * 2f));
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { action.run(); labels(); } catch (Throwable ignored) { } } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, unit * 8);
        at.topMargin = unit;
        column.addView(k, at);
        return k;
    }

    private void optionIn(LinearLayout row, String label, final Runnable action) {
        TextView k = text(label, 2.6f, INK, false);
        k.setGravity(Gravity.CENTER);
        k.setSingleLine(true);
        k.setBackground(round(PLATE, unit * 2f));
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { action.run(); } catch (Throwable ignored) { } } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        at.setMargins(0, unit, unit, 0);
        row.addView(k, at);
    }

    private void flip(String pref, boolean byDefault) { prefs.edit().putBoolean(pref, !prefs.getBoolean(pref, byDefault)).apply(); }

    private void labels() {
        themeKey.setText("ธีมสี:  " + themeName() + "  (แตะเพื่อเปลี่ยน)");
        hueBar.setVisibility(Math.abs(prefs.getInt("theme", 0)) % (THEMES.length + 1) == THEMES.length ? View.VISIBLE : View.GONE);
        voiceKey.setText("เสียง AI:  " + (Assistant.femaleVoice(this) ? "ผู้หญิง" : "ผู้ชาย") + "  (แตะเพื่อเปลี่ยน)");
        warnKey.setText("เตือนเมื่อเร็วเกิน:  " + (limit > 0 ? limit + " กม./ชม." : "ปิด") + "  (แตะเพื่อเปลี่ยน)");
        roadKey.setText("ชื่อถนนและป้ายจากแผนที่:  " + (prefs.getBoolean("roads", true) ? "เปิด" : "ปิด"));
        briefKey.setText("AI รายงานเส้นทางเอง (ใช้โควตา):  " + (prefs.getBoolean("briefing", false) ? "เปิด" : "ปิด"));
        bootKey.setText("เปิดเองเมื่อเปิดจอ:  " + (prefs.getBoolean("autostart", false) ? "เปิด" : "ปิด"));
        phoneKey.setText("ถามจากมือถือ (ใช้ไมค์มือถือ)  \u25B6");
        pathKey.setText("ปรับแถบเส้นทางบนภาพกล้อง  \u25B6");
    }

    /** The co-pilot's words: in place of the clock on the dashboard, under the strip in camera mode. */
    private void note(String line) {
        boolean some = line != null && !line.isEmpty();
        noteD.setText(some ? line : "");
        noteD.setVisibility(some ? View.VISIBLE : View.GONE);
        clockD.setVisibility(some ? View.INVISIBLE : View.VISIBLE);
        dateD.setVisibility(some ? View.INVISIBLE : View.VISIBLE);
        noteC.setText(some ? line : "");
        noteC.setVisibility(some ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- co-pilot

    private final Assistant.Listener aiListener = new Assistant.Listener() {
        @Override public void changed(Assistant.State state, String line) {
            if (orbD == null) return;
            int now = state == Assistant.State.LISTENING ? OrbView.LISTENING : state == Assistant.State.THINKING ? OrbView.THINKING : state == Assistant.State.SPEAKING ? OrbView.SPEAKING : OrbView.IDLE;
            orbD.setState(now);
            orbC.setState(now);
            aiLabel.setText(state == Assistant.State.LISTENING ? "กำลังฟัง… แตะเมื่อพูดจบ" : state == Assistant.State.THINKING ? "กำลังคิด…" : state == Assistant.State.SPEAKING ? "กำลังพูด… แตะเพื่อหยุด" : "ถาม AI");
            if (line != null) note(line);
        }
    };

    /** What the co-pilot may read and change. Its methods are called from the worker thread. */
    private final Assistant.Car car = new Assistant.Car() {
        @Override public String readings() {
            StringBuilder s = new StringBuilder();
            Calendar now = Calendar.getInstance();
            s.append(String.format(Locale.US, "Local time: %tF %tR (Thailand)\n", now, now));
            Location at = lastPlace;
            boolean fresh = at != null && speedKmh >= 0 && System.currentTimeMillis() - lastFix < 15000;
            if (at == null) s.append("Position: no GPS fix yet\n");
            else s.append(String.format(Locale.US, "Position: %.5f, %.5f%s\n", at.getLatitude(), at.getLongitude(), fresh ? "" : " (last known, may be stale)"));
            CharSequence road = roadName;
            if (road.length() > 0) s.append("Road (from OpenStreetMap): ").append(road).append('\n');
            if (!heading.isEmpty()) s.append("Heading: ").append(heading).append('\n');
            s.append("Speed: ").append(fresh ? Math.round(speedKmh) + " km/h" : "unknown").append("; over-speed warning: ").append(limit > 0 ? "set at " + limit + " km/h" : "off").append('\n');
            s.append(String.format(Locale.US, "This trip so far: %.1f km in %d minutes of driving, top speed %d km/h\n", tripMetres / 1000.0, movingMillis / 60000, Math.round(maxKmh)));
            NowPlaying music = NowPlaying.get();
            if (music.title != null) s.append("Music from the phone: ").append(music.title).append(music.artist == null ? "" : " by " + music.artist).append(Boolean.TRUE.equals(music.playing) ? " (playing)" : " (paused)").append('\n');
            s.append("Screen capture for look_at_road: ").append(ScreenGrab.ready() ? "allowed" : "not allowed yet");
            return s.toString();
        }
        @Override public Location place() { return lastPlace; }
        @Override public void setLimit(final int kmh) {
            ui.post(new Runnable() { @Override public void run() { setLimitNow(kmh); manualWay = roadWay; } });
        }
        @Override public void setPath(final boolean visible) {
            ui.post(new Runnable() { @Override public void run() { prefs.edit().putBoolean("pathOn", visible).apply(); showPath(); } });
        }
        @Override public String guide(String destination) { return guideOnMap(destination); }
        @Override public void hideOverlay(final boolean hide) {       // so a screen frame shows the whole camera picture
            ui.post(new Runnable() { @Override public void run() { hidden = hide; dash.setVisibility(hide ? View.INVISIBLE : View.VISIBLE); strip.setVisibility(hide ? View.INVISIBLE : View.VISIBLE); showPath(); syncMap(); } });
        }
    };

    /** Optional: once driving, and then every 40 km, the co-pilot checks the road ahead and says what it found. */
    private void maybeBrief() {
        if (!prefs.getBoolean("briefing", false) || Assistant.get().state() != Assistant.State.IDLE) return;
        boolean first = briefedAtMetres < 0 && movingMillis > 90000 && speedKmh > 20;
        boolean later = briefedAtMetres >= 0 && tripMetres - briefedAtMetres > 40000;
        if (!first && !later) return;
        briefedAtMetres = tripMetres;
        Assistant.get().askText("Check for anything on the road ahead that I should know about in the next 40 km or so in my direction of travel: closures, serious accidents, roadworks, flooding or bad weather. If you find nothing worth interrupting me for, say so in one short sentence.", true);
    }

    // ---------------------------------------------------------------- music

    private final Runnable musicChanged = new Runnable() {
        @Override public void run() {
            ui.post(new Runnable() { @Override public void run() {
                if (titleD == null) return;
                NowPlaying now = NowPlaying.get();
                if (now.title == null) return;
                if (!now.title.contentEquals(titleD.getText())) titleD.setText(now.title);
                String who = now.artist == null ? "" : now.artist;
                if (!who.contentEquals(artistD.getText())) artistD.setText(who);
                playD.setKind(Boolean.TRUE.equals(now.playing) ? IconView.PAUSE : IconView.PLAY);
                eq.setPlaying(Boolean.TRUE.equals(now.playing));
                disc.setPlaying(Boolean.TRUE.equals(now.playing));
                disc.setCover(now.cover);
            } });
        }
    };

    // ---------------------------------------------------------------- speed and alerts

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            syncMap();
            boolean fresh = speedKmh >= 0 && System.currentTimeMillis() - lastFix < 5000;
            boolean over = fresh && limit > 0 && speedKmh > limit + 0.5f;
            String shown = fresh ? String.valueOf(Math.round(speedKmh)) : "--";
            gauge.setSpeed(fresh ? speedKmh : -1);
            if (!shown.contentEquals(speedC.getText())) speedC.setText(shown);
            speedC.setTextColor(over ? SIGN_RED : INK);
            if (over && speedKmh > limit + 3 && !CallWatch.inCall && SystemClock.elapsedRealtime() - lastTone > 4000) beep();
            if (callShown && callStarted > 0) {
                long sec = (SystemClock.elapsedRealtime() - callStarted) / 1000;
                callState.setText(String.format(Locale.US, "กำลังคุย  %d:%02d", sec / 60, sec % 60));
            }
            Calendar clock = Calendar.getInstance();
            String time = String.format(Locale.US, "%02d:%02d", clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE));
            if (!time.contentEquals(clockD.getText())) {
                clockD.setText(time);
                String[] days = {"อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์"};
                String[] months = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};
                dateD.setText("วัน" + days[clock.get(Calendar.DAY_OF_WEEK) - 1] + "ที่ " + clock.get(Calendar.DAY_OF_MONTH) + " " + months[clock.get(Calendar.MONTH)]);
            }

            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);     // dim after dark so the display does not glare
            float dim = hour >= 19 || hour < 6 ? 0.8f : 1f;
            if (strip.getAlpha() != dim) { strip.setAlpha(dim); path.setAlpha(dim); }
            ui.postDelayed(this, 500);
        }
    };

    private void beep() {
        lastTone = SystemClock.elapsedRealtime();
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
        } catch (Throwable ignored) { }          // some units have no tone generator; the red number still shows
    }

    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(Location at) {
            long clock = SystemClock.elapsedRealtime();
            if (at.hasSpeed()) {
                speedKmh = at.getSpeed() * 3.6f;
                lastFix = System.currentTimeMillis();
                if (speedKmh > maxKmh) maxKmh = speedKmh;
            }
            if (lastPlace != null && speedKmh > 3 && clock - lastFixClock < 5000) {
                tripMetres += lastPlace.distanceTo(at);
                movingMillis += clock - lastFixClock;
            }
            if (at.hasBearing() && speedKmh > 8) heading = COMPASS[Math.round(at.getBearing() / 45f) % 8];
            sendFix(at);
            lookUpRoad(at);
            maybeBrief();
            lastPlace = at;
            lastFixClock = clock;
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    /** Ask the map which road this is: after moving 120 m, or every minute while moving. One request at a time. */
    private void lookUpRoad(Location at) {
        if (!prefs.getBoolean("roads", true) || lookingUp) return;
        long clock = SystemClock.elapsedRealtime();
        boolean due = lookedUpAt == null || lookedUpAt.distanceTo(at) > 120 || (speedKmh > 5 && clock - lookedUpClock > 60000);
        if (!due || clock - lookedUpClock < 8000) return;
        lookingUp = true;
        lookedUpAt = at;
        lookedUpClock = clock;
        RoadInfo.lookup(at.getLatitude(), at.getLongitude(), ui, new RoadInfo.Done() {
            @Override public void on(RoadInfo.Result r) {
                lookingUp = false;
                // The road name is a nicety from a free public service that is often slow; stay quiet when it does not answer.
                if (!running || gauge == null || !prefs.getBoolean("roads", true) || r.error != null) return;
                roadWay = r.wayId;
                roadName = r.name == null ? "" : r.name;
                gauge.setRoad(roadName);
                if (limit > 0 && r.limit > 0 && manualWay != r.wayId) setLimitNow(r.limit);      // posted limit known and not overridden by hand on this road
            }
        });
    }

    private void startGps() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locations != null && locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, gps);
        } catch (Throwable ignored) { }
    }
}
