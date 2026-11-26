package th.car.display;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

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
                    try { r = ask(server, lat, lon); break; } catch (Throwable e) { problem = e instanceof java.net.SocketTimeoutException ? "บริการแผนที่ตอบช้า" : e instanceof java.io.IOException ? "ต่อบริการแผนที่ไม่ได้" : e.getClass().getSimpleName(); }
                }
                if (r == null) { r = new Result(); r.error = problem; }
                final Result out = r;
                ui.post(new Runnable() { @Override public void run() { done.on(out); } });
            }
        });
    }

    private static Result ask(String server, double lat, double lon) throws Exception {
        String query = String.format(Locale.US, "[out:json][timeout:8];way(around:30,%.5f,%.5f)[highway];out tags 12;", lat, lon);
        return pick(overpass(server, query).optJSONArray("elements"));
    }

    private static JSONObject overpass(String server, String query) throws Exception {
        Http.Reply r = Http.send("GET", server + "?data=" + URLEncoder.encode(query, "UTF-8"), new String[]{"User-Agent", "car-display-app/2.0"}, null, null, 25000);
        if (!r.ok()) throw new java.io.IOException("HTTP " + r.code);
        return new JSONObject(r.text());
    }

    /** Places of one kind around a position, nearest first, as plain text for the co-pilot. Blocking. */
    static String nearby(android.location.Location at, String kind, double radiusKm) {
        String filter;
        switch (kind == null ? "" : kind) {
            case "fuel": filter = "[amenity=fuel]"; break;
            case "food": filter = "[amenity~\"^(restaurant|fast_food|food_court)$\"]"; break;
            case "cafe": filter = "[amenity=cafe]"; break;
            case "convenience_store": filter = "[shop=convenience]"; break;
            case "toilet": filter = "[amenity=toilets]"; break;
            case "hospital": filter = "[amenity~\"^(hospital|clinic)$\"]"; break;
            case "parking": filter = "[amenity=parking]"; break;
            case "atm": filter = "[amenity~\"^(atm|bank)$\"]"; break;
            case "ev_charging": filter = "[amenity=charging_station]"; break;
            case "hotel": filter = "[tourism~\"^(hotel|guest_house|motel)$\"]"; break;
            default: return "Unknown kind of place.";
        }
        int metres = (int) Math.round(Math.max(1, Math.min(25, radiusKm)) * 1000);
        String around = String.format(Locale.US, "(around:%d,%.5f,%.5f)", metres, at.getLatitude(), at.getLongitude());
        String query = "[out:json][timeout:10];(node" + around + filter + ";way" + around + filter + ";);out center tags 40;";
        JSONArray found = null;
        String problem = "no connection";
        for (String server : SERVERS) {
            try { found = overpass(server, query).optJSONArray("elements"); break; } catch (Throwable e) { problem = e.getClass().getSimpleName(); }
        }
        if (found == null) return "The map service could not be reached (" + problem + ").";
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        String[] compass = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        for (int i = 0; i < found.length(); i++) {
            JSONObject e = found.optJSONObject(i);
            if (e == null) continue;
            JSONObject centre = e.optJSONObject("center");
            double lat = centre != null ? centre.optDouble("lat") : e.optDouble("lat"), lon = centre != null ? centre.optDouble("lon") : e.optDouble("lon");
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            float[] d = new float[2];
            android.location.Location.distanceBetween(at.getLatitude(), at.getLongitude(), lat, lon, d);
            JSONObject tags = e.optJSONObject("tags");
            String name = tags == null ? null : firstText(tags.optString("name", null), tags.optString("name:th", null), tags.optString("brand", null), tags.optString("operator", null));
            String hours = tags == null ? "" : tags.optString("opening_hours", "");
            rows.add(new String[]{String.format(Locale.US, "%09.0f", d[0]),
                    (name == null ? "(unnamed)" : name) + String.format(Locale.US, ", %.1f km to the %s", d[0] / 1000f, compass[Math.round(((d[1] + 360) % 360) / 45f) % 8])
                            + (hours.isEmpty() ? "" : ", hours " + hours)});
        }
        if (rows.isEmpty()) return "OpenStreetMap lists none within " + (metres / 1000) + " km. Its coverage here may be incomplete.";
        java.util.Collections.sort(rows, new java.util.Comparator<String[]>() { @Override public int compare(String[] x, String[] y) { return x[0].compareTo(y[0]); } });
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(6, rows.size()); i++) out.append(rows.get(i)[1]).append('\n');
        return out.toString().trim();
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
