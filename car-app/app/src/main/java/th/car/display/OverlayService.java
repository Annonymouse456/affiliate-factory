package th.car.display;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Draws the speed card and the path band on top of whatever app is in front.
 *
 * The dashcam on this kind of head unit is not a standard camera: only its own app can show the
 * picture. So instead of reading the camera, this floats the readouts over that app's live view.
 */
public class OverlayService extends Service {

    static volatile boolean running;

    private static final int[] LIMITS = {60, 80, 90, 100, 120};
    private static final int INK = 0xFFF1F5F6, INK_DIM = 0xFFB4C1C4, GLASS = 0xD00A0F12, SIGN_RED = 0xFFE5483D;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private View card, path;
    private WindowManager.LayoutParams cardAt;
    private TextView speedText, limitText, pathKey;
    private LocationManager locations;
    private float speedKmh = -1;
    private long lastFix;
    private int limit = 80, unit;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(1, notice());
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        unit = Math.max(4, Math.min(getResources().getDisplayMetrics().heightPixels, getResources().getDisplayMetrics().widthPixels) / 100);
        try {
            addPath();
            addCard();
        } catch (Throwable e) {          // e.g. the permission was withdrawn
            stopSelf();
            return;
        }
        startGps();
        ui.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        try { if (locations != null) locations.removeUpdates(gps); } catch (Throwable ignored) { }
        try { if (card != null) windows.removeView(card); } catch (Throwable ignored) { }
        try { if (path != null) windows.removeView(path); } catch (Throwable ignored) { }
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

    /** Full-screen band that lets every touch through to the app underneath. */
    private void addPath() {
        path = new MainActivity.PathView(this);
        WindowManager.LayoutParams at = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layer(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        windows.addView(path, at);
    }

    /** Speed card: drag it by the number, tap the sign to change the limit. */
    private void addCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GLASS);
        bg.setCornerRadius(unit * 3.4f);
        box.setBackground(bg);
        box.setPadding(unit * 3, unit, unit * 3, unit * 2);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        speedText = text("--", 17, INK, true);
        speedText.setMinWidth(unit * 21);
        speedText.setIncludeFontPadding(false);
        row.addView(speedText);
        TextView kmh = text("กม./ชม.", 2.8f, INK_DIM, false);
        kmh.setPadding(unit, unit * 7, unit * 2, 0);
        row.addView(kmh);
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
        } });
        row.addView(limitText, new LinearLayout.LayoutParams(unit * 12, unit * 12));
        box.addView(row);

        LinearLayout keys = new LinearLayout(this);
        pathKey = key(keys, "เส้นทาง: เปิด", new Runnable() { @Override public void run() {
            boolean on = path.getVisibility() != View.VISIBLE;
            path.setVisibility(on ? View.VISIBLE : View.GONE);
            pathKey.setText(on ? "เส้นทาง: เปิด" : "เส้นทาง: ปิด");
        } });
        key(keys, "ปิดจอซ้อน", new Runnable() { @Override public void run() { stopSelf(); } });
        box.addView(keys);

        cardAt = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layer(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        cardAt.gravity = Gravity.TOP | Gravity.START;
        cardAt.x = unit * 2;
        cardAt.y = unit * 2;

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        speedText.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downX = e.getRawX(); downY = e.getRawY(); startX = cardAt.x; startY = cardAt.y; moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (moved || Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop) {
                            moved = true;
                            cardAt.x = Math.max(0, startX + Math.round(e.getRawX() - downX));
                            cardAt.y = Math.max(0, startY + Math.round(e.getRawY() - downY));
                            try { windows.updateViewLayout(card, cardAt); } catch (Throwable ignored) { }
                        }
                        return true;
                    default: return true;
                }
            }
        });
        card = box;
        windows.addView(card, cardAt);
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
        TextView k = text(s, 2.8f, INK, false);
        k.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(unit * 2f);
        k.setBackground(bg);
        k.setPadding(unit * 2, 0, unit * 2, 0);
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { action.run(); } catch (Throwable ignored) { } } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, unit * 9, 1);
        at.setMargins(0, unit, unit, 0);
        row.addView(k, at);
        return k;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean fresh = speedKmh >= 0 && System.currentTimeMillis() - lastFix < 5000;
            speedText.setText(fresh ? String.valueOf(Math.round(speedKmh)) : "--");
            speedText.setTextColor(fresh && speedKmh > limit + 0.5f ? SIGN_RED : INK);
            ui.postDelayed(this, 500);
        }
    };

    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(Location at) {
            if (at.hasSpeed()) { speedKmh = at.getSpeed() * 3.6f; lastFix = System.currentTimeMillis(); }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    private void startGps() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locations != null && locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, gps);
        } catch (Throwable ignored) { }
    }
}
