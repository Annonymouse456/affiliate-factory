package th.car.display;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The voice co-pilot: listen to a question, ask Claude with the car's live readings, speak the answer.
 *
 * Claude does the thinking, web search and looking at the camera picture; OpenAI turns Thai speech
 * into text and the answer back into speech. Keys are typed in on the head unit and kept in the
 * app's private storage; they are never part of the app file.
 */
final class Assistant {

    enum State { IDLE, LISTENING, THINKING, SPEAKING }

    /** What the co-pilot can read from, and do to, the car display. Called on the worker thread. */
    interface Car {
        String readings();                 // plain-text snapshot: position, road, speed, trip, time, music
        Location place();                  // null when there is no GPS fix
        void setLimit(int kmh);
        void setPath(boolean visible);
        void hideOverlay(boolean hidden);  // so a screen frame shows the camera, not our card
    }

    interface Listener { void changed(State state, String line); }

    private static final Assistant ONE = new Assistant();
    static Assistant get() { return ONE; }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile State state = State.IDLE;
    private volatile Recorder recorder;
    private volatile MediaPlayer player;
    private Listener listener;

    // Short memory for follow-up questions: the last few exchanges, forgotten ten minutes after the last one.
    private static final long REMEMBER_MS = 10 * 60 * 1000;
    private final java.util.ArrayList<String[]> recent = new java.util.ArrayList<>();
    private long lastExchange;

    private synchronized String earlier() {
        if (android.os.SystemClock.elapsedRealtime() - lastExchange > REMEMBER_MS) recent.clear();
        StringBuilder s = new StringBuilder();
        for (String[] e : recent) s.append("Driver: ").append(e[0]).append("\nYou: ").append(e[1]).append("\n");
        return s.toString().trim();
    }

    private synchronized void remember(String question, String answer) {
        if (android.os.SystemClock.elapsedRealtime() - lastExchange > REMEMBER_MS) recent.clear();
        recent.add(new String[]{question, answer});
        while (recent.size() > 4) recent.remove(0);
        lastExchange = android.os.SystemClock.elapsedRealtime();
    }

    static boolean femaleVoice(Context c) { return App.prefs(c).getBoolean("aiFemale", false) || sessionFemale; }
    static volatile boolean sessionFemale;
    private Car car;
    private Context app;

    void attach(Context c, Car car, Listener listener) { this.app = c.getApplicationContext(); this.car = car; this.listener = listener; }
    void detach(Listener l) { if (listener == l) { listener = null; car = null; } }
    State state() { return state; }

    // Keys also live in memory, so the co-pilot still works for this run on a head unit that cannot save them.
    static volatile String sessionClaudeKey = "", sessionOpenAiKey = "";
    static String claudeKey(Context c) { String k = App.prefs(c).getString("claudeKey", "").trim(); if (k.isEmpty()) k = sessionClaudeKey; return k.isEmpty() ? App.spareKey(c, "claudeKey") : k; }
    static String openAiKey(Context c) { String k = App.prefs(c).getString("openaiKey", "").trim(); if (k.isEmpty()) k = sessionOpenAiKey; return k.isEmpty() ? App.spareKey(c, "openaiKey") : k; }

    private void show(final State s, final String line) {
        state = s;
        ui.post(new Runnable() { @Override public void run() { if (listener != null) listener.changed(s, line); } });
    }

    /** The one button: start listening; while listening, finish the question; while speaking, be quiet. */
    void tap() {
        if (app == null) return;
        if (state == State.LISTENING) { Recorder r = recorder; if (r != null) r.stop(); return; }
        if (state == State.SPEAKING) { stopSpeaking(); show(State.IDLE, null); return; }
        if (state != State.IDLE) return;
        if (claudeKey(app).isEmpty()) { show(State.IDLE, "ยังไม่ได้ใส่คีย์ Claude: เปิดแอปจอขับขี่ แล้วกด ตั้งค่า AI"); return; }
        if (openAiKey(app).isEmpty()) { show(State.IDLE, "ยังไม่ได้ใส่คีย์ OpenAI สำหรับฟังและพูด: กด ตั้งค่า AI ในแอปจอขับขี่"); return; }
        show(State.LISTENING, "กำลังฟัง… พูดได้เลย");
        worker.execute(new Runnable() { @Override public void run() { listenAndAnswer(); } });
    }

    /** A question that is already text: the typed test, and the automatic road briefing. */
    void askText(final String question, final boolean aloud) {
        if (app == null || state != State.IDLE) return;
        if (claudeKey(app).isEmpty()) { show(State.IDLE, "ยังไม่ได้ใส่คีย์ Claude"); return; }
        show(State.THINKING, "กำลังคิด…");
        worker.execute(new Runnable() { @Override public void run() { answer(question, aloud && !openAiKey(app).isEmpty()); } });
    }

    private void listenAndAnswer() {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            // Ask the music to pause while the microphone is open, or it is transcribed along with the question.
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            Thread.sleep(250);
            recorder = new Recorder();
            byte[] wav = recorder.record();
            recorder = null;
            if (wav == null) { audio.abandonAudioFocus(null); show(State.IDLE, "ไม่ได้ยินเสียงพูด ลองกดแล้วพูดอีกครั้ง"); return; }
            show(State.THINKING, "กำลังฟังคำถาม…");
            String question = new Speech(openAiKey(app)).transcribe(wav);
            if (question.isEmpty()) { audio.abandonAudioFocus(null); show(State.IDLE, "ฟังไม่ออก ลองพูดอีกครั้ง"); return; }
            show(State.THINKING, "ถาม: " + question);
            answer(question, true);
        } catch (Speech.Failure e) {
            audio.abandonAudioFocus(null);
            show(State.IDLE, e.getMessage());
        } catch (Throwable e) {
            recorder = null;
            audio.abandonAudioFocus(null);
            show(State.IDLE, e instanceof IllegalStateException ? e.getMessage() : Http.problem(e));
        }
    }

    private void answer(String question, boolean aloud) {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            final Car c = car;
            String readings = c == null ? "ไม่มีข้อมูลจากรถในขณะนี้" : c.readings();
            boolean briefing = question.startsWith("Check for anything on the road ahead");      // the automatic briefing is not part of the conversation
            ClaudeClient.Answer a = new ClaudeClient(claudeKey(app)).ask(readings, briefing ? "" : earlier(), question, femaleVoice(app), new ClaudeClient.Tools() {
                @Override public Object run(String name, JSONObject input) throws Exception { return tool(name, input, c); }
            });
            count(a);
            if (a.problem == null && !briefing) remember(question, a.text);
            if (a.problem != null) { audio.abandonAudioFocus(null); show(State.IDLE, a.problem); return; }
            if (!aloud) { audio.abandonAudioFocus(null); show(State.IDLE, a.text); return; }
            show(State.SPEAKING, a.text);
            speak(a.text, audio);
        } catch (Throwable e) {
            stopSpeaking();
            show(State.IDLE, Http.problem(e));
        }
    }

    /** Read the answer aloud. If speech fails the written answer stays on the card, with the reason. */
    private void speak(final String text, AudioManager audio) {
        try {
            final byte[] mp3 = new Speech(openAiKey(app)).speak(text, femaleVoice(app));
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            MediaPlayer p = new MediaPlayer();
            player = p;
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                // Played straight from memory: this head unit cannot write files in the app's own folder.
                p.setDataSource(new android.media.MediaDataSource() {
                    @Override public int readAt(long position, byte[] buffer, int offset, int size) {
                        if (position >= mp3.length) return -1;
                        int n = (int) Math.min(size, mp3.length - position);
                        System.arraycopy(mp3, (int) position, buffer, offset, n);
                        return n;
                    }
                    @Override public long getSize() { return mp3.length; }
                    @Override public void close() { }
                });
            } else {
                File dir = app.getExternalCacheDir() != null ? app.getExternalCacheDir() : app.getCacheDir();
                File file = new File(dir, "answer.mp3");
                FileOutputStream out = new FileOutputStream(file);
                out.write(mp3);
                out.close();
                p.setDataSource(file.getAbsolutePath());
            }
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { @Override public void onCompletion(MediaPlayer mp) { stopSpeaking(); show(State.IDLE, text); } });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() { @Override public boolean onError(MediaPlayer mp, int what, int extra) { stopSpeaking(); show(State.IDLE, text + "\n(เล่นเสียงตอบไม่ได้ รหัส " + what + ")"); return true; } });
            p.prepare();
            p.start();
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                try { p.setPlaybackParams(p.getPlaybackParams().setSpeed(1.15f)); } catch (Throwable ignored) { }      // a little brisker than the voice's own pace
            }
        } catch (Speech.Failure e) {
            stopSpeaking();
            show(State.IDLE, text + "\n(พูดตอบไม่ได้: " + e.getMessage() + ")");
        } catch (Throwable e) {
            stopSpeaking();
            show(State.IDLE, text + "\n(พูดตอบไม่ได้: " + Http.problem(e) + ")");
        }
    }

    private void stopSpeaking() {
        MediaPlayer p = player;
        player = null;
        try { if (p != null) { p.stop(); p.release(); } } catch (Throwable ignored) { }
        try { ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(null); } catch (Throwable ignored) { }
    }

    /** Running totals, shown in the AI settings so spending is visible. */
    private void count(ClaudeClient.Answer a) {
        SharedPreferences p = App.prefs(app);
        p.edit().putInt("aiAsks", p.getInt("aiAsks", 0) + 1).putLong("aiIn", p.getLong("aiIn", 0) + a.inputTokens)
                .putLong("aiOut", p.getLong("aiOut", 0) + a.outputTokens).putInt("aiSearches", p.getInt("aiSearches", 0) + a.searches).apply();
    }

    static String usage(Context c) {
        SharedPreferences p = App.prefs(c);
        long in = p.getLong("aiIn", 0), out = p.getLong("aiOut", 0);
        // Claude Opus 5.5 list price: $4 per million input tokens, $20 per million output tokens. Web searches and speech are billed on top.
        double dollars = in / 1e6 * 4 + out / 1e6 * 20;
        return String.format(Locale.US, "ถาม Claude ไปแล้ว %d ครั้ง · ค้นเว็บ %d ครั้ง · ประมาณ $%.2f (ไม่รวมค่าค้นเว็บและค่าเสียงของ OpenAI)", p.getInt("aiAsks", 0), p.getInt("aiSearches", 0), dollars);
    }

    // ------------------------------------------------------------------ acting on other apps on the head unit

    private boolean installed(String pkg) {
        try { app.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private String navigate(String destination) {
        if (destination.isEmpty()) return "No destination was given.";
        try {
            Intent go;
            if (installed("com.google.android.apps.maps")) {
                go = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination))).setPackage("com.google.android.apps.maps");
            } else {
                go = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination)));
                if (go.resolveActivity(app.getPackageManager()) == null) return "No maps app is installed on the head unit, so navigation could not be started.";
            }
            app.startActivity(go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "The maps app was opened with this destination. It may take a moment to load, and it needs internet to find the route.";
        } catch (Throwable e) { return "The maps app could not be opened: " + e.getClass().getSimpleName(); }
    }

    private String playMusic(String query) {
        if (query.isEmpty()) return "Nothing to search for was given.";
        String[] players = {"com.google.android.apps.youtube.music", "com.spotify.music", "com.google.android.youtube"};
        for (String pkg : players) {
            if (!installed(pkg)) continue;
            try {
                Intent play = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                        .putExtra(SearchManager.QUERY, query).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (play.resolveActivity(app.getPackageManager()) == null) continue;
                app.startActivity(play);
                return "Asked " + pkg + " to play this. It was not confirmed that playback started.";
            } catch (Throwable ignored) { }
        }
        return "No music app on the head unit accepted the request. YouTube Music or Spotify would need to be installed on the head unit itself.";
    }

    private String openApp(String wanted) {
        try {
            PackageManager pm = app.getPackageManager();
            Intent launchers = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            String want = wanted.toLowerCase(Locale.US);
            StringBuilder names = new StringBuilder();
            ResolveInfo close = null;
            for (ResolveInfo info : pm.queryIntentActivities(launchers, 0)) {
                if (info.activityInfo == null) continue;
                String label = String.valueOf(info.loadLabel(pm));
                if (names.length() < 700) names.append(names.length() > 0 ? ", " : "").append(label);
                String low = label.toLowerCase(Locale.US);
                if (!want.isEmpty() && low.equals(want)) { close = info; break; }
                if (close == null && !want.isEmpty() && (low.contains(want) || info.activityInfo.packageName.toLowerCase(Locale.US).contains(want))) close = info;
            }
            if (close == null) return "No installed app matches that name. Installed apps: " + names;
            app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setComponent(new ComponentName(close.activityInfo.packageName, close.activityInfo.name)));
            return "Opened " + close.loadLabel(pm) + ".";
        } catch (Throwable e) { return "The app could not be opened: " + e.getClass().getSimpleName(); }
    }

    // ------------------------------------------------------------------ tools Claude can call

    private Object tool(String name, JSONObject input, Car c) throws Exception {
        if ("media_control".equals(name)) {
            String action = input.optString("action");
            int key = "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT : "previous".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            NowPlaying.get().press(key);
            return "Sent " + action + " to the phone.";
        }
        if ("navigate_to".equals(name)) return navigate(input.optString("destination").trim());
        if ("play_music".equals(name)) return playMusic(input.optString("query").trim());
        if ("open_app".equals(name)) return openApp(input.optString("name").trim());
        if (c == null) return "The display overlay is not running, so this cannot be done right now.";
        if ("set_speed_limit".equals(name)) {
            int kmh = input.optInt("kmh", -1);
            if (kmh < 20 || kmh > 130) return "Not set: the limit must be between 20 and 130 km/h.";
            c.setLimit(kmh);
            return "The sign now shows " + kmh + " km/h.";
        }
        if ("set_path_overlay".equals(name)) {
            c.setPath(input.optBoolean("visible", true));
            return input.optBoolean("visible", true) ? "The lane band is shown." : "The lane band is hidden.";
        }
        if ("look_at_road".equals(name)) {
            if (!ScreenGrab.ready()) return "The driver has not allowed screen capture yet. They can turn it on under AI settings in the app while parked.";
            c.hideOverlay(true);
            String jpeg;
            try { Thread.sleep(350); jpeg = ScreenGrab.jpegBase64(app); } finally { c.hideOverlay(false); }
            if (jpeg == null) return "No picture could be captured from the screen.";
            return new JSONArray()
                    .put(new JSONObject().put("type", "image").put("source", new JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpeg)))
                    .put(new JSONObject().put("type", "text").put("text", "The head unit's screen a moment ago. It normally shows the forward dashcam, with the dashcam app's own buttons and timestamp drawn over the picture."));
        }
        if ("nearby_places".equals(name)) {
            Location at = c.place();
            if (at == null) return "There is no GPS fix, so the car's position is unknown.";
            return RoadInfo.nearby(at, input.optString("kind"), input.optDouble("radius_km", 5));
        }
        return "Unknown tool.";
    }
}
