package th.car.display;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Name and posted speed limit of the road at a position, from OpenStreetMap through the public
 * Overpass service. Needs internet. The position is sent to that service to make the lookup, and
 * nothing else is. Many Thai roads have no speed limit recorded, so the limit is often absent.
 */
class RoadInfo {

    static class Result {
        String name;            // null when the road has no name recorded
        int limit = -1;         // km/h, -1 when not recorded
        long wayId;
        String error;           // short reason when the lookup failed
    }

    interface Done { void on(Result r); }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final String[] SERVERS = {"https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"};

    static void lookup(final double lat, final double lon, final Handler ui, final Done done) {
        worker.execute(new Runnable() {
            @Override public void run() {
                Result r = null;
                String problem = "ไม่มีเน็ต";
                for (String server : SERVERS) {
                    try { r = ask(server, lat, lon); break; } catch (Throwable e) { problem = e.getClass().getSimpleName(); }
                }
                if (r == null) { r = new Result(); r.error = problem; }
                final Result out = r;
                ui.post(new Runnable() { @Override public void run() { done.on(out); } });
            }
        });
    }

    private static Result ask(String server, double lat, double lon) throws Exception {
        String query = String.format(Locale.US, "[out:json][timeout:8];way(around:30,%.5f,%.5f)[highway];out tags 12;", lat, lon);
        HttpURLConnection c = (HttpURLConnection) new URL(server + "?data=" + URLEncoder.encode(query, "UTF-8")).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "car-display-app/1.3");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) { bytes.write(buffer, 0, n); if (bytes.size() > 300000) break; }
            return pick(new JSONObject(bytes.toString("UTF-8")).optJSONArray("elements"));
        } finally { c.disconnect(); }
    }

    /** Of the ways near the position, take the most major drivable one: that is nearly always the one being driven. */
    static Result pick(JSONArray ways) {
        Result best = new Result();
        float bestRank = -1;
        for (int i = 0; ways != null && i < ways.length(); i++) {
            JSONObject way = ways.optJSONObject(i);
            JSONObject tags = way == null ? null : way.optJSONObject("tags");
            if (tags == null) continue;
            float rank = rank(tags.optString("highway"));
            if (rank <= bestRank) continue;
            bestRank = rank;
            best.wayId = way.optLong("id");
            best.name = firstText(tags.optString("name", null), tags.optString("name:th", null), tags.optString("name:en", null),
                    tags.has("ref") ? "ทางหลวง " + tags.optString("ref") : null);
            best.limit = number(tags.optString("maxspeed", null));
        }
        if (bestRank < 0) best.error = "ไม่พบถนนในแผนที่";
        return best;
    }

    private static float rank(String kind) {
        if (kind == null) return -1;
        float link = kind.endsWith("_link") ? -0.5f : 0;
        String k = kind.replace("_link", "");
        switch (k) {
            case "motorway": return 10 + link;
            case "trunk": return 9 + link;
            case "primary": return 8 + link;
            case "secondary": return 7 + link;
            case "tertiary": return 6 + link;
            case "unclassified": return 5;
            case "residential": return 4;
            case "living_street": return 3;
            case "service": return 2;
            default: return -1;            // footways, cycleways, tracks, roads under construction
        }
    }

    private static String firstText(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }

    /** "80", "80 km/h" → 80; anything else (e.g. "TH:urban") → -1. Values in mph are not used in Thailand. */
    private static int number(String maxspeed) {
        if (maxspeed == null) return -1;
        int n = 0, digits = 0;
        for (int i = 0; i < maxspeed.length() && Character.isDigit(maxspeed.charAt(i)); i++) { n = n * 10 + (maxspeed.charAt(i) - '0'); digits++; }
        return digits > 0 && n >= 10 && n <= 130 && !maxspeed.contains("mph") ? n : -1;
    }
}
