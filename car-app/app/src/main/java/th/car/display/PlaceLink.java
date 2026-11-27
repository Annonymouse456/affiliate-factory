package th.car.display;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a maps app shares ("Place name" and a link, or just a link, or bare coordinates) into
 * a point on the map. Short share links say nothing by themselves, so they are followed to the long
 * address they stand for, which carries the coordinates.
 */
final class PlaceLink {

    static final class Place {
        double lat, lon;
        boolean exact;          // false: only a name was found, to be searched for
        String name = "";
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");
    private static final String N = "(-?\\d{1,3}\\.\\d{3,})";
    /** Most trustworthy first: the place's own pin, then an explicit query, then the map's centre. */
    private static final Pattern[] COORDS = {
            Pattern.compile("!3d" + N + "!4d" + N),
            Pattern.compile("[?&](?:q|query|ll|sll|destination|daddr|center)=(?:loc:)?" + N + "\\s*,\\s*\\+?" + N),
            Pattern.compile("/(?:place|search|dir)/" + N + ",\\+?" + N),
            Pattern.compile("@" + N + "," + N),
    };
    private static final Pattern PLACE_NAME = Pattern.compile("/place/([^/@?]+)");
    private static final Pattern BARE = Pattern.compile("^\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

    /** Coordinates written in an address, or null. */
    static double[] coords(String address) {
        String s = address;
        try { s = URLDecoder.decode(address.replace("+", "%2B"), "UTF-8").replace("%2B", "+"); } catch (Throwable ignored) { }
        for (Pattern p : COORDS) {
            Matcher m = p.matcher(s);
            if (!m.find()) continue;
            double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0)) return new double[]{lat, lon};
        }
        return null;
    }

    static String nameIn(String address) {
        Matcher m = PLACE_NAME.matcher(address);
        if (!m.find()) return "";
        try {
            String name = URLDecoder.decode(m.group(1), "UTF-8").trim();
            return BARE.matcher(name).matches() ? "" : name;
        } catch (Throwable e) { return ""; }
    }

    /** Works out the place. Uses the network for links; not for the main thread. Throws with a plain reason when nothing usable is found. */
    static Place resolve(String shared) throws Exception {
        Place place = new Place();
        String text = shared == null ? "" : shared.trim();
        Matcher bare = BARE.matcher(text);
        if (bare.matches()) { place.lat = Double.parseDouble(bare.group(1)); place.lon = Double.parseDouble(bare.group(2)); place.exact = true; return place; }
        Matcher link = URL_IN_TEXT.matcher(text);
        if (!link.find()) {                       // no link at all: treat the words as a place to search for
            if (text.isEmpty()) throw new IllegalStateException("ยังไม่ได้วางลิงก์");
            place.name = text;
            return place;
        }
        String address = link.group();
        String before = text.substring(0, link.start()).trim();
        if (!before.isEmpty()) place.name = before.split("\\n")[0].trim();      // maps apps put the place's name on the line above the link

        for (int hop = 0; hop < 7; hop++) {
            double[] at = coords(address);
            if (place.name.isEmpty()) place.name = nameIn(address);
            if (at != null) { place.lat = at[0]; place.lon = at[1]; place.exact = true; return place; }
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "th,en;q=0.8");
            int status = c.getResponseCode();
            String next = c.getHeaderField("Location");
            if (status >= 300 && status < 400 && next != null) {
                address = new URL(new URL(address), next).toString();
                c.disconnect();
                continue;
            }
            if (status == 200) {                  // no further address to follow: look inside the page itself
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; out.size() < 600000 && (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                in.close();
                String page = out.toString("UTF-8").replace("\\u003d", "=").replace("\\u0026", "&").replace("&amp;", "&");
                at = coords(page);
                if (at != null) { place.lat = at[0]; place.lon = at[1]; place.exact = true; return place; }
            }
            c.disconnect();
            break;
        }
        if (!place.name.isEmpty()) return place;  // no coordinates, but a name to search for
        throw new IllegalStateException("อ่านตำแหน่งจากลิงก์นี้ไม่ได้");
    }
}
