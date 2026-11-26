package th.car.display;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds a cover picture for a track when Bluetooth does not send one (this head unit's Bluetooth
 * version carries title and artist only). Looks the track up in the public iTunes Search service
 * by title and artist; only those two strings are sent. A wrong or missing match is possible,
 * mostly for remixes and uploads with unusual titles.
 */
final class CoverArt {

    interface Done { void on(Bitmap cover); }      // null when nothing was found

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    static void find(final String title, final String artist, final Handler ui, final Done done) {
        worker.execute(new Runnable() {
            @Override public void run() {
                Bitmap found = null;
                try {
                    found = search(clean(title) + " " + clean(artist));
                    if (found == null) found = search(clean(title));
                } catch (Throwable ignored) { }
                final Bitmap out = found;
                ui.post(new Runnable() { @Override public void run() { done.on(out); } });
            }
        });
    }

    /** Drop bracketed extras such as "(Remix)" or "[Official MV]" that keep a search from matching. */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\(.*?\\)|\\[.*?\\]|\\|.*$", " ").replaceAll("\\s+", " ").trim();
    }

    private static Bitmap search(String term) throws Exception {
        if (term.trim().isEmpty()) return null;
        String[] agent = {"User-Agent", "car-display-app/3.3"};
        Http.Reply r = Http.send("GET", "https://itunes.apple.com/search?media=music&entity=song&limit=1&country=th&term=" + URLEncoder.encode(term, "UTF-8"), agent, null, null, 12000);
        if (!r.ok()) return null;
        JSONArray results = new JSONObject(r.text()).optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        String url = results.getJSONObject(0).optString("artworkUrl100", "");
        if (url.isEmpty()) return null;
        Http.Reply image = Http.send("GET", url.replace("100x100", "300x300"), agent, null, null, 15000);
        if (!image.ok() || image.body.length == 0) return null;
        return BitmapFactory.decodeByteArray(image.body, 0, image.body.length);
    }

    private CoverArt() { }
}
