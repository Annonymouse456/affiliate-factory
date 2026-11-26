package th.car.display;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A button face drawn in code: one simple symbol on an optional rounded or round plate. */
public class IconView extends View {
    static final int PLAY = 0, PAUSE = 1, NEXT = 2, PREVIOUS = 3, SPARK = 4, MENU = 5, EXPAND = 6, PANEL = 7;

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF box = new RectF();
    private int kind;
    private boolean round;

    public IconView(Context c, int kind, int inkColor, int plateColor, boolean roundPlate) {
        super(c);
        this.kind = kind;
        this.round = roundPlate;
        ink.setColor(inkColor);
        plate.setColor(plateColor);
    }

    void setKind(int k) { if (k != kind) { kind = k; invalidate(); } }
    void setPlate(int color) { if (plate.getColor() != color) { plate.setColor(color); invalidate(); } }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
        if (round) c.drawCircle(cx, cy, Math.min(w, h) / 2f, plate);
        else { box.set(0, 0, w, h); c.drawRoundRect(box, h * 0.22f, h * 0.22f, plate); }
        float r = Math.min(w, h) * (round ? 0.24f : 0.26f);        // half-size of the symbol
        ink.setStyle(Paint.Style.FILL);
        shape.reset();
        switch (kind) {
            case PLAY:
                shape.moveTo(cx - r * 0.7f, cy - r); shape.lineTo(cx + r, cy); shape.lineTo(cx - r * 0.7f, cy + r); shape.close();
                c.drawPath(shape, ink);
                break;
            case PAUSE:
                c.drawRect(cx - r * 0.8f, cy - r, cx - r * 0.2f, cy + r, ink);
                c.drawRect(cx + r * 0.2f, cy - r, cx + r * 0.8f, cy + r, ink);
                break;
            case NEXT:
            case PREVIOUS: {
                float d = kind == NEXT ? 1 : -1;
                shape.moveTo(cx - d * r, cy - r); shape.lineTo(cx + d * r * 0.5f, cy); shape.lineTo(cx - d * r, cy + r); shape.close();
                c.drawPath(shape, ink);
                c.drawRect(Math.min(cx + d * r * 0.62f, cx + d * r), cy - r, Math.max(cx + d * r * 0.62f, cx + d * r), cy + r, ink);
                break;
            }
            case SPARK:                                           // a four-point star with a small companion: the assistant
                star(cx - r * 0.15f, cy + r * 0.1f, r * 1.15f);
                star(cx + r * 0.95f, cy - r * 0.95f, r * 0.42f);
                c.drawPath(shape, ink);
                break;
            case MENU:
                for (int i = -1; i <= 1; i++) { box.set(cx - r, cy + i * r * 0.72f - r * 0.13f, cx + r, cy + i * r * 0.72f + r * 0.13f); c.drawRoundRect(box, r * 0.13f, r * 0.13f, ink); }
                break;
            case EXPAND: {                                        // four corners pointing outward
                ink.setStyle(Paint.Style.STROKE);
                ink.setStrokeWidth(r * 0.26f);
                ink.setStrokeCap(Paint.Cap.ROUND);
                float a = r * 0.55f;
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
                    c.drawLine(cx + sx * r, cy + sy * r, cx + sx * (r - a), cy + sy * r, ink);
                    c.drawLine(cx + sx * r, cy + sy * r, cx + sx * r, cy + sy * (r - a), ink);
                }
                break;
            }
            case PANEL: {                                         // a framed screen with a side panel: back to the dashboard
                ink.setStyle(Paint.Style.STROKE);
                ink.setStrokeWidth(r * 0.22f);
                box.set(cx - r, cy - r * 0.78f, cx + r, cy + r * 0.78f);
                c.drawRoundRect(box, r * 0.25f, r * 0.25f, ink);
                c.drawLine(cx - r * 0.2f, cy - r * 0.78f, cx - r * 0.2f, cy + r * 0.78f, ink);
                break;
            }
            default: break;
        }
    }

    private void star(float x, float y, float r) {
        float k = r * 0.24f;
        shape.moveTo(x, y - r); shape.lineTo(x + k, y - k); shape.lineTo(x + r, y); shape.lineTo(x + k, y + k);
        shape.lineTo(x, y + r); shape.lineTo(x - k, y + k); shape.lineTo(x - r, y); shape.lineTo(x - k, y - k); shape.close();
    }
}
