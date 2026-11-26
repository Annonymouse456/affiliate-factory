package th.car.display;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/**
 * Records one spoken question from the head unit's microphone and returns it as a WAV file.
 * Stops by itself once the speaker goes quiet, when stop() is called, or after twelve seconds.
 *
 * Made for a moving car: wind and road noise sit mostly below the voice, so the recording is passed
 * through a high-pass filter before anything else. The quiet/loud decision is made on the filtered
 * sound and follows the cabin's noise level as it changes, and the device's own noise suppressor
 * and gain control are switched on where the head unit has them.
 */
final class Recorder {

    private static final int RATE = 16000;
    private volatile boolean stopNow;

    void stop() { stopNow = true; }

    /** Blocking. Returns null when nothing was said. Throws when the microphone cannot be opened. */
    byte[] record() throws Exception {
        stopNow = false;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("ไมค์ของจอไม่รองรับการอัดเสียง");
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) { mic.release(); throw new IllegalStateException("เปิดไมค์ไม่ได้ ตรวจสิทธิ์ไมโครโฟนของแอป"); }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        android.media.audiofx.AudioEffect suppressor = null, gainControl = null;
        try {
            // The head unit's own clean-up, when it has any. Many do not; the filter below does not depend on it.
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) { suppressor = android.media.audiofx.NoiseSuppressor.create(mic.getAudioSessionId()); if (suppressor != null) suppressor.setEnabled(true); } } catch (Throwable ignored) { }
            try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) { gainControl = android.media.audiofx.AutomaticGainControl.create(mic.getAudioSessionId()); if (gainControl != null) gainControl.setEnabled(true); } } catch (Throwable ignored) { }
            mic.startRecording();
            short[] frame = new short[RATE / 50];                 // 20 ms

            // High-pass filter at 170 Hz (second order): removes wind rumble and road drone, keeps the voice.
            double w0 = 2 * Math.PI * 170 / RATE, cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * 0.707), a0 = 1 + alpha;
            double b0 = (1 + cos) / 2 / a0, b1 = -(1 + cos) / a0, b2 = b0, a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
            double x1 = 0, x2 = 0, y1 = 0, y2 = 0;

            double noise = 0;
            int frames = 0, loudRun = 0, quietRun = 0;
            boolean speaking = false;
            while (!stopNow && frames < 50 * 12) {
                int n = mic.read(frame, 0, frame.length);
                if (n <= 0) break;
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    double x = frame[i], y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                    x2 = x1; x1 = x; y2 = y1; y1 = y;
                    int v = (int) Math.max(-32768, Math.min(32767, Math.round(y)));
                    sum += (double) v * v;
                    pcm.write(v & 0xff);
                    pcm.write((v >> 8) & 0xff);
                }
                double level = Math.sqrt(sum / n);
                frames++;
                if (frames <= 15) { noise = Math.max(noise, level); continue; }      // first 0.3 s measures cabin noise
                boolean loud = level > Math.max(300, noise * 2.0);
                // Follow the cabin: drop quickly when it gets quieter, creep up when a steady noise (wind, rough road) sets in.
                if (!loud) noise = level < noise ? noise * 0.9 + level * 0.1 : noise * 0.98 + level * 0.02;
                else if (!speaking) noise = noise * 0.995 + level * 0.005;
                if (loud) { loudRun++; quietRun = 0; if (loudRun >= 3) speaking = true; }
                else { loudRun = 0; quietRun++; }
                if (speaking && quietRun > 70) break;             // 1.4 s of quiet after speech: done
                if (!speaking && frames > 50 * 6) return null;    // six seconds and nobody spoke
            }
            if (!speaking && !stopNow) return null;
        } finally {
            try { mic.stop(); } catch (Throwable ignored) { }
            try { if (suppressor != null) suppressor.release(); } catch (Throwable ignored) { }
            try { if (gainControl != null) gainControl.release(); } catch (Throwable ignored) { }
            mic.release();
        }
        return wav(louder(pcm.toByteArray()));
    }

    /**
     * Bring a quiet recording up to a healthy level. Head-unit microphones are often faint; speech
     * recognition does much better when the loudest syllable sits near full scale. Gain is capped
     * so that a recording of nothing but hiss is not blown up into noise.
     */
    private static byte[] louder(byte[] pcm) {
        int peak = 1;
        for (int i = 0; i + 1 < pcm.length; i += 2) peak = Math.max(peak, Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8))));
        float gain = Math.min(10f, 29000f / peak);
        if (gain <= 1.15f) return pcm;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = Math.round(((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8))) * gain);
            v = Math.max(-32768, Math.min(32767, v));
            pcm[i] = (byte) (v & 0xff);
            pcm[i + 1] = (byte) ((v >> 8) & 0xff);
        }
        return pcm;
    }

    private static byte[] wav(byte[] pcm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        write(out, "RIFF"); le32(out, 36 + pcm.length); write(out, "WAVE");
        write(out, "fmt "); le32(out, 16); le16(out, 1); le16(out, 1); le32(out, RATE); le32(out, RATE * 2); le16(out, 2); le16(out, 16);
        write(out, "data"); le32(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream o, String s) { for (int i = 0; i < s.length(); i++) o.write(s.charAt(i)); }
    private static void le16(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
    private static void le32(ByteArrayOutputStream o, int v) { le16(o, v & 0xffff); le16(o, (v >> 16) & 0xffff); }
}
