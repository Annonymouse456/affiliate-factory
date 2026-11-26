package th.car.display;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * The speedometer: a glowing arc that fills with speed, the number in the middle, a red marker at
 * the set limit, and the road name in the gap at the bottom. The needle of light eases toward each
 * new reading and sweeps the dial once when the display starts. It redraws only while something moves.
 */
public class GaugeView extends View {
    private static final float MAX = 160f, START = 135f, SWEEP = 270f;

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
            tick = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG), road = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int[] theme;
    private SweepGradient calm, hot;
    private float target = -1, shown, cx, cy, r, stroke;
    private int limit = 80;
    private String roadName = "";
    private long introFrom;

    public GaugeView(Context c, int[] theme) {
        super(c);
        this.theme = theme;
        for (Paint p : new Paint[]{arc, glow, track}) { p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        track.setColor(0x24FFFFFF);
        tick.setColor(0x66FFFFFF);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextAlign(Paint.Align.CENTER);
        small.setTextAlign(Paint.Align.CENTER);
        small.setColor(0xFF9FB0B4);
        road.setTextAlign(Paint.Align.CENTER);
        road.setColor(0xFFF1F5F6);
        introFrom = SystemClock.uptimeMillis();
    }

    /** kmh below zero means no reading yet. */
    void setSpeed(float kmh) { if (kmh != target) { target = kmh; invalidate(); } }
    void setLimit(int kmh) { if (kmh != limit) { limit = kmh; invalidate(); } }
    void setRoad(String name) { String n = name == null ? "" : name; if (!n.equals(roadName)) { roadName = n; invalidate(); } }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float d = Math.min(w, h);
        cx = w / 2f; cy = h / 2f; r = d * 0.43f; stroke = d * 0.058f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        arc.setStrokeWidth(stroke);
        track.setStrokeWidth(stroke);
        glow.setStrokeWidth(stroke * 2.4f);
        tick.setStrokeWidth(Math.max(2f, d * 0.006f));
        big.setTextSize(d * 0.33f);
        small.setTextSize(d * 0.055f);
        road.setTextSize(d * 0.058f);
        Matrix turn = new Matrix();
        turn.setRotate(START - 8, cx, cy);                  // start the colour run where the arc starts
        calm = new SweepGradient(cx, cy, new int[]{theme[0], theme[1], theme[2], theme[2]}, new float[]{0f, 0.38f, 0.76f, 1f});
        calm.setLocalMatrix(turn);
        hot = new SweepGradient(cx, cy, new int[]{0xFFFBBF24, 0xFFF97316, 0xFFEF4444, 0xFFEF4444}, new float[]{0f, 0.3f, 0.7f, 1f});
        hot.setLocalMatrix(turn);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float aim = Math.max(0, target);
        boolean moving;
        long intro = now - introFrom;
        if (intro < 1300) {                                   // opening sweep: up the dial and back
            float t = intro / 1300f;
            shown = MAX * (t < 0.5f ? t * 2 : (1 - t) * 2);
            moving = true;
        } else {
            shown += (aim - shown) * 0.2f;
            moving = Math.abs(aim - shown) > 0.15f;
            if (!moving) shown = aim;
        }
        boolean over = target > limit + 0.5f && intro >= 1300;

        c.drawArc(oval, START, SWEEP, false, track);
        for (int v = 0; v <= MAX; v += 20) {
            double a = Math.toRadians(START + SWEEP * v / MAX);
            float in = r - stroke * 1.05f, out = r - stroke * (v % 40 == 0 ? 1.75f : 1.4f);
            c.drawLine(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in, cx + (float) Math.cos(a) * out, cy + (float) Math.sin(a) * out, tick);
        }
        float fill = SWEEP * Math.min(1f, shown / MAX);
        if (fill > 0.5f) {
            arc.setShader(over ? hot : calm);
            glow.setShader(over ? hot : calm);
            glow.setAlpha(46);
            c.drawArc(oval, START, fill, false, glow);
            c.drawArc(oval, START, fill, false, arc);
        }
        double la = Math.toRadians(START + SWEEP * Math.min(MAX, limit) / MAX);     // the set limit, marked on the dial
        float lx = cx + (float) Math.cos(la) * r, ly = cy + (float) Math.sin(la) * r;
        dot.setColor(0xFFFFFFFF); c.drawCircle(lx, ly, stroke * 0.62f, dot);
        dot.setColor(0xFFE5483D); c.drawCircle(lx, ly, stroke * 0.42f, dot);

        big.setColor(over ? 0xFFFF5A4F : 0xFFF1F5F6);
        String number = intro < 1300 ? String.valueOf(Math.round(shown)) : target < 0 ? "--" : String.valueOf(Math.round(target));
        c.drawText(number, cx, cy + big.getTextSize() * 0.3f, big);
        c.drawText("กม./ชม.", cx, cy + big.getTextSize() * 0.3f + small.getTextSize() * 1.7f, small);
        if (!roadName.isEmpty()) {
            CharSequence fit = TextUtils.ellipsize(roadName, road, r * 1.25f, TextUtils.TruncateAt.END);
            c.drawText(fit, 0, fit.length(), cx, cy + r * 0.86f, road);
        }
        if (moving) postInvalidateDelayed(33);
    }
}
