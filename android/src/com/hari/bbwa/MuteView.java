package com.hari.bbwa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A speaker with a slash through it, drawn by hand (no font symbols): shown next to muted chats. */
final class MuteView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path speaker = new Path();
    private final float dp;

    MuteView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setColor(UI.GREY);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(1.7f * dp);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(16 * dp), Math.round(16 * dp));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float u = dp;
        speaker.reset();
        speaker.moveTo(2 * u, 6 * u);
        speaker.lineTo(5.5f * u, 6 * u);
        speaker.lineTo(10 * u, 2.5f * u);
        speaker.lineTo(10 * u, 13.5f * u);
        speaker.lineTo(5.5f * u, 10 * u);
        speaker.lineTo(2 * u, 10 * u);
        speaker.close();
        paint.setStyle(Paint.Style.FILL);
        cv.drawPath(speaker, paint);
        paint.setStyle(Paint.Style.STROKE);
        cv.drawLine(1.5f * u, 1.5f * u, 14.5f * u, 14.5f * u, paint); // the slash
    }
}
