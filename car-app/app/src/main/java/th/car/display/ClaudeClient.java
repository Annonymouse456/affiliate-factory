package th.car.display;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Talks to Claude through the Messages API over plain HTTPS.
 *
 * Written against the raw HTTP API rather than the Java SDK: the SDK targets the desktop/server JVM,
 * and this has to run on an Android 8.1 head unit where it cannot be tried in advance. Plain
 * HttpURLConnection and org.json behave the same on the JVM and on the device.
 *
 * One call to ask() is one spoken exchange: it runs the tool loop until Claude gives its answer.
 */
final class ClaudeClient {

    static final String MODEL = "claude-opus-5-5";
    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2026-10-21";
    private static final int MAX_STEPS = 8;

    /** The app's side of the tools Claude may call. Runs on the worker thread. */
    interface Tools {
        /** Returns the tool_result content: a String, or a JSONArray of content blocks (e.g. an image plus text). */
        Object run(String name, JSONObject input) throws Exception;
    }

    static final class Answer {
        String text = "";
        String problem;          // short Thai reason when there is no answer
        int inputTokens, outputTokens, searches;
    }

    private static final String SYSTEM =
            "You are the voice co-pilot built into a car's dashboard display in Thailand. The driver talks to you while driving and hears your reply read aloud by a speech engine.\n\n"
            + "How to answer:\n"
            + "- Reply in Thai, in plain spoken sentences. Your words are converted to speech, so write nothing that cannot be said aloud: no lists, headings, links, symbols or emoji.\n"
            + "- Keep it short enough to take in while driving: usually one to three sentences. Give the single most useful thing first. The driver can ask a follow-up.\n"
            + "- Never ask the driver to look at or read the screen.\n\n"
            + "What you know: each request starts with a block of live readings from the car (position, road, heading, speed, trip so far, time, and the track playing). Treat it as the present moment and place. Distances and directions should be relative to the car.\n\n"
            + "Tools: use web search for anything current, such as road closures, accidents, roadworks, flooding, weather, opening hours or news about the road ahead. Search in Thai when the subject is local. Use nearby_places for things around the car, look_at_road when the driver asks about something they can see ahead, navigate_to, play_music and open_app to act on the head unit when the driver asks you to take them somewhere, play something or open an app, and the control tools when they ask you to change the display or the phone's music. When a tool reports that it could not do something, tell the driver plainly rather than implying it worked.\n\n"
            + "Being straight with the driver matters more than sounding helpful, because they may change their route on what you say:\n"
            + "- When you report something from the web, say how old it is, for example that it was reported this morning or two days ago. If you cannot tell how recent it is, say so.\n"
            + "- Police checkpoints are rarely published anywhere you can search. If asked, report only what you actually found and say plainly that you cannot see live checkpoint reports.\n"
            + "- If a search finds nothing relevant, say that you found no reports, which is different from saying the road is clear.\n"
            + "- What you see through the camera is a single still frame a moment old. Describe it; do not tell the driver it is safe to proceed, overtake or turn.";

    private final String apiKey;

    ClaudeClient(String apiKey) { this.apiKey = apiKey; }

    Answer ask(String carReadings, String question, String country, Tools tools) {
        Answer answer = new Answer();
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content",
                    "<car_readings>\n" + carReadings + "\n</car_readings>\n\n" + question));

            for (int step = 0; step < MAX_STEPS; step++) {
                JSONObject reply = send(messages, country, answer);
                if (reply == null) return answer;
                JSONArray content = reply.optJSONArray("content");
                if (content == null) content = new JSONArray();
                String stop = reply.optString("stop_reason");
                JSONObject usage = reply.optJSONObject("usage");
                if (usage != null) {
                    answer.inputTokens += usage.optInt("input_tokens") + usage.optInt("cache_read_input_tokens") + usage.optInt("cache_creation_input_tokens");
                    answer.outputTokens += usage.optInt("output_tokens");
                    JSONObject serverTools = usage.optJSONObject("server_tool_use");
                    if (serverTools != null) answer.searches += serverTools.optInt("web_search_requests");
                }

                if ("refusal".equals(stop)) { answer.problem = "เรื่องนี้ตอบไม่ได้ครับ"; return answer; }

                if ("tool_use".equals(stop)) {
                    // Send the assistant turn back exactly as received (it carries thinking blocks), then every result in one user message.
                    appendAssistant(messages, content);
                    JSONArray results = new JSONArray();
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject block = content.getJSONObject(i);
                        if (!"tool_use".equals(block.optString("type"))) continue;
                        JSONObject result = new JSONObject().put("type", "tool_result").put("tool_use_id", block.getString("id"));
                        try {
                            JSONObject input = block.optJSONObject("input");
                            result.put("content", tools.run(block.getString("name"), input == null ? new JSONObject() : input));
                        } catch (Throwable e) {
                            result.put("content", "Tool failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage())).put("is_error", true);
                        }
                        results.put(result);
                    }
                    messages.put(new JSONObject().put("role", "user").put("content", results));
                    continue;
                }

                if ("pause_turn".equals(stop)) {     // a long server-side search paused: resend as is and it resumes
                    appendAssistant(messages, content);
                    continue;
                }

                StringBuilder said = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.getJSONObject(i);
                    if ("text".equals(block.optString("type"))) said.append(block.optString("text"));
                }
                answer.text = said.toString().trim();
                if (answer.text.isEmpty()) answer.problem = "max_tokens".equals(stop) ? "คำตอบยาวเกินไป ลองถามให้แคบลง" : "AI ไม่ได้ตอบอะไรกลับมา";
                return answer;
            }
            answer.problem = "ใช้เวลาค้นนานเกินไป ลองถามใหม่";
        } catch (Throwable e) {
            answer.problem = e instanceof java.io.IOException ? "ต่อเน็ตไม่ได้" : "ผิดพลาด: " + e.getClass().getSimpleName();
        }
        return answer;
    }

    /** Add Claude's turn to the history. A turn resumed after a pause continues the same assistant message. */
    private static void appendAssistant(JSONArray messages, JSONArray content) throws Exception {
        JSONObject last = messages.getJSONObject(messages.length() - 1);
        if ("assistant".equals(last.optString("role"))) {
            JSONArray so = last.getJSONArray("content");
            for (int i = 0; i < content.length(); i++) so.put(content.get(i));
        } else messages.put(new JSONObject().put("role", "assistant").put("content", content));
    }

    /** One request, retried once when the service is busy. Returns null after setting answer.problem. */
    private JSONObject send(JSONArray messages, String country, Answer answer) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 8000)
                .put("output_config", new JSONObject().put("effort", "low"))     // spoken replies: favour a quick answer
                .put("fallbacks", "default")                                       // if a safety filter declines, let the server route to another model
                .put("system", new JSONArray().put(new JSONObject().put("type", "text").put("text", SYSTEM)
                        .put("cache_control", new JSONObject().put("type", "ephemeral"))))
                .put("tools", toolList(country))
                .put("messages", messages);
        byte[] bytes = body.toString().getBytes(Http.UTF8);
        String[] headers = {"x-api-key", apiKey, "anthropic-version", API_VERSION, "anthropic-beta", "server-side-fallback-2026-07-01"};

        Http.Reply r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            r = Http.send("POST", URL, headers, "application/json", bytes, 120000);
            if (r.ok()) return new JSONObject(r.text());
            boolean busy = r.code == 429 || r.code >= 500;
            if (!busy) break;
            Thread.sleep(2500);
        }
        String detail = "";
        try { detail = new JSONObject(r.text()).optJSONObject("error").optString("message"); } catch (Throwable ignored) { }
        if (r.code == 401 || r.code == 403) answer.problem = "คีย์ Claude ใช้ไม่ได้ ตรวจคีย์ในตั้งค่า AI";
        else if (r.code == 429) answer.problem = "ใช้ AI ถี่เกินไป รอสักครู่";
        else if (r.code >= 500) answer.problem = "ระบบ AI ไม่ว่าง ลองใหม่อีกครั้ง";
        else answer.problem = "AI ปฏิเสธคำขอ (" + r.code + ") " + (detail.length() > 120 ? detail.substring(0, 120) : detail);
        return null;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required) throws Exception {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        schema.put("required", req);
        return new JSONObject().put("name", name).put("description", description).put("input_schema", schema);
    }

    private static JSONObject prop(String type, String description, String... choices) throws Exception {
        JSONObject p = new JSONObject().put("type", type).put("description", description);
        if (choices.length > 0) { JSONArray e = new JSONArray(); for (String c : choices) e.put(c); p.put("enum", e); }
        return p;
    }

    private static JSONArray toolList(String country) throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(new JSONObject().put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 4)
                .put("user_location", new JSONObject().put("type", "approximate").put("country", country).put("timezone", "Asia/Bangkok")));
        tools.put(tool("nearby_places",
                "Find places of one kind around the car's current position from OpenStreetMap. Returns up to six, nearest first, each with its name, distance in kilometres and compass direction from the car. Coverage in Thailand is uneven, so an empty result does not mean there are none; fall back to web search when it matters.",
                new JSONObject()
                        .put("kind", prop("string", "What to look for.", "fuel", "food", "cafe", "convenience_store", "toilet", "hospital", "parking", "atm", "ev_charging", "hotel"))
                        .put("radius_km", prop("number", "Search radius in kilometres, from 1 to 25. Use about 5 in towns and 20 on open highway.")),
                "kind", "radius_km"));
        tools.put(tool("look_at_road",
                "Get one still frame of what the forward dashcam shows right now. Use it when the driver asks about something visible ahead: a sign, a shop, a landmark, the weather. The frame is a moment old by the time you see it.",
                new JSONObject()));
        tools.put(tool("navigate_to",
                "Start turn-by-turn navigation on the head unit's own maps app. Use it when the driver asks to be taken somewhere, including right after you found the place for them. Give a place name with its town, or exact coordinates from nearby_places. The maps app comes to the front of the screen.",
                new JSONObject().put("destination", prop("string", "Place name and town in Thai or English, or 'latitude,longitude'.")), "destination"));
        tools.put(tool("play_music",
                "Ask a music app installed on the head unit to search for and play something. This plays from the head unit itself, not from the driver's phone; for the phone's music use media_control. Whether it works depends on which music apps are installed.",
                new JSONObject().put("query", prop("string", "Artist, song, album or genre to play.")), "query"));
        tools.put(tool("open_app",
                "Bring an app installed on the head unit to the front, by the name on its icon. If no app matches, the result lists the apps that are installed so you can pick the right one or tell the driver.",
                new JSONObject().put("name", prop("string", "App name or part of it, for example YouTube, Maps, Radio or the dashcam app.")), "name"));
        tools.put(tool("set_speed_limit",
                "Set the speed-limit sign on the display. The display warns the driver above this speed.",
                new JSONObject().put("kmh", prop("integer", "Limit in km/h, 20 to 130.")), "kmh"));
        tools.put(tool("set_path_overlay",
                "Show or hide the blue lane band drawn over the camera picture.",
                new JSONObject().put("visible", prop("boolean", "true to show it, false to hide it.")), "visible"));
        tools.put(tool("media_control",
                "Control the music playing from the driver's phone over Bluetooth.",
                new JSONObject().put("action", prop("string", "What to do.", "next", "previous", "play_pause")), "action"));
        return tools;
    }
}
