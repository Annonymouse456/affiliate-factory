package th.car.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The track's cover as a record: round, with a spindle hole, turning while the music plays and
 * holding still when it is paused. Without a cover it is a disc in the theme colours with grooves.
 */
public class DiscView extends View {
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), plain = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG), hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] theme;
    private Bitmap cover;
    private boolean playing;
    private float angle;
    private long last;

    public DiscView(Context c, int[] theme) {
        super(c);
        this.theme = theme;
        line.setStyle(Paint.Style.STROKE);
    }

    void setCover(Bitmap b) {
        if (b == cover) return;
        cover = b;
        fit();
        invalidate();
    }

    void setPlaying(boolean on) { if (on != playing) { playing = on; last = 0; invalidate(); } }

    private void fit() {
        int w = getWidth(), h = getHeight();
        if (cover == null || w == 0) { face.setShader(null); return; }
        BitmapShader s = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(w / (float) cover.getWidth(), h / (float) cover.getHeight());     // cover the circle, centred
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - cover.getWidth() * scale) / 2f, (h - cover.getHeight() * scale) / 2f);
        s.setLocalMatrix(m);
        face.setShader(s);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        plain.setShader(new LinearGradient(0, 0, w, h, new int[]{theme[0], theme[1], theme[2]}, null, Shader.TileMode.CLAMP));
        fit();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2f - 2;
        long now = SystemClock.uptimeMillis();
        if (playing) { if (last != 0) angle = (angle + (now - last) * 0.045f) % 360; last = now; }     // one turn in eight seconds
        c.save();
        c.rotate(angle, cx, cy);
        c.drawCircle(cx, cy, r, cover != null ? face : plain);
        if (cover == null) {                                    // grooves, with one bright mark so the turning shows
            line.setStrokeWidth(Math.max(1.5f, r * 0.012f));
            line.setColor(0x33000000);
            for (float f = 0.42f; f < 0.95f; f += 0.13f) c.drawCircle(cx, cy, r * f, line);
            line.setColor(0x88FFFFFF);
            line.setStrokeWidth(r * 0.05f);
            c.drawLine(cx, cy - r * 0.45f, cx, cy - r * 0.88f, line);
        }
        c.restore();
        line.setColor(0x55FFFFFF);
        line.setStrokeWidth(Math.max(2f, r * 0.03f));
        c.drawCircle(cx, cy, r, line);
        hub.setColor(0xF00B1014);
        c.drawCircle(cx, cy, r * 0.17f, hub);
        hub.setColor(0x99FFFFFF);
        c.drawCircle(cx, cy, r * 0.045f, hub);
        if (playing) postInvalidateDelayed(70);
    }
}
