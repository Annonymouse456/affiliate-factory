package th.car.display;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Settings kept in one JSON file, with the same interface as Android's own preferences.
 *
 * Used on head units whose private app folder cannot be written, where the normal preferences
 * silently lose everything when the app closes. The file lives in the app's folder on shared
 * storage, which is less private: on Android 8 other apps holding the storage permission can read it.
 */
final class FilePrefs implements SharedPreferences {
    private final File file;
    private final Map<String, Object> values = new HashMap<>();

    FilePrefs(File file) {
        this.file = file;
        try {
            if (file.exists()) {
                FileInputStream in = new FileInputStream(file);
                byte[] all = new byte[(int) file.length()];
                int got = 0;
                while (got < all.length) { int n = in.read(all, got, all.length - got); if (n < 0) break; got += n; }
                in.close();
                JSONObject o = new JSONObject(new String(all, 0, got, "UTF-8"));
                JSONArray names = o.names();
                for (int i = 0; names != null && i < names.length(); i++) { String k = names.getString(i); values.put(k, o.get(k)); }
            }
        } catch (Throwable ignored) { }      // unreadable file: start empty
    }

    private synchronized boolean write() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Object> e : values.entrySet()) o.put(e.getKey(), e.getValue());
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(file);
            out.write(o.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            return true;
        } catch (Throwable e) { return false; }
    }

    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(values); }
    @Override public synchronized String getString(String k, String d) { Object v = values.get(k); return v instanceof String ? (String) v : d; }
    @Override public synchronized int getInt(String k, int d) { Object v = values.get(k); return v instanceof Number ? ((Number) v).intValue() : d; }
    @Override public synchronized long getLong(String k, long d) { Object v = values.get(k); return v instanceof Number ? ((Number) v).longValue() : d; }
    @Override public synchronized float getFloat(String k, float d) { Object v = values.get(k); return v instanceof Number ? ((Number) v).floatValue() : d; }
    @Override public synchronized boolean getBoolean(String k, boolean d) { Object v = values.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    @Override public synchronized boolean contains(String k) { return values.containsKey(k); }
    @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, Object> put = new HashMap<>();
            private final Set<String> gone = new HashSet<>();
            private boolean wipe;
            @Override public Editor putString(String k, String v) { put.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { put.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { put.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { put.put(k, (double) v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { put.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { return this; }
            @Override public Editor remove(String k) { gone.add(k); return this; }
            @Override public Editor clear() { wipe = true; return this; }
            @Override public boolean commit() {
                synchronized (FilePrefs.this) {
                    if (wipe) values.clear();
                    for (String k : gone) values.remove(k);
                    for (Map.Entry<String, Object> e : put.entrySet()) { if (e.getValue() == null) values.remove(e.getKey()); else values.put(e.getKey(), e.getValue()); }
                    return write();
                }
            }
            @Override public void apply() { commit(); }
        };
    }
}
