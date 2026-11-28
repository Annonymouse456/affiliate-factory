package th.car.display;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.media.projection.MediaProjectionManager;
import android.text.InputType;
import android.widget.EditText;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen driving display for an Android head unit: live picture from a USB (UVC) dashcam,
 * real speed from GPS, a tappable speed-limit sign and a planned-path overlay.
 *
 * Head units and their cameras vary a lot and this cannot be tried on every one, so the screen
 * reports plainly what it found: every USB device, each step of opening the camera, and any crash.
 */
public class MainActivity extends Activity {

    private static final String VERSION = "แอป 5.4";
    private static final int[] LIMITS = {60, 80, 90, 100, 120};
    private static final int INK = 0xFFF1F5F6, INK_DIM = 0xFFB4C1C4, GLASS = 0xB80A0F12, BAR = 0xDB0A0F12, PATH_BLUE = 0xFF3A93FF, SIGN_RED = 0xFFE5483D;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> log = new ArrayList<>();

    private int unit;                       // 1% of screen height, so sizes follow the screen
    private TextureView cameraView;
    private ImageView still;
    private PathView pathView;
    private View night;
    private TextView speedText, statusText, limitText, clockText, infoText;
    private ScrollView infoBox;
    private TextView pathKey, nightKey;

    private boolean askedForMusicAccess;
    private ICameraHelper camera;
    private UsbDevice current;              // device the camera helper is working on
    private int tryIndex = -1;              // position in the USB list for manual attempts
    private boolean previewing;
    private Size previewSize;

    private LocationManager locations;
    private float speedKmh = -1;            // -1 until GPS reports
    private long lastFix;
    private int limit = 80;

    // ---------------------------------------------------------------- life cycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashReporter();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        unit = Math.max(4, Math.min(getResources().getDisplayMetrics().heightPixels, getResources().getDisplayMetrics().widthPixels) / 100);
        setContentView(buildScreen());
        hideSystemBars();
        note(VERSION + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MODEL);
        Updater.peek(this, new Updater.Say() { @Override public void line(String text) { note(text); } });

        String crash = App.prefs(this).getString("crash", null);
        if (crash != null) {                 // show the last crash so a photo of the screen explains it
            App.prefs(this).edit().remove("crash").apply();
            showInfo("แอปปิดตัวเองครั้งก่อน ถ่ายรูปข้อความนี้ส่งในแชท\n\n" + crash);
        }

        if (Build.VERSION.SDK_INT >= 23) {
            List<String> ask = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.CAMERA);
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.RECORD_AUDIO);
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.READ_CONTACTS);
            if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 1);
        }
        ui.post(tick);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        for (int i = 0; i < permissions.length; i++) note("สิทธิ์ " + permissions[i].replace("android.permission.", "") + ": " + (results[i] == PackageManager.PERMISSION_GRANTED ? "อนุญาต" : "ไม่อนุญาต"));
        startGps();
        startCamera();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startGps();
        startCamera();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopGps();
        stopCamera();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private SharedPreferences prefs() { return getSharedPreferences("car", Context.MODE_PRIVATE); }

    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                try {
                    StringWriter text = new StringWriter();
                    error.printStackTrace(new PrintWriter(text));
                    String s = text.toString();
                    prefs().edit().putString("crash", s.length() > 1800 ? s.substring(0, 1800) : s).commit();
                } catch (Throwable ignored) { }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    // ---------------------------------------------------------------- screen

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        still = new ImageView(this);                       // shown until a camera delivers a picture
        still.setImageResource(R.drawable.road);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(still, fill());

        cameraView = new TextureView(this);
        cameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { attachSurface(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fitCamera(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { if (camera != null) try { camera.removeSurface(s); } catch (Throwable ignored) { } return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
        cameraView.setVisibility(View.INVISIBLE);
        root.addView(cameraView, fill());

        pathView = new PathView(this);
        root.addView(pathView, fill());

        night = new View(this);
        night.setBackgroundColor(0xB3030918);
        night.setVisibility(View.GONE);
        root.addView(night, fill());

        // speed card, top left
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(GLASS, unit * 3.4f));
        card.setPadding(unit * 4, unit, unit * 4, unit * 2);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        speedText = label("--", 21, INK, true);
        speedText.setMinWidth(unit * 26);
        speedText.setIncludeFontPadding(false);
        row.addView(speedText);
        TextView kmh = label("กม./ชม.", 3.2f, INK_DIM, false);
        kmh.setPadding(unit, unit * 9, unit * 3, 0);
        row.addView(kmh);
        limitText = label(String.valueOf(limit), 5.6f, 0xFF14181A, true);
        limitText.setGravity(Gravity.CENTER);
        GradientDrawable sign = new GradientDrawable();
        sign.setShape(GradientDrawable.OVAL);
        sign.setColor(0xFFF4F6F6);
        sign.setStroke(Math.round(unit * 1.2f), SIGN_RED);
        limitText.setBackground(sign);
        limitText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { nextLimit(); } });
        row.addView(limitText, new LinearLayout.LayoutParams(unit * 14, unit * 14));
        card.addView(row);
        statusText = label("กำลังเริ่ม…", 3f, INK_DIM, false);
        card.addView(statusText);
        FrameLayout.LayoutParams cardAt = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cardAt.setMargins(unit * 2, unit * 2, 0, 0);
        root.addView(card, cardAt);

        // one row of large keys along the bottom
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(BAR);
        bar.setPadding(unit, unit, unit, unit);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        pathKey = key(bar, "เส้นทาง", new Runnable() { @Override public void run() { boolean on = pathView.getVisibility() != View.VISIBLE; pathView.setVisibility(on ? View.VISIBLE : View.GONE); pressed(pathKey, on); } });
        pressed(pathKey, true);
        key(bar, "ซ้อนบนแอปกล้อง", new Runnable() { @Override public void run() { floatOverCameraApp(); } });
        key(bar, "เพลงบลูทูธ", new Runnable() { @Override public void run() { music(); } });
        key(bar, "ตั้งค่า AI", new Runnable() { @Override public void run() { aiSettings(); } });
        key(bar, "อัปเดต", new Runnable() { @Override public void run() { Updater.run(MainActivity.this, new Updater.Say() { @Override public void line(String text) { note(text); } }); } });
        key(bar, "ออก", new Runnable() { @Override public void run() { finish(); } });
        clockText = label("--:--", 4.8f, INK, true);
        clockText.setPadding(unit * 3, 0, unit * 3, 0);
        bar.addView(clockText);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // report panel: text big enough to read in a photo; tap to close
        infoText = label("", 3f, INK, false);
        infoText.setLineSpacing(0, 1.25f);
        infoText.setPadding(unit * 3, unit * 2, unit * 3, unit * 2);
        infoBox = new ScrollView(this);
        infoBox.setBackground(rounded(0xF20A0F12, unit * 3f));
        infoBox.addView(infoText);
        infoBox.setVisibility(View.GONE);
        infoText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { infoBox.setVisibility(View.GONE); } });
        FrameLayout.LayoutParams infoAt = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        infoAt.setMargins(unit * 8, unit * 4, unit * 8, unit * 20);
        root.addView(infoBox, infoAt);
        return root;
    }

    private FrameLayout.LayoutParams fill() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }

    private TextView label(String text, float sizeUnits, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * sizeUnits);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView key(LinearLayout bar, String text, final Runnable action) {
        TextView k = label(text, 2.9f, INK, false);
        k.setGravity(Gravity.CENTER);
        k.setSingleLine(true);
        k.setBackground(rounded(0x00000000, unit * 2.6f));
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { action.run(); } catch (Throwable e) { showInfo("ปุ่มนี้ทำงานผิดพลาด\n" + e); } } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, unit * 13, 1);
        at.setMargins(unit / 2, 0, unit / 2, 0);
        bar.addView(k, at);
        return k;
    }

    private void pressed(TextView k, boolean on) {
        k.setBackground(rounded(on ? INK : 0x00000000, unit * 2.6f));
        k.setTextColor(on ? 0xFF0C1114 : INK);
    }

    private void showInfo(String text) {
        infoText.setText(text + "\n\n(แตะเพื่อปิด)");
        infoBox.setVisibility(View.VISIBLE);
        infoBox.scrollTo(0, 0);
    }

    private void status(final String text) {
        ui.post(new Runnable() { @Override public void run() { statusText.setText(text); } });
    }

    private void note(String line) {
        if (log.size() > 40) log.remove(0);
        log.add(line);
    }

    // ---------------------------------------------------------------- floating over the dashcam app

    /**
     * The dashcam here is not a standard camera, so only its own app can show the picture.
     * Float the speed card and path band over that app instead of reading the camera.
     */
    private void floatOverCameraApp() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                showInfo("เปิดสวิตช์ \"อนุญาตให้แสดงทับแอปอื่น\" ให้แอปจอขับขี่\nแล้วกดย้อนกลับ และกดปุ่ม ซ้อนบนแอปกล้อง อีกครั้ง");
            } catch (Throwable e) {
                showInfo("จอนี้ไม่มีหน้าตั้งค่าสิทธิ์แสดงทับแอปอื่น\n" + e + "\nถ่ายรูปข้อความนี้ส่งในแชท");
            }
            return;
        }
        try {
            Intent overlay = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(overlay); else startService(overlay);
        } catch (Throwable e) {
            showInfo("เริ่มจอซ้อนไม่ได้\n" + e + "\nถ่ายรูปข้อความนี้ส่งในแชท");
            return;
        }
        App.prefs(this).edit().putBoolean("autostart", App.prefs(this).getBoolean("autostart", true)).apply();
        Intent cameraApp = App.findCameraApp(this);
        if (cameraApp == null) { showInfo("เปิดจอซ้อนแล้ว แต่หาแอปกล้องไม่เจอ\nกดปุ่มโฮม แล้วเปิดแอป Traffic safety เอง การ์ดความเร็วจะลอยอยู่ด้านบน"); return; }
        try { startActivity(cameraApp); } catch (Throwable e) { showInfo("เปิดแอปกล้องไม่ได้ เปิดแอป Traffic safety เองจากหน้าโฮม\n" + e); }
    }

    // ---------------------------------------------------------------- AI co-pilot settings

    private String clipboard() {
        try {
            ClipData clip = ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
            return clip == null || clip.getItemCount() == 0 ? "" : String.valueOf(clip.getItemAt(0).coerceToText(this)).trim();
        } catch (Throwable e) { return ""; }
    }

    private static String masked(String key) { return key.isEmpty() ? "ยังไม่ได้ใส่" : "ใส่แล้ว (ลงท้าย " + key.substring(Math.max(0, key.length() - 4)) + ")"; }

    /** Keys are typed or pasted here and kept in the app's private storage on this head unit only. */
    private void aiSettings() {
        final SharedPreferences p = App.prefs(this);
        String[] items = {
                "รับคีย์จากมือถือ (ไม่ต้องพิมพ์บนจอ)",
                "คีย์ Claude: " + masked(Assistant.claudeKey(this)),
                "คีย์ OpenAI (ฟังและพูด): " + masked(Assistant.openAiKey(this)),
                "ให้ AI ดูหน้าจอได้ (สำหรับถามเรื่องที่เห็นจากกล้อง): " + (ScreenGrab.ready() ? "อนุญาตแล้ว" : "ยังไม่อนุญาต"),
                "พิมพ์คำถามทดสอบ",
                "ดูยอดการใช้งานและสถานะการบันทึก",
                "ลบคีย์ทั้งหมดออกจากจอนี้"
        };
        new AlertDialog.Builder(this).setTitle("ตั้งค่า AI").setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                which -= 1;
                if (which == -1) keysFromPhone();
                else if (which == 0) enterKey("คีย์ Claude (ขึ้นต้นด้วย sk-ant-)", "claudeKey");
                else if (which == 1) enterKey("คีย์ OpenAI (ขึ้นต้นด้วย sk-)", "openaiKey");
                else if (which == 2) {
                    try { startActivityForResult(((MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent(), 7); }
                    catch (Throwable e) { showInfo("จอนี้ไม่รองรับการจับภาพหน้าจอ\n" + e); }
                }
                else if (which == 3) typeQuestion();
                else if (which == 4) showInfo(Assistant.usage(MainActivity.this) + "\n\n" + App.installHistory(MainActivity.this) + "\nพื้นที่ว่างในจอ: " + App.freeMegabytes(MainActivity.this) + " MB · ที่เก็บการตั้งค่า: " + (App.usingSpareStore() ? "ที่เก็บสำรอง (ที่เก็บปกติใช้ไม่ได้: " + App.whyCannotSave(MainActivity.this) + ")" : "ปกติ") + "\n\nภาพจากกล้อง เสียงพูด และตำแหน่งรถ จะถูกส่งไปที่ Anthropic และ OpenAI เฉพาะตอนที่คุณถาม AI หรือเปิดรายงานเส้นทางเอง");
                else { p.edit().remove("claudeKey").remove("openaiKey").apply(); App.clearSpareKeys(MainActivity.this); Assistant.sessionClaudeKey = ""; Assistant.sessionOpenAiKey = ""; showInfo("ลบคีย์ออกจากจอนี้แล้ว"); }
            }
        }).show();
    }

    /** Serve a small page on the local Wi-Fi so the keys can be pasted on the phone and sent straight here. */
    private void keysFromPhone() {
        String address = KeyReceiver.localAddress();
        if (address == null) { showInfo("จอรถยังไม่ได้ต่อ Wi-Fi\nต่อจอรถกับฮอตสปอตของมือถือก่อน แล้วกดใหม่"); return; }
        final KeyReceiver receiver = new KeyReceiver();
        try {
            receiver.start(this, new KeyReceiver.Done() {
                @Override public void saved(final String summary) { ui.post(new Runnable() { @Override public void run() { if (keyDialog != null) keyDialog.dismiss(); showInfo("ได้รับคีย์จากมือถือแล้ว\n" + summary + "\n\nลองกด ตั้งค่า AI แล้วเลือก พิมพ์คำถามทดสอบ"); } }); }
            });
        } catch (Throwable e) { showInfo("เปิดช่องรับคีย์ไม่ได้\n" + e); return; }
        keyDialog = new AlertDialog.Builder(this).setTitle("รับคีย์จากมือถือ")
                .setMessage("1. มือถือต้องเป็นเครื่องที่แชร์ฮอตสปอตให้จอรถ (หรืออยู่ Wi-Fi เดียวกัน)\n"
                        + "2. เปิด Safari หรือ Chrome บนมือถือ พิมพ์:\n\n      " + address + ":" + KeyReceiver.PORT + "\n\n"
                        + "3. ใส่รหัส:   " + receiver.code + "\n"
                        + "4. วางคีย์ แล้วกด ส่งเข้าจอรถ\n\nหน้านี้เปิดรับ 5 นาที คีย์ไม่ผ่านอินเทอร์เน็ต")
                .setNegativeButton("ปิด", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) { receiver.stop(); keyDialog = null; } })
                .show();
    }

    private AlertDialog keyDialog;

    private void enterKey(String title, final String pref) {
        final EditText box = new EditText(this);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.setSingleLine(true);
        box.setHint("พิมพ์ หรือกด วางจากคลิปบอร์ด");
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("บันทึก", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { save(pref, box.getText().toString()); } })
                .setNeutralButton("วางจากคลิปบอร์ด", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { save(pref, clipboard()); } })
                .setNegativeButton("ยกเลิก", null).show();
    }

    private void save(String pref, String key) {
        key = key == null ? "" : key.replaceAll("\\s", "");
        if (key.length() < 20) { showInfo("ข้อความสั้นเกินกว่าจะเป็นคีย์ ไม่ได้บันทึก"); return; }
        if (pref.equals("claudeKey")) Assistant.sessionClaudeKey = key; else Assistant.sessionOpenAiKey = key;
        if (!App.prefs(this).edit().putString(pref, key).commit() && App.saveSpareKey(this, pref, key)) { showInfo("บันทึกคีย์ไว้ในที่เก็บสำรองของจอแล้ว (ลงท้าย " + key.substring(key.length() - 4) + ")"); return; }
        if (!App.prefs(this).getString(pref, "").equals(key)) { showInfo("ใช้คีย์ได้ชั่วคราวจนกว่าจะปิดแอป แต่บันทึกถาวรไม่ได้\nสาเหตุ: " + App.whyCannotSave(this) + "\nพื้นที่ว่าง " + App.freeMegabytes(this) + " MB"); return; }
        showInfo("บันทึกคีย์แล้ว (ลงท้าย " + key.substring(key.length() - 4) + ")\nคีย์เก็บอยู่ในจอนี้เท่านั้น");
    }

    /** A typed question, answered on screen: checks the Claude key and the internet without needing the microphone. */
    private void typeQuestion() {
        final EditText box = new EditText(this);
        box.setText("ตอนนี้กี่โมง และวันนี้มีข่าวจราจรอะไรในประเทศไทยบ้าง");
        new AlertDialog.Builder(this).setTitle("พิมพ์คำถามทดสอบ").setView(box)
                .setPositiveButton("ถาม", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    showInfo("กำลังถาม Claude… อาจใช้เวลาถึงครึ่งนาทีถ้าต้องค้นเว็บ");
                    Assistant.Listener hear = new Assistant.Listener() {
                        @Override public void changed(Assistant.State state, String line) {
                            if (state != Assistant.State.IDLE || line == null) return;
                            Assistant.get().alsoTell(null);
                            showInfo("คำตอบ:\n" + line + "\n\n" + Assistant.usage(MainActivity.this));
                        }
                    };
                    // while the display overlay is running it owns the co-pilot; listen in rather than take its place
                    if (Assistant.get().attached()) Assistant.get().alsoTell(hear); else Assistant.get().attach(MainActivity.this, null, hear);
                    Assistant.get().askText(box.getText().toString(), false);
                } })
                .setNegativeButton("ยกเลิก", null).show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 7) return;
        if (result == RESULT_OK && data != null) { ScreenGrab.allow(this, result, data); showInfo(ScreenGrab.ready() ? "อนุญาตแล้ว AI ดูภาพหน้าจอได้จนกว่าจะปิดแอป\nต้องอนุญาตใหม่ทุกครั้งที่เปิดจอ" : "เริ่มการจับภาพหน้าจอไม่ได้"); }
        else showInfo("ไม่ได้อนุญาตให้จับภาพหน้าจอ");
    }

    // ---------------------------------------------------------------- Bluetooth music

    /** Ask for notification access (it unlocks other apps' "now playing"), then show what each source reports. */
    private void music() {
        NowPlaying.get().start(this);
        if (!NowPlaying.hasNotificationAccess(this) && !askedForMusicAccess) {
            askedForMusicAccess = true;
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                showInfo("เปิดสวิตช์ให้แอป จอขับขี่ อ่านการแจ้งเตือนได้\nแล้วกดย้อนกลับ เปิดเพลงจากมือถือผ่านบลูทูธ และกดปุ่ม เพลงบลูทูธ อีกครั้ง");
                return;
            } catch (Throwable e) {
                NowPlaying.get().note("ไม่มีหน้าตั้งค่าสิทธิ์อ่านการแจ้งเตือน: " + e.getClass().getSimpleName());
            }
        }
        showInfo("ผลตรวจเพลง (เปิดเพลงจากมือถือค้างไว้ก่อนกด แล้วถ่ายรูปหน้านี้ส่งในแชท)\n\n" + NowPlaying.get().report(this));
    }

    // ---------------------------------------------------------------- speed and clock

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar now = Calendar.getInstance();
            clockText.setText(String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)));
            boolean fresh = speedKmh >= 0 && System.currentTimeMillis() - lastFix < 5000;
            speedText.setText(fresh ? String.valueOf(Math.round(speedKmh)) : "--");
            speedText.setTextColor(fresh && speedKmh > limit + 0.5f ? SIGN_RED : INK);   // over the set limit
            if (!previewing) statusText.setText(fresh ? "ความเร็วจาก GPS · ยังไม่มีภาพกล้อง" : "รอสัญญาณ GPS · ยังไม่มีภาพกล้อง");
            else statusText.setText(fresh ? "ความเร็วจาก GPS · กล้องทำงาน" : "รอสัญญาณ GPS · กล้องทำงาน");
            ui.postDelayed(this, 500);
        }
    };

    private void nextLimit() {
        int i = 0;
        for (int n = 0; n < LIMITS.length; n++) if (LIMITS[n] == limit) i = n;
        limit = LIMITS[(i + 1) % LIMITS.length];
        limitText.setText(String.valueOf(limit));
    }

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
            if (locations == null) locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locations == null || !locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) { note("GPS: จอนี้ไม่มีตัวรับ GPS"); return; }
            locations.removeUpdates(gps);
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, gps);
            note("GPS: เริ่มรับสัญญาณ");
        } catch (Throwable e) { note("GPS: " + e); }
    }

    private void stopGps() {
        try { if (locations != null) locations.removeUpdates(gps); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- USB camera

    private void startCamera() {
        if (camera != null) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { note("กล้อง: รอสิทธิ์กล้อง"); return; }
        try {
            camera = new CameraHelper();
            camera.setStateCallback(cameraEvents);
            note("กล้อง: เริ่มมองหากล้อง USB");
        } catch (Throwable e) {
            camera = null;
            note("กล้อง: เริ่มไม่ได้ " + e);
            showInfo("เริ่มระบบกล้อง USB ไม่ได้\n" + e + "\n\n" + report());
        }
    }

    private void stopCamera() {
        previewing = false;
        current = null;
        if (camera != null) {
            try { camera.release(); } catch (Throwable ignored) { }
            camera = null;
        }
        ui.post(new Runnable() { @Override public void run() { cameraView.setVisibility(View.INVISIBLE); still.setVisibility(View.VISIBLE); } });
    }

    private final ICameraHelper.StateCallback cameraEvents = new ICameraHelper.StateCallback() {
        @Override public void onAttach(UsbDevice device) {
            note("พบอุปกรณ์ " + name(device));
            if (current == null && camera != null) { current = device; note("ขอสิทธิ์ใช้ " + name(device)); camera.selectDevice(device); }
        }
        @Override public void onDeviceOpen(UsbDevice device, boolean first) {
            note("เปิดอุปกรณ์ได้ " + name(device));
            if (camera != null) camera.openCamera();
        }
        @Override public void onCameraOpen(UsbDevice device) {
            note("เปิดกล้องได้ " + name(device));
            if (camera == null) return;
            camera.startPreview();
            previewSize = camera.getPreviewSize();
            if (previewSize != null) note("ขนาดภาพ " + previewSize.width + "x" + previewSize.height);
            previewing = true;
            ui.post(new Runnable() { @Override public void run() { cameraView.setVisibility(View.VISIBLE); attachSurface(); fitCamera(); still.setVisibility(View.GONE); infoBox.setVisibility(View.GONE); } });
        }
        @Override public void onCameraClose(UsbDevice device) {
            note("กล้องปิด " + name(device));
            previewing = false;
            if (camera != null && cameraView.getSurfaceTexture() != null) try { camera.removeSurface(cameraView.getSurfaceTexture()); } catch (Throwable ignored) { }
            ui.post(new Runnable() { @Override public void run() { cameraView.setVisibility(View.INVISIBLE); still.setVisibility(View.VISIBLE); } });
        }
        @Override public void onDeviceClose(UsbDevice device) { note("อุปกรณ์ปิด " + name(device)); }
        @Override public void onDetach(UsbDevice device) {
            note("ถอดอุปกรณ์ " + name(device));
            if (device != null && device.equals(current)) current = null;
        }
        @Override public void onCancel(UsbDevice device) {
            note("ไม่ได้รับสิทธิ์ใช้ " + name(device));
            if (device != null && device.equals(current)) current = null;
            ui.post(new Runnable() { @Override public void run() { showInfo("ไม่ได้รับสิทธิ์ใช้อุปกรณ์ USB นี้\nถ้ามีหน้าต่างถาม ให้กด ตกลง/OK\n\n" + report()); } });
        }
        @Override public void onError(UsbDevice device, final CameraException e) {
            note("ผิดพลาด " + name(device) + ": " + e);
            if (device != null && device.equals(current)) current = null;
            ui.post(new Runnable() { @Override public void run() {
                String why = e != null && e.getCode() == CameraException.CAMERA_OPEN_ERROR_BUSY ? "กล้องถูกแอปอื่นใช้อยู่ (ปิดแอป Traffic safety แล้วกด ลองกล้อง)" : "เปิดกล้องตัวนี้ไม่ได้";
                showInfo(why + "\n\n" + report());
            } });
        }
    };

    private void attachSurface() {
        SurfaceTexture surface = cameraView.getSurfaceTexture();
        if (camera != null && previewing && surface != null) {
            try { camera.addSurface(surface, false); } catch (Throwable e) { note("แสดงภาพไม่ได้: " + e); }
        }
    }

    /** Scale the picture to cover the whole screen without stretching it. */
    private void fitCamera() {
        if (previewSize == null || cameraView.getWidth() == 0 || previewSize.width == 0 || previewSize.height == 0) return;
        float w = cameraView.getWidth(), h = cameraView.getHeight();
        float scale = Math.max(w / previewSize.width, h / previewSize.height);
        Matrix m = new Matrix();
        m.setScale(previewSize.width * scale / w, previewSize.height * scale / h, w / 2f, h / 2f);
        cameraView.setTransform(m);
    }

    private List<UsbDevice> usbDevices() {
        List<UsbDevice> all = new ArrayList<>();
        try {
            UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usb != null) all.addAll(usb.getDeviceList().values());
        } catch (Throwable e) { note("อ่านรายการ USB ไม่ได้: " + e); }
        return all;
    }

    /** Manual attempt: each press asks the next USB device for a picture, whatever it claims to be. */
    private void tryNextDevice() {
        startCamera();
        List<UsbDevice> all = usbDevices();
        if (camera == null || all.isEmpty()) { showInfo((all.isEmpty() ? "ไม่พบอุปกรณ์ USB เลย\nกล้องอาจไม่ได้ต่อผ่าน USB หรือจอนี้ไม่เปิด USB ให้แอปใช้" : "ระบบกล้องยังไม่พร้อม") + "\n\n" + report()); return; }
        tryIndex = (tryIndex + 1) % all.size();
        UsbDevice device = all.get(tryIndex);
        note("ลองเอง " + (tryIndex + 1) + "/" + all.size() + ": " + name(device));
        status("กำลังลอง " + name(device));
        try {
            if (previewing) camera.closeCamera();
            current = device;
            camera.selectDevice(device);
        } catch (Throwable e) {
            note("ลองไม่ได้: " + e);
            showInfo("ลองอุปกรณ์นี้ไม่ได้\n" + e + "\n\n" + report());
        }
    }

    private static String hex(int n) { return String.format(Locale.US, "%04x", n); }

    private String name(UsbDevice d) {
        if (d == null) return "(ไม่ทราบ)";
        String product = null;
        try { product = d.getProductName(); } catch (Throwable ignored) { }
        return hex(d.getVendorId()) + ":" + hex(d.getProductId()) + (product != null ? " " + product : "");
    }

    /** Everything needed to judge from a photo why there is, or is not, a picture. */
    private String report() {
        StringBuilder s = new StringBuilder();
        s.append(VERSION).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.MODEL).append('\n');
        List<UsbDevice> all = usbDevices();
        s.append("อุปกรณ์ USB ที่แอปมองเห็น: ").append(all.size()).append(" ตัว\n");
        for (UsbDevice d : all) {
            boolean video = d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO;
            StringBuilder kinds = new StringBuilder();
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface f = d.getInterface(i);
                if (f.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) video = true;
                String k = String.valueOf(f.getInterfaceClass());
                if (kinds.indexOf(k) < 0) kinds.append(kinds.length() > 0 ? "," : "").append(k);
            }
            s.append(" • ").append(name(d)).append(" ชนิด ").append(d.getDeviceClass()).append(" [").append(kinds).append("] ")
                    .append(video ? "← เป็นกล้องมาตรฐาน (UVC)" : "ไม่ใช่กล้องมาตรฐาน").append('\n');
        }
        boolean anyVideo = s.indexOf("(UVC)") >= 0;
        if (!all.isEmpty() && !anyVideo) s.append("กล้องของจอนี้ไม่ใช่กล้องมาตรฐาน แอปนี้อ่านภาพตรงๆ ไม่ได้ ใช้ปุ่ม ซ้อนบนแอปกล้อง แทน\n");
        s.append("บันทึกล่าสุด:\n");
        for (int i = Math.max(0, log.size() - 12); i < log.size(); i++) s.append(" ").append(log.get(i)).append('\n');
        return s.toString().trim();
    }
}
