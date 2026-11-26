package th.car.display;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Speech in and out through the OpenAI audio API: Thai speech to text, and text to spoken Thai.
 * Each call tries the current model first and an older one if the account cannot use it.
 */
final class Speech {

    private static final String[] LISTEN_MODELS = {"gpt-4o-mini-transcribe", "whisper-1"};
    private static final String[] SPEAK_MODELS = {"gpt-4o-mini-tts", "tts-1"};

    static final class Failure extends Exception { Failure(String thaiReason) { super(thaiReason); } }

    private final String apiKey;

    Speech(String apiKey) { this.apiKey = apiKey; }

    /** WAV audio in, the words out. */
    String transcribe(byte[] wav) throws Exception {
        Http.Reply r = null;
        for (String model : LISTEN_MODELS) {
            String boundary = "----car" + System.nanoTime();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            field(body, boundary, "model", model);
            field(body, boundary, "language", "th");
            field(body, boundary, "response_format", "json");
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(Http.UTF8));
            body.write(wav);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(Http.UTF8));
            r = Http.send("POST", "https://api.openai.com/v1/audio/transcriptions", new String[]{"Authorization", "Bearer " + apiKey},
                    "multipart/form-data; boundary=" + boundary, body.toByteArray(), 45000);
            if (r.ok()) return new JSONObject(r.text()).optString("text").trim();
            if (r.code != 400 && r.code != 404) break;        // only a model the account lacks is worth a second try
        }
        throw new Failure(reason(r, "ฟังเสียง"));
    }

    /** Text in, MP3 audio out, in a man's or a woman's voice. */
    byte[] speak(String text, boolean female) throws Exception {
        Http.Reply r = null;
        for (String model : SPEAK_MODELS) {
            JSONObject body = new JSONObject().put("model", model).put("voice", female ? "nova" : "onyx").put("input", text).put("response_format", "mp3");
            // The newer model takes direction in words; the older one ignores this field's absence.
            if (model.startsWith("gpt-4o")) body.put("instructions", "Speak Thai naturally, in a friendly and clear tone, at a brisk pace, a little faster than normal conversation.");
            r = Http.send("POST", "https://api.openai.com/v1/audio/speech", new String[]{"Authorization", "Bearer " + apiKey},
                    "application/json", body.toString().getBytes(Http.UTF8), 60000);
            if (r.ok() && r.body.length > 0) return r.body;
            if (r.code != 400 && r.code != 404) break;
        }
        throw new Failure(reason(r, "พูดตอบ"));
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(Http.UTF8));
    }

    private static String reason(Http.Reply r, String what) {
        if (r == null) return what + "ไม่ได้";
        if (r.code == 401 || r.code == 403) return "คีย์ OpenAI ใช้ไม่ได้ ตรวจคีย์ในตั้งค่า AI";
        if (r.code == 429) return "โควตา OpenAI หมดหรือใช้ถี่เกินไป";
        return what + "ไม่ได้ (" + r.code + ")";
    }
}
