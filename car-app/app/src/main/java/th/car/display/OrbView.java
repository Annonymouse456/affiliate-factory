package th.car.display;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The co-pilot's button: a glowing orb that shows what it is doing. It breathes slowly when idle,
 * sends out rings while listening, turns an arc while thinking, and pulses with bars while speaking.
 */
public class OrbView extends View {
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;

    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path spark = new Path();
    private final RectF box = new RectF();
    private final int[] theme;
    private int state = IDLE;

    public OrbView(Context c, int[] theme) {
        super(c);
        this.theme = theme;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ink.setColor(0xFFFFFFFF);
    }

    void setState(int s) { if (s != state) { state = s; invalidate(); } }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        core.setShader(new LinearGradient(0, 0, w, h, new int[]{theme[0], theme[1], theme[2]}, null, Shader.TileMode.CLAMP));
    }

    private void star(float x, float y, float r) {
        float k = r * 0.24f;
        spark.moveTo(x, y - r); spark.lineTo(x + k, y - k); spark.lineTo(x + r, y); spark.lineTo(x + k, y + k);
        spark.lineTo(x, y + r); spark.lineTo(x - k, y + k); spark.lineTo(x - r, y); spark.lineTo(x - k, y - k); spark.close();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, full = Math.min(w, h) / 2f, r = full * 0.66f;
        float t = (SystemClock.uptimeMillis() % 100000) / 1000f;
        int colour = state == LISTENING ? 0xFFFF5A6E : state == THINKING ? 0xFFFBBF24 : state == SPEAKING ? 0xFF46DBA0 : theme[0];
        ring.setColor(colour);

        if (state == LISTENING) {                              // rings travelling outward
            for (int i = 0; i < 3; i++) {
                float p = (t * 0.9f + i / 3f) % 1f;
                ring.setStrokeWidth(full * 0.045f);
                ring.setAlpha((int) (200 * (1 - p)));
                c.drawCircle(cx, cy, r + (full - r) * p, ring);
            }
        } else if (state == THINKING) {                        // two arcs chasing each other
            ring.setStrokeWidth(full * 0.07f);
            ring.setAlpha(230);
            box.set(cx - full * 0.9f, cy - full * 0.9f, cx + full * 0.9f, cy + full * 0.9f);
            c.drawArc(box, (t * 260) % 360, 80, false, ring);
            c.drawArc(box, (t * 260 + 180) % 360, 80, false, ring);
        } else if (state == SPEAKING) {                        // bars around the rim, rising and falling
            ring.setStrokeWidth(full * 0.06f);
            ring.setAlpha(230);
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16;
                float len = full * (0.08f + 0.2f * (0.5f + 0.5f * (float) Math.sin(t * 9 + i * 1.7)));
                float r0 = r + full * 0.07f;
                c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0, cx + (float) Math.cos(a) * (r0 + len), cy + (float) Math.sin(a) * (r0 + len), ring);
            }
        } else {                                               // idle: a soft halo that breathes
            ring.setStrokeWidth(full * 0.16f);
            ring.setAlpha((int) (44 + 26 * Math.sin(t * 2.1)));
            c.drawCircle(cx, cy, r + full * 0.1f, ring);
        }

        float beat = state == IDLE ? 1f + 0.025f * (float) Math.sin(t * 2.1) : 1f;
        c.drawCircle(cx, cy, r * beat, core);
        spark.reset();
        star(cx - r * 0.08f, cy + r * 0.06f, r * 0.5f);
        star(cx + r * 0.36f, cy - r * 0.36f, r * 0.19f);
        c.drawPath(spark, ink);
        postInvalidateDelayed(state == IDLE ? 250 : 40);
    }
}
