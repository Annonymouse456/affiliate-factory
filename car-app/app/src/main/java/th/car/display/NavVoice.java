package th.car.display;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Speaks the map's turn instructions ("in 300 metres, turn left onto …").
 *
 * The words come from the map; the voice is the same one the co-pilot uses. Only the newest
 * instruction matters, so anything still waiting when a newer one arrives is dropped. It keeps
 * quiet during phone calls and while the co-pilot is listening or talking.
 */
final class NavVoice {

    private static final NavVoice ONE = new NavVoice();
    static NavVoice get() { return ONE; }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String waiting;
    private volatile MediaPlayer player;
    private volatile boolean hadMusic;
    private Context app;
    /** Phrases repeat a lot ("turn left", "you have arrived"): keep the sound of recent ones rather than fetch them again. */
    private final Map<String, byte[]> heard = new LinkedHashMap<String, byte[]>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) { return size() > 40; }
    };

    void say(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        app = c.getApplicationContext();
        waiting = text.trim();
        worker.execute(new Runnable() { @Override public void run() { next(); } });
    }

    /** Stop at once: the driver has started talking to the co-pilot, or a call has come in. */
    boolean quiet() {
        waiting = null;
        boolean owed = hadMusic && player != null;
        finish(false);
        if (owed) hadMusic = false;
        return owed;                     // true: music was paused for the instruction and has not been started again
    }

    private boolean busy() { return CallWatch.inCall || Assistant.get().state() != Assistant.State.IDLE; }

    private void next() {
        String text = waiting;
        waiting = null;
        if (text == null || app == null || busy()) return;
        String key = Assistant.openAiKey(app);
        if (key.isEmpty()) { NowPlaying.get().note("เสียงนำทาง: ยังไม่มีคีย์ OpenAI สำหรับเสียงพูด"); return; }
        try {
            boolean female = Assistant.femaleVoice(app);
            String id = (female ? "f:" : "m:") + text;
            byte[] mp3;
            synchronized (heard) { mp3 = heard.get(id); }
            if (mp3 == null) {
                mp3 = new Speech(key).speak(text, female);
                synchronized (heard) { heard.put(id, mp3); }
            }
            if (waiting != null || busy()) return;          // something newer arrived while this was being fetched
            play(mp3);
        } catch (Throwable e) { NowPlaying.get().note("เสียงนำทาง: พูดไม่ได้ " + (e instanceof Speech.Failure ? e.getMessage() : Http.problem(e))); }
    }

    private void play(final byte[] mp3) throws Exception {
        finish(false);
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (!hadMusic) hadMusic = Boolean.TRUE.equals(NowPlaying.get().playing);      // keep it across instructions that follow closely: by the second, the music already reads as paused
        audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        final MediaPlayer p = new MediaPlayer();
        player = p;
        p.setAudioStreamType(AudioManager.STREAM_MUSIC);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            p.setDataSource(new android.media.MediaDataSource() {        // straight from memory: this head unit cannot write files in the app's own folder
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
            java.io.File file = new java.io.File(app.getExternalCacheDir() != null ? app.getExternalCacheDir() : app.getCacheDir(), "turn.mp3");
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(mp3);
            out.close();
            p.setDataSource(file.getAbsolutePath());
        }
        p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { @Override public void onCompletion(MediaPlayer mp) { if (player == p) finish(true); } });
        p.setOnErrorListener(new MediaPlayer.OnErrorListener() { @Override public boolean onError(MediaPlayer mp, int what, int extra) { if (player == p) finish(true); return true; } });
        p.prepare();
        p.start();
        if (android.os.Build.VERSION.SDK_INT >= 23) { try { p.setPlaybackParams(p.getPlaybackParams().setSpeed(1.1f)); } catch (Throwable ignored) { } }
    }

    private void finish(boolean musicBack) {
        MediaPlayer p = player;
        player = null;
        if (p == null) return;
        try { p.stop(); } catch (Throwable ignored) { }
        try { p.release(); } catch (Throwable ignored) { }
        try { ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(null); } catch (Throwable ignored) { }
        if (musicBack && hadMusic) {         // this head unit pauses the phone's music for any other sound and does not start it again by itself
            ui.postDelayed(new Runnable() { @Override public void run() { if (player == null && waiting == null && hadMusic && !busy()) { hadMusic = false; NowPlaying.get().resume(); } } }, 1500);
        }
    }
}
