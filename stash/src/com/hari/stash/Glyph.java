package com.hari.stash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small icons drawn with a Canvas, because the BB10 Android font has no symbol glyphs. */
final class Glyph extends View {
    static final int PLAY = 0, PAUSE = 1, NEXT = 2, PREV = 3, SHUFFLE = 4, REPEAT = 5, NOTE = 6, PHOTO = 7, VIDEO = 8, FILE = 9;

    int type;
    int color;
    boolean on = true;   // dims the glyph when false (shuffle/repeat off)
    boolean one;         // REPEAT: repeat the single track

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Glyph(Context c, int type, int color) {
        super(c);
        this.type = type;
        this.color = color;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // the BB10 GPU path renderer garbles these shapes
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void set(int type, boolean on) {
        this.type = type;
        this.on = on;
        invalidate();
    }

    private float s; // icon size in pixels; everything is drawn in real pixels (a scaled canvas breaks filled paths on BB10)

    private void tri(float x1, float y1, float x2, float y2, float x3, float y3, Canvas c) {
        path.reset();
        path.moveTo(x1 * s, y1 * s);
        path.lineTo(x2 * s, y2 * s);
        path.lineTo(x3 * s, y3 * s);
        path.close();
        c.drawPath(path, fill);
    }

    private void rect(float l, float t, float r, float b, Canvas c) {
        c.drawRect(l * s, t * s, r * s, b * s, fill);
    }

    private void ln(float x1, float y1, float x2, float y2, Canvas c) {
        c.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, line);
    }

    private void box(float l, float t, float r, float b, float rad, Canvas c) {
        c.drawRoundRect(new RectF(l * s, t * s, r * s, b * s), rad * s, rad * s, line);
    }

    @Override
    protected void onDraw(Canvas c) {
        s = Math.min(getWidth(), getHeight()) * 0.8f;
        if (s <= 0) return;
        int col = on ? color : (color & 0x00FFFFFF) | 0x55000000;
        fill.setColor(col);
        fill.setStyle(Paint.Style.FILL);
        line.setColor(col);
        line.setStrokeWidth(Math.max(1.5f, 0.08f * s));
        c.save();
        c.translate(getWidth() / 2f, getHeight() / 2f); // then draw about the centre, units of s (about -0.5..0.5)
        switch (type) {
            case PLAY:
                tri(-0.28f, -0.4f, -0.28f, 0.4f, 0.42f, 0f, c);
                break;
            case PAUSE:
                rect(-0.3f, -0.38f, -0.06f, 0.38f, c);
                rect(0.06f, -0.38f, 0.3f, 0.38f, c);
                break;
            case NEXT:
                tri(-0.4f, -0.34f, -0.4f, 0.34f, 0.2f, 0f, c);
                rect(0.24f, -0.34f, 0.37f, 0.34f, c);
                break;
            case PREV:
                tri(0.4f, -0.34f, 0.4f, 0.34f, -0.2f, 0f, c);
                rect(-0.37f, -0.34f, -0.24f, 0.34f, c);
                break;
            case SHUFFLE:
                ln(-0.42f, -0.22f, 0.12f, 0.22f, c);
                ln(-0.42f, 0.22f, 0.12f, -0.22f, c);
                tri(0.18f, -0.34f, 0.18f, -0.1f, 0.42f, -0.22f, c);
                tri(0.18f, 0.1f, 0.18f, 0.34f, 0.42f, 0.22f, c);
                break;
            case REPEAT:
                box(-0.4f, -0.22f, 0.32f, 0.22f, 0.12f, c);
                tri(0.28f, -0.4f, 0.28f, -0.04f, 0.46f, -0.22f, c);
                if (one) {
                    line.setStyle(Paint.Style.FILL);
                    line.setTextSize(0.34f * s);
                    line.setTextAlign(Paint.Align.CENTER);
                    c.drawText("1", -0.04f * s, 0.12f * s, line);
                    line.setStyle(Paint.Style.STROKE);
                }
                break;
            case NOTE:
                c.drawCircle(-0.16f * s, 0.26f * s, 0.17f * s, fill);
                rect(-0.02f, -0.38f, 0.06f, 0.26f, c);
                ln(0.02f, -0.36f, 0.3f, -0.12f, c);
                break;
            case PHOTO:
                box(-0.4f, -0.32f, 0.4f, 0.32f, 0.06f, c);
                c.drawCircle(-0.15f * s, -0.1f * s, 0.07f * s, fill);
                tri(-0.4f, 0.32f, -0.05f, 0.0f, 0.12f, 0.32f, c);
                tri(0.0f, 0.32f, 0.2f, 0.08f, 0.4f, 0.32f, c);
                break;
            case VIDEO:
                box(-0.4f, -0.3f, 0.4f, 0.3f, 0.06f, c);
                tri(-0.1f, -0.15f, -0.1f, 0.15f, 0.16f, 0f, c);
                break;
            default:
                box(-0.28f, -0.4f, 0.28f, 0.4f, 0.05f, c);
                ln(-0.14f, -0.1f, 0.14f, -0.1f, c);
                ln(-0.14f, 0.08f, 0.14f, 0.08f, c);
                break;
        }
        c.restore();
    }

    static int forKind(int kind) {
        return kind == Item.PHOTO ? PHOTO : kind == Item.VIDEO ? VIDEO : kind == Item.AUDIO ? NOTE : FILE;
    }
}
