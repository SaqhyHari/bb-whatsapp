package com.hari.bbwa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * WhatsApp's delivery marks, drawn by hand (no font symbols, so they look the same on every phone):
 * clock = sending, one grey tick = sent, two grey = delivered, two blue = read, red dot = failed.
 */
final class TickView extends View {
    private static final int GREY = Color.parseColor("#8696A0");
    private static final int BLUE = Color.parseColor("#34B7F1");
    private static final int RED = Color.parseColor("#E53935");

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;
    private int ack;

    TickView(Context c, int ack) {
        super(c);
        this.ack = ack;
        dp = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.6f * dp);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(17 * dp), Math.round(11 * dp));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float h = getHeight();
        if (ack < 0) { // failed
            paint.setColor(RED);
            paint.setStyle(Paint.Style.FILL);
            cv.drawCircle(h / 2f + 1.5f * dp, h / 2f, h * 0.38f, paint);
            paint.setStyle(Paint.Style.STROKE);
            return;
        }
        if (ack == 0) { // still sending: a small clock
            paint.setColor(GREY);
            float cx = h / 2f + 1.5f * dp;
            float cy = h / 2f;
            float r = h * 0.4f;
            cv.drawCircle(cx, cy, r, paint);
            cv.drawLine(cx, cy, cx, cy - r * 0.6f, paint);
            cv.drawLine(cx, cy, cx + r * 0.5f, cy, paint);
            return;
        }
        paint.setColor(ack >= 3 ? BLUE : GREY);
        tick(cv, 0f, h);
        if (ack >= 2) tick(cv, 5f * dp, h);
    }

    private void tick(Canvas cv, float dx, float h) {
        float s = 10f * dp;
        path.reset();
        path.moveTo(dx + 0.5f * dp, h * 0.55f);
        path.lineTo(dx + s * 0.32f, h * 0.88f);
        path.lineTo(dx + s * 1.05f, h * 0.12f);
        cv.drawPath(path, paint);
    }
}
