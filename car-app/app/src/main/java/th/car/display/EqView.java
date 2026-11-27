package th.car.display;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Five bars that dance while music plays and rest low when it is paused. Decoration: they do not follow the actual sound. */
public class EqView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final int[] theme;
    private boolean playing;

    public EqView(Context c, int[] theme) { super(c); this.theme = theme; }

    void setPlaying(boolean on) { if (on != playing) { playing = on; invalidate(); } }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        paint.setShader(new LinearGradient(0, h, 0, 0, theme[1], theme[0], Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), slot = w / 5f, t = (SystemClock.uptimeMillis() % 100000) / 1000f;
        for (int i = 0; i < 5; i++) {
            float level = playing ? 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(t * (5.5 + i * 1.3) + i * 2.1)) : 0.16f;
            bar.set(i * slot + slot * 0.2f, h * (1 - level), i * slot + slot * 0.8f, h);
            c.drawRoundRect(bar, slot * 0.3f, slot * 0.3f, paint);
        }
        if (playing) postInvalidateDelayed(120);
    }
}
