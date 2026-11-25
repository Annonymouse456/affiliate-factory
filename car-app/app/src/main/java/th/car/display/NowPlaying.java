package th.car.display;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What is playing, gathered from every place a head unit might publish it.
 *
 * A phone streaming over Bluetooth sends title and artist to the head unit, but where the head
 * unit keeps them differs by maker. This listens to all the standard places at once and records
 * what each one said, so the on-screen report shows which of them this unit actually uses:
 *   1. media sessions of other apps (needs notification access),
 *   2. the Bluetooth stack's own media service,
 *   3. broadcasts sent by the Bluetooth stack and by music players,
 *   4. media notifications.
 */
public class NowPlaying {

    private static final NowPlaying ONE = new NowPlaying();
    static NowPlaying get() { return ONE; }

    String title, artist, source;
    Boolean playing;                        // null when unknown

    private final List<Runnable> listeners = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private Context app;
    private boolean started;
    private MediaController controller;     // session we can also send play/pause/next to
    private boolean controllerFromSession;
    private final List<MediaBrowser> browsers = new ArrayList<>();
    private MediaSessionManager.OnActiveSessionsChangedListener sessionWatcher;

    void addListener(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void removeListener(Runnable r) { listeners.remove(r); }

    void note(String line) {
        if (log.size() > 30) log.remove(0);
        log.add(line);
    }

    private void changed() { for (Runnable r : new ArrayList<>(listeners)) try { r.run(); } catch (Throwable ignored) { } }

    /** A source reports a track. Session sources outrank guesses from broadcasts and notifications. */
    void offer(String from, String newTitle, String newArtist, Boolean nowPlaying, boolean trusted) {
        if (newTitle == null || newTitle.trim().isEmpty()) {
            if (nowPlaying != null && from.equals(source)) { playing = nowPlaying; changed(); }
            return;
        }
        if (!trusted && controller != null && title != null) return;
        boolean differs = !newTitle.equals(title) || (newArtist != null && !newArtist.equals(artist)) || !from.equals(source);
        title = newTitle.trim();
        artist = newArtist == null ? null : newArtist.trim();
        if (nowPlaying != null) playing = nowPlaying;
        source = from;
        if (differs) note("เพลงจาก " + from + ": " + title + (artist != null ? " – " + artist : ""));
        changed();
    }

    // ------------------------------------------------------------------ starting the sources

    void start(Context c) {
        app = c.getApplicationContext();
        if (!started) {
            started = true;
            listenForBroadcasts();
            connectBluetoothService();
        }
        watchSessions(app);
    }

    static boolean hasNotificationAccess(Context c) {
        try {
            String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return on != null && on.contains(c.getPackageName());
        } catch (Throwable e) { return false; }
    }

    /** 1. Media sessions of other apps. */
    void watchSessions(Context c) {
        try {
            final MediaSessionManager sessions = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            final ComponentName me = new ComponentName(c, MusicListener.class);
            if (sessions == null) return;
            pick(sessions.getActiveSessions(me));
            if (sessionWatcher == null) {
                sessionWatcher = new MediaSessionManager.OnActiveSessionsChangedListener() {
                    @Override public void onActiveSessionsChanged(List<MediaController> now) { pick(now); }
                };
                sessions.addOnActiveSessionsChangedListener(sessionWatcher, me);
                note("เซสชันเพลง: เริ่มเฝ้าดู");
            }
        } catch (SecurityException e) {
            note("เซสชันเพลง: ยังไม่มีสิทธิ์อ่านการแจ้งเตือน");
        } catch (Throwable e) {
            note("เซสชันเพลง: " + e);
        }
    }

    private void pick(List<MediaController> sessions) {
        if (sessions == null) return;
        MediaController best = null;
        for (MediaController s : sessions) {
            if (app != null && app.getPackageName().equals(s.getPackageName())) continue;
            PlaybackState st = s.getPlaybackState();
            boolean active = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            if (best == null || active) best = s;
            if (active) break;
        }
        if (best != null) follow(best, "เซสชันของ " + best.getPackageName(), true);
    }

    private void follow(final MediaController c, final String from, boolean fromSession) {
        try {
            if (controller != null && controller.getSessionToken().equals(c.getSessionToken())) return;
            if (controller != null && controllerFromSession && !fromSession) return;   // keep the app session over the Bluetooth service
            controller = c;
            controllerFromSession = fromSession;
            note("ติดตาม " + from);
            c.registerCallback(new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { if (controller == c) read(c, from); }
                @Override public void onPlaybackStateChanged(PlaybackState s) { if (controller == c) read(c, from); }
                @Override public void onSessionDestroyed() { if (controller == c) { controller = null; note("เซสชันปิด " + from); } }
            });
            read(c, from);
        } catch (Throwable e) { note("ติดตามไม่ได้ " + from + ": " + e); }
    }

    private void read(MediaController c, String from) {
        MediaMetadata m = c.getMetadata();
        PlaybackState s = c.getPlaybackState();
        Boolean isPlaying = s == null ? null : Boolean.valueOf(s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
        String t = null, a = null;
        if (m != null) {
            t = first(m.getString(MediaMetadata.METADATA_KEY_TITLE), m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            a = first(m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        }
        if (t == null && isPlaying != null) { if (from.equals(source)) { playing = isPlaying; changed(); } return; }
        offer(from, t, a, isPlaying, true);
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return null;
    }

    /** 2. The Android Bluetooth stack's media service, present when the unit uses that stack as a music receiver. */
    private void connectBluetoothService() {
        String[][] known = {
                {"com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService"},
                {"com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"}
        };
        for (final String[] k : known) {
            try {
                final MediaBrowser[] holder = new MediaBrowser[1];
                holder[0] = new MediaBrowser(app, new ComponentName(k[0], k[1]), new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        note("บริการบลูทูธ: เชื่อมต่อได้ " + shortName(k[1]));
                        try { follow(new MediaController(app, holder[0].getSessionToken()), "บลูทูธของระบบ", false); } catch (Throwable e) { note("บริการบลูทูธ: " + e); }
                    }
                    @Override public void onConnectionFailed() { note("บริการบลูทูธ: ไม่มี " + shortName(k[1])); }
                    @Override public void onConnectionSuspended() { note("บริการบลูทูธ: หลุด " + shortName(k[1])); }
                }, null);
                holder[0].connect();
                browsers.add(holder[0]);
            } catch (Throwable e) { note("บริการบลูทูธ: " + shortName(k[1]) + " " + e); }
        }
    }

    private static String shortName(String cls) { return cls.substring(cls.lastIndexOf('.') + 1); }

    /** 3. Broadcasts from the Bluetooth stack and from music players. */
    private void listenForBroadcasts() {
        IntentFilter f = new IntentFilter();
        String[] actions = {
                "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT",
                "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED",
                "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
                "com.android.music.metachanged", "com.android.music.playstatechanged", "com.android.music.metadatachanged", "com.android.music.queuechanged",
                "com.htc.music.metachanged", "com.miui.player.metachanged", "com.sec.android.app.music.metachanged", "fm.last.android.metachanged",
                "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged"
        };
        for (String a : actions) f.addAction(a);
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { fromBroadcast(i); }
            }, f);
            note("ประกาศจากระบบ: เริ่มฟัง " + actions.length + " ชนิด");
        } catch (Throwable e) { note("ประกาศจากระบบ: " + e); }
    }

    private void fromBroadcast(Intent i) {
        try {
            String action = String.valueOf(i.getAction());
            Bundle x = i.getExtras();
            StringBuilder keys = new StringBuilder();
            String t = null, a = null;
            Boolean isPlaying = null;
            if (x != null) for (String k : x.keySet()) {
                Object v = x.get(k);
                if (keys.length() < 120) keys.append(keys.length() > 0 ? "," : "").append(k.substring(k.lastIndexOf('.') + 1));
                String low = k.toLowerCase(Locale.US);
                if (v instanceof MediaMetadata) {
                    MediaMetadata m = (MediaMetadata) v;
                    t = first(m.getString(MediaMetadata.METADATA_KEY_TITLE), m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                    a = first(m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
                } else if (v instanceof PlaybackState) {
                    isPlaying = ((PlaybackState) v).getState() == PlaybackState.STATE_PLAYING;
                } else if (v instanceof String) {
                    if (t == null && (low.equals("track") || low.equals("title") || low.contains("song") || low.equals("name"))) t = (String) v;
                    if (a == null && (low.equals("artist") || low.contains("singer") || low.equals("author"))) a = (String) v;
                } else if (v instanceof Boolean && (low.equals("playing") || low.equals("isplaying"))) {
                    isPlaying = (Boolean) v;
                }
            }
            note("ประกาศ " + action.substring(Math.max(0, action.length() - 44)) + " [" + keys + "]");
            String from = action.contains("bluetooth") ? "ประกาศบลูทูธ" : "ประกาศของแอปเพลง";
            if (t != null) offer(from, t, a, isPlaying, false);
            else if (isPlaying != null && from.equals(source)) { playing = isPlaying; changed(); }
        } catch (Throwable e) { note("อ่านประกาศไม่ได้: " + e); }
    }

    // ------------------------------------------------------------------ controls

    /** Play/pause, next or previous: through the followed session when there is one, else as a media key. */
    void press(int keyCode) {
        try {
            if (controller != null) {
                MediaController.TransportControls t = controller.getTransportControls();
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) t.skipToNext();
                else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) t.skipToPrevious();
                else if (Boolean.TRUE.equals(playing)) t.pause(); else t.play();
                return;
            }
        } catch (Throwable e) { note("สั่งเซสชันไม่ได้: " + e); }
        try {
            AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            long now = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable e) { note("ส่งปุ่มเพลงไม่ได้: " + e); }
    }

    // ------------------------------------------------------------------ report

    /** Everything needed to tell, from a photo, where this head unit keeps its Bluetooth track details. */
    String report(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("ตอนนี้: ").append(title == null ? "ยังไม่ได้ชื่อเพลงจากแหล่งใดเลย" : title + (artist != null ? " – " + artist : "") + "  (" + source + ")").append('\n');
        s.append("สิทธิ์อ่านการแจ้งเตือน: ").append(hasNotificationAccess(c) ? "เปิด" : "ปิด");
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.isLowRamDevice()) s.append(" · เครื่องแรมน้อย (ระบบอาจไม่ให้สิทธิ์นี้)");
        } catch (Throwable ignored) { }
        s.append('\n');
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) s.append("บลูทูธของแอนดรอยด์: ไม่มี (จอใช้โมดูลบลูทูธแยกของผู้ผลิต)\n");
            else s.append("บลูทูธของแอนดรอยด์: ").append(bt.isEnabled() ? "เปิด" : "ปิด").append(" · รับเพลง(A2DP sink)=").append(bt.getProfileConnectionState(11)).append(" · รีโมต(AVRCP)=").append(bt.getProfileConnectionState(12)).append('\n');
        } catch (Throwable e) { s.append("บลูทูธของแอนดรอยด์: อ่านไม่ได้ ").append(e.getClass().getSimpleName()).append('\n'); }
        try {
            MediaSessionManager sessions = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = sessions.getActiveSessions(new ComponentName(c, MusicListener.class));
            s.append("เซสชันเพลงที่เปิดอยู่: ").append(list.size()).append('\n');
            for (MediaController m : list) {
                MediaMetadata d = m.getMetadata();
                PlaybackState st = m.getPlaybackState();
                s.append(" • ").append(m.getPackageName()).append(" สถานะ ").append(st == null ? "-" : String.valueOf(st.getState()))
                        .append(d == null ? " (ไม่มีชื่อเพลง)" : " " + d.getString(MediaMetadata.METADATA_KEY_TITLE)).append('\n');
            }
        } catch (SecurityException e) { s.append("เซสชันเพลง: อ่านไม่ได้เพราะยังไม่มีสิทธิ์อ่านการแจ้งเตือน\n");
        } catch (Throwable e) { s.append("เซสชันเพลง: ").append(e).append('\n'); }
        StringBuilder apps = new StringBuilder();
        try {
            for (PackageInfo p : c.getPackageManager().getInstalledPackages(0)) {
                String n = p.packageName.toLowerCase(Locale.US);
                if ((n.contains("bt") || n.contains("blue") || n.contains("music") || n.contains("a2dp") || n.contains("avrcp") || n.contains("audio")) && apps.length() < 420) apps.append(' ').append(p.packageName);
            }
        } catch (Throwable ignored) { }
        s.append("แอปที่เกี่ยวกับบลูทูธ/เพลงในเครื่อง:").append(apps.length() == 0 ? " (ไม่พบ)" : apps).append('\n');
        s.append("บันทึกล่าสุด:\n");
        for (int i = Math.max(0, log.size() - 12); i < log.size(); i++) s.append(" ").append(log.get(i)).append('\n');
        s.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.MODEL);
        return s.toString();
    }
}
