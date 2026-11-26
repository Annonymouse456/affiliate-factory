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
import android.graphics.PixelFormat;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * Draws the speed card and the path band on top of whatever app is in front.
 *
 * The dashcam on this kind of head unit is not a standard camera: only its own app can show the
 * picture. So instead of reading the camera, this floats the readouts over that app's live view.
 *
 * The card: speed and limit sign; the track playing over Bluetooth with its controls; trip figures;
 * and a settings panel for the path shape, the over-speed tone and starting with the head unit.
 * Drag the speed number to move the card, tap it to shrink or expand the card.
 */
public class OverlayService extends Service {

    static volatile boolean running;

    private static final int[] LIMITS = {60, 80, 90, 100, 120};
    private static final int INK = 0xFFF1F5F6, INK_DIM = 0xFFB4C1C4, GLASS = 0xD00A0F12, SIGN_RED = 0xFFE5483D;
    private static final String[] COMPASS = {"เหนือ", "ตอ.เฉียงเหนือ", "ตะวันออก", "ตอ.เฉียงใต้", "ใต้", "ตต.เฉียงใต้", "ตะวันตก", "ตต.เฉียงเหนือ"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager windows;
    private PathView path;
    private LinearLayout card, details, settings, musicRow;
    private WindowManager.LayoutParams cardAt;
    private LinearLayout musicBox;
    private TextView askKey, answerText, briefKey;
    private double briefedAtMetres = -1;
    private TextView speedText, limitText, musicText, artistText, roadText, playKey, tripText, moreText, pathKey, toneKey, bootKey, roadKey;
    private LocationManager locations;
    private ToneGenerator tone;
    private int limit, unit;

    // live figures
    private float speedKmh = -1;
    private long lastFix;
    private Location lastPlace;
    private double tripMetres;
    private long movingMillis, lastFixClock;
    private float maxKmh;
    private String heading = "";
    private long launchAt = -1;             // moment the car left standstill, for the 0–100 time
    private boolean armed;
    private float sprintSeconds = -1;
    private long lastTone;

    // road name and posted limit from the map
    private Location lookedUpAt;
    private long lookedUpClock, roadWay, manualWay = -1;
    private boolean lookingUp;
    private String roadNote = "";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = App.prefs(this);
        limit = prefs.getInt("limit", 80);
        startForeground(1, notice());
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        unit = Math.max(4, Math.min(getResources().getDisplayMetrics().heightPixels, getResources().getDisplayMetrics().widthPixels) / 100);
        try {
            addPath();
            addCard();
        } catch (Throwable e) {          // e.g. the draw-over-apps permission was withdrawn
            stopSelf();
            return;
        }
        startGps();
        Assistant.get().attach(this, car, aiListener);
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
        NowPlaying.get().removeListener(musicChanged);
        try { if (locations != null) locations.removeUpdates(gps); } catch (Throwable ignored) { }
        try { if (card != null) windows.removeView(card); } catch (Throwable ignored) { }
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

    // ---------------------------------------------------------------- windows

    /** Full-screen band that lets every touch through to the app underneath. */
    private void addPath() {
        path = new PathView(this);
        path.setVisibility(prefs.getBoolean("pathOn", true) ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams at = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layer(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        windows.addView(path, at);
    }

    private void addCard() {
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(GLASS, unit * 3.4f));
        card.setPadding(unit * 3, unit, unit * 3, unit * 2);

        // speed and limit sign
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        speedText = text("--", 15, INK, true);
        speedText.setIncludeFontPadding(false);
        top.addView(speedText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView kmh = text("กม./ชม.", 2.7f, INK_DIM, false);
        kmh.setPadding(unit, unit * 6, unit * 2, 0);
        top.addView(kmh);
        limitText = text(String.valueOf(limit), 4.8f, 0xFF14181A, true);
        limitText.setGravity(Gravity.CENTER);
        GradientDrawable sign = new GradientDrawable();
        sign.setShape(GradientDrawable.OVAL);
        sign.setColor(0xFFF4F6F6);
        sign.setStroke(Math.round(unit * 1.1f), SIGN_RED);
        limitText.setBackground(sign);
        limitText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            int i = 0;
            for (int n = 0; n < LIMITS.length; n++) if (LIMITS[n] == limit) i = n;
            limit = LIMITS[(i + 1) % LIMITS.length];
            limitText.setText(String.valueOf(limit));
            prefs.edit().putInt("limit", limit).apply();
            manualWay = roadWay;             // keep the hand-set value until the road changes
        } });
        top.addView(limitText, new LinearLayout.LayoutParams(unit * 12, unit * 12));
        card.addView(top);

        // road name from the map; hidden until one is known
        roadText = text("", 3.1f, INK, false);
        roadText.setSingleLine(true);
        roadText.setEllipsize(TextUtils.TruncateAt.END);
        roadText.setVisibility(View.GONE);
        card.addView(roadText);

        // what is playing: title on its own line (scrolls when long), artist beside the controls
        musicBox = new LinearLayout(this);
        musicBox.setOrientation(LinearLayout.VERTICAL);
        musicText = text("", 3.1f, INK, false);
        musicText.setSingleLine(true);
        musicText.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        musicText.setMarqueeRepeatLimit(-1);
        musicText.setSelected(true);
        musicBox.addView(musicText);
        musicRow = row();
        musicRow.setGravity(Gravity.CENTER_VERTICAL);
        artistText = text("", 2.7f, INK_DIM, false);
        artistText.setSingleLine(true);
        artistText.setEllipsize(TextUtils.TruncateAt.END);
        musicRow.addView(artistText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        smallKey(musicRow, "\u25C0\u25C0", new Runnable() { @Override public void run() { NowPlaying.get().press(KeyEvent.KEYCODE_MEDIA_PREVIOUS); } });
        playKey = smallKey(musicRow, "\u25B6", new Runnable() { @Override public void run() { NowPlaying.get().press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); } });
        smallKey(musicRow, "\u25B6\u25B6", new Runnable() { @Override public void run() { NowPlaying.get().press(KeyEvent.KEYCODE_MEDIA_NEXT); } });
        musicBox.addView(musicRow);
        musicBox.setVisibility(View.GONE);
        card.addView(musicBox);

        // the co-pilot: one key to ask aloud, and its last words
        LinearLayout ask = row();
        askKey = key(ask, "ถาม AI", new Runnable() { @Override public void run() { Assistant.get().tap(); } });
        card.addView(ask);
        answerText = text("", 2.8f, INK, false);
        answerText.setMaxLines(5);
        answerText.setEllipsize(TextUtils.TruncateAt.END);
        answerText.setPadding(0, unit, 0, 0);
        answerText.setVisibility(View.GONE);
        answerText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { answerText.setVisibility(View.GONE); } });
        card.addView(answerText);

        // trip figures and the main keys; tap the speed number to hide or show this part
        details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        tripText = text("", 2.7f, INK_DIM, false);
        details.addView(tripText);
        moreText = text("", 2.7f, INK_DIM, false);
        details.addView(moreText);
        LinearLayout keys = row();
        pathKey = key(keys, "", new Runnable() { @Override public void run() { boolean on = path.getVisibility() != View.VISIBLE; path.setVisibility(on ? View.VISIBLE : View.GONE); prefs.edit().putBoolean("pathOn", on).apply(); labels(); } });
        key(keys, "ตั้งค่า", new Runnable() { @Override public void run() { settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); } });
        key(keys, "ปิด", new Runnable() { @Override public void run() { stopSelf(); } });
        details.addView(keys);

        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.addView(text("ปรับแถบเส้นทางให้ตรงเลน", 2.6f, INK_DIM, false));
        LinearLayout a = row();
        key(a, "◀ ซ้าย", nudge(-0.02f, 0, 0, 0));
        key(a, "ขวา ▶", nudge(0.02f, 0, 0, 0));
        key(a, "กว้าง", nudge(0, 0.03f, 0, 0));
        key(a, "แคบ", nudge(0, -0.03f, 0, 0));
        settings.addView(a);
        LinearLayout b = row();
        key(b, "เอียง ◀", nudge(0, 0, -0.02f, 0));
        key(b, "เอียง ▶", nudge(0, 0, 0.02f, 0));
        key(b, "ยาว", nudge(0, 0, 0, 0.04f));
        key(b, "สั้น", nudge(0, 0, 0, -0.04f));
        settings.addView(b);
        LinearLayout c = row();
        toneKey = key(c, "", new Runnable() { @Override public void run() { prefs.edit().putBoolean("tone", !prefs.getBoolean("tone", true)).apply(); labels(); } });
        bootKey = key(c, "", new Runnable() { @Override public void run() { prefs.edit().putBoolean("autostart", !prefs.getBoolean("autostart", false)).apply(); labels(); } });
        settings.addView(c);
        LinearLayout r = row();
        roadKey = key(r, "", new Runnable() { @Override public void run() {
            boolean on = !prefs.getBoolean("roads", true);
            prefs.edit().putBoolean("roads", on).apply();
            if (!on) { roadText.setVisibility(View.GONE); roadNote = ""; }
            lookedUpAt = null;
            labels();
        } });
        settings.addView(r);
        LinearLayout ai = row();
        briefKey = key(ai, "", new Runnable() { @Override public void run() { prefs.edit().putBoolean("briefing", !prefs.getBoolean("briefing", false)).apply(); labels(); } });
        settings.addView(ai);
        LinearLayout d = row();
        key(d, "เริ่มทริปใหม่", new Runnable() { @Override public void run() { tripMetres = 0; movingMillis = 0; maxKmh = 0; sprintSeconds = -1; } });
        settings.addView(d);
        settings.setVisibility(View.GONE);
        details.addView(settings);
        card.addView(details);
        if (prefs.getBoolean("compact", false)) details.setVisibility(View.GONE);
        labels();

        cardAt = new WindowManager.LayoutParams(unit * 62, WindowManager.LayoutParams.WRAP_CONTENT, layer(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        cardAt.gravity = Gravity.TOP | Gravity.START;
        cardAt.x = prefs.getInt("cardX", unit * 2);
        cardAt.y = prefs.getInt("cardY", unit * 2);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        speedText.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downX = e.getRawX(); downY = e.getRawY(); startX = cardAt.x; startY = cardAt.y; moved = false; break;
                    case MotionEvent.ACTION_MOVE:
                        if (moved || Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop) {
                            moved = true;
                            cardAt.x = Math.max(0, startX + Math.round(e.getRawX() - downX));
                            cardAt.y = Math.max(0, startY + Math.round(e.getRawY() - downY));
                            try { windows.updateViewLayout(card, cardAt); } catch (Throwable ignored) { }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (moved) prefs.edit().putInt("cardX", cardAt.x).putInt("cardY", cardAt.y).apply();
                        else {                       // a tap: shrink to speed and music only, or expand again
                            boolean compact = details.getVisibility() == View.VISIBLE;
                            details.setVisibility(compact ? View.GONE : View.VISIBLE);
                            prefs.edit().putBoolean("compact", compact).apply();
                        }
                        break;
                    default: break;
                }
                return true;
            }
        });
        windows.addView(card, cardAt);
    }

    private void labels() {
        pathKey.setText(path.getVisibility() == View.VISIBLE ? "เส้นทาง: เปิด" : "เส้นทาง: ปิด");
        toneKey.setText(prefs.getBoolean("tone", true) ? "เสียงเตือนเร็วเกิน: เปิด" : "เสียงเตือนเร็วเกิน: ปิด");
        bootKey.setText(prefs.getBoolean("autostart", false) ? "เปิดเองตอนเปิดจอ: เปิด" : "เปิดเองตอนเปิดจอ: ปิด");
        briefKey.setText(prefs.getBoolean("briefing", false) ? "AI รายงานเส้นทางเอง (ใช้โควตา): เปิด" : "AI รายงานเส้นทางเอง (ใช้โควตา): ปิด");
        roadKey.setText(prefs.getBoolean("roads", true) ? "ชื่อถนนและป้ายจากแผนที่ (ใช้เน็ต): เปิด" : "ชื่อถนนและป้ายจากแผนที่ (ใช้เน็ต): ปิด");
    }

    private Runnable nudge(final float centre, final float width, final float tilt, final float length) {
        return new Runnable() { @Override public void run() { path.setVisibility(View.VISIBLE); prefs.edit().putBoolean("pathOn", true).apply(); path.nudge(centre, width, tilt, length); labels(); } };
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

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

    private TextView key(LinearLayout row, String s, final Runnable action) {
        TextView k = text(s, 2.6f, INK, false);
        k.setGravity(Gravity.CENTER);
        k.setSingleLine(true);
        k.setBackground(round(0x33FFFFFF, unit * 2f));
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { action.run(); } catch (Throwable ignored) { } } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, unit * 9, 1);
        at.setMargins(0, unit, unit, 0);
        row.addView(k, at);
        return k;
    }

    private TextView smallKey(LinearLayout row, String s, final Runnable action) {
        TextView k = text(s, 3f, INK, false);
        k.setGravity(Gravity.CENTER);
        k.setBackground(round(0x33FFFFFF, unit * 2f));
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { action.run(); } catch (Throwable ignored) { } } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(unit * 9, unit * 8);
        at.setMargins(unit, 0, 0, 0);
        row.addView(k, at);
        return k;
    }

    // ---------------------------------------------------------------- co-pilot

    private final Assistant.Listener aiListener = new Assistant.Listener() {
        @Override public void changed(Assistant.State state, String line) {
            if (askKey == null) return;
            askKey.setText(state == Assistant.State.LISTENING ? "กำลังฟัง… (แตะเมื่อพูดจบ)" : state == Assistant.State.THINKING ? "กำลังคิด…" : state == Assistant.State.SPEAKING ? "กำลังพูด… (แตะเพื่อหยุด)" : "ถาม AI");
            askKey.setBackground(round(state == Assistant.State.IDLE ? 0x33FFFFFF : 0xFF3A93FF, unit * 2f));
            if (line != null) { answerText.setText(line); answerText.setVisibility(View.VISIBLE); }
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
            CharSequence road = roadText == null ? "" : roadText.getText();
            if (road.length() > 0) s.append("Road (from OpenStreetMap): ").append(road).append('\n');
            if (!heading.isEmpty()) s.append("Heading: ").append(heading).append('\n');
            s.append("Speed: ").append(fresh ? Math.round(speedKmh) + " km/h" : "unknown").append("; limit sign on the display: ").append(limit).append(" km/h\n");
            s.append(String.format(Locale.US, "This trip so far: %.1f km in %d minutes of driving, top speed %d km/h\n", tripMetres / 1000.0, movingMillis / 60000, Math.round(maxKmh)));
            NowPlaying music = NowPlaying.get();
            if (music.title != null) s.append("Music from the phone: ").append(music.title).append(music.artist == null ? "" : " by " + music.artist).append(Boolean.TRUE.equals(music.playing) ? " (playing)" : " (paused)").append('\n');
            s.append("Screen capture for look_at_road: ").append(ScreenGrab.ready() ? "allowed" : "not allowed yet");
            return s.toString();
        }
        @Override public Location place() { return lastPlace; }
        @Override public void setLimit(final int kmh) {
            ui.post(new Runnable() { @Override public void run() { limit = kmh; limitText.setText(String.valueOf(kmh)); prefs.edit().putInt("limit", kmh).apply(); manualWay = roadWay; } });
        }
        @Override public void setPath(final boolean visible) {
            ui.post(new Runnable() { @Override public void run() { path.setVisibility(visible ? View.VISIBLE : View.GONE); prefs.edit().putBoolean("pathOn", visible).apply(); labels(); } });
        }
        @Override public void hideOverlay(final boolean hidden) {
            ui.post(new Runnable() { @Override public void run() { card.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE); path.setAlpha(hidden ? 0f : card.getAlpha()); } });
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
                if (musicBox == null) return;
                NowPlaying now = NowPlaying.get();
                if (now.title == null) { musicBox.setVisibility(View.GONE); return; }
                String line = "\u266A " + now.title;
                if (!line.contentEquals(musicText.getText())) musicText.setText(line);
                String who = now.artist == null ? "" : now.artist;
                if (!who.contentEquals(artistText.getText())) artistText.setText(who);
                playKey.setText(Boolean.TRUE.equals(now.playing) ? "II" : "\u25B6");
                musicBox.setVisibility(View.VISIBLE);
            } });
        }
    };

    // ---------------------------------------------------------------- speed, trip and alerts

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean fresh = speedKmh >= 0 && System.currentTimeMillis() - lastFix < 5000;
            boolean over = fresh && speedKmh > limit + 0.5f;
            speedText.setText(fresh ? String.valueOf(Math.round(speedKmh)) : "--");
            speedText.setTextColor(over ? SIGN_RED : INK);
            if (over && speedKmh > limit + 3 && prefs.getBoolean("tone", true) && SystemClock.elapsedRealtime() - lastTone > 4000) beep();

            long minutes = movingMillis / 60000;
            tripText.setText(String.format(Locale.US, "ทริป %.1f กม. · %d:%02d ชม. · สูงสุด %d", tripMetres / 1000.0, minutes / 60, minutes % 60, Math.round(maxKmh)));
            StringBuilder more = new StringBuilder();
            if (!heading.isEmpty()) more.append("มุ่ง").append(heading);
            if (sprintSeconds > 0) more.append(more.length() > 0 ? " · " : "").append(String.format(Locale.US, "0–100 ≈ %.1f วิ", sprintSeconds));
            if (!roadNote.isEmpty()) more.append(more.length() > 0 ? " · " : "").append(roadNote);
            moreText.setText(more.length() == 0 ? "รอข้อมูล GPS" : more.toString());

            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);     // dim after dark so the card does not glare
            float dim = hour >= 19 || hour < 6 ? 0.78f : 1f;
            if (card.getAlpha() != dim) { card.setAlpha(dim); path.setAlpha(dim); }
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
                // 0–100: armed while standing still, timed from the first fix that shows movement
                if (speedKmh < 2) { armed = true; launchAt = -1; }
                else if (armed && launchAt < 0) launchAt = lastFixClock > 0 ? lastFixClock : clock;
                if (armed && launchAt > 0 && speedKmh >= 100) { sprintSeconds = (clock - launchAt) / 1000f; armed = false; }
            }
            if (lastPlace != null && speedKmh > 3 && clock - lastFixClock < 5000) {
                tripMetres += lastPlace.distanceTo(at);
                movingMillis += clock - lastFixClock;
            }
            if (at.hasBearing() && speedKmh > 8) heading = COMPASS[Math.round(at.getBearing() / 45f) % 8];
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
                if (!running || roadText == null || !prefs.getBoolean("roads", true)) return;
                // The road name is a nicety from a free public service that is often slow; stay quiet when it does not answer.
                if (r.error != null) { roadNote = ""; return; }
                roadWay = r.wayId;
                roadText.setText(r.name == null ? "" : r.name);
                roadText.setVisibility(r.name == null ? View.GONE : View.VISIBLE);
                if (r.limit > 0 && manualWay != r.wayId) {      // posted limit known and not overridden by hand on this road
                    limit = r.limit;
                    limitText.setText(String.valueOf(limit));
                    roadNote = "ป้ายจากแผนที่";
                } else roadNote = r.limit > 0 ? "ป้ายตั้งเอง" : "แผนที่ไม่มีป้ายของถนนนี้";
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
