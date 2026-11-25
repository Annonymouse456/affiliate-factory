package th.car.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * Blue band over the driving lane, fading with distance. Its shape is set by hand (it does not
 * analyse the picture), so each value can be nudged to match where the camera is mounted.
 */
public class PathView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    float bottomCentre = 0.31f, bottomWidth = 0.28f, topCentre = 0.425f, topY = 0.71f;

    public PathView(Context c) {
        super(c);
        SharedPreferences p = App.prefs(c);
        bottomCentre = p.getFloat("pathBottomCentre", bottomCentre);
        bottomWidth = p.getFloat("pathBottomWidth", bottomWidth);
        topCentre = p.getFloat("pathTopCentre", topCentre);
        topY = p.getFloat("pathTopY", topY);
    }

    /** Change the shape by the given steps, keep it on screen, save it and redraw. */
    void nudge(float centre, float width, float tilt, float length) {
        bottomCentre = clamp(bottomCentre + centre, 0.05f, 0.95f);
        topCentre = clamp(topCentre + centre + tilt, 0.05f, 0.95f);
        bottomWidth = clamp(bottomWidth + width, 0.10f, 0.70f);
        topY = clamp(topY - length, 0.35f, 0.90f);
        App.prefs(getContext()).edit().putFloat("pathBottomCentre", bottomCentre).putFloat("pathBottomWidth", bottomWidth)
                .putFloat("pathTopCentre", topCentre).putFloat("pathTopY", topY).apply();
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private void rebuild(int w, int h) {
        if (w == 0 || h == 0) return;
        float topWidth = bottomWidth * 0.32f;
        shape.reset();
        shape.moveTo(w * (bottomCentre - bottomWidth / 2), h);
        shape.lineTo(w * (bottomCentre + bottomWidth / 2), h);
        shape.lineTo(w * (topCentre + topWidth / 2), h * topY);
        shape.lineTo(w * (topCentre - topWidth / 2), h * topY);
        shape.close();
        paint.setShader(new LinearGradient(0, h, 0, h * topY, new int[]{0xB83A93FF, 0x803A93FF, 0x003A93FF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { rebuild(w, h); }

    @Override protected void onDraw(Canvas canvas) { canvas.drawPath(shape, paint); }
}
