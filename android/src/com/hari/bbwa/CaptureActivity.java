package com.hari.bbwa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes a photo with the phone's camera and returns its file path as the "path" extra.
 * Focus: tap the picture to focus there, press the Focus button (or the trackpad) to focus on the
 * centre, and "Take photo" (or Enter / Space) focuses first and then shoots.
 */
public class CaptureActivity extends Activity implements SurfaceHolder.Callback {
    private static final int FOCUS_TIMEOUT_MS = 2500;
    private static final int RING_HOLD_MS = 1200;

    private Camera camera;
    private SurfaceView surface;
    private FocusRing ring;
    private TextView hint;
    private Button shoot;
    private boolean surfaceReady;
    private boolean busy;
    private boolean focusing;
    private int sensorOrientation;
    private boolean canAutoFocus;
    private boolean hasContinuous;
    private float focusX = -1;
    private float focusY = -1;

    private static final int TARGET_LUMA = 118;
    private static final int LUMA_TOLERANCE = 14;
    private static final int SETTLE_FRAMES = 6;
    private static final int MAX_TRIES = 10;
    private boolean metering;
    private int previewW;
    private int previewH;
    private int minComp;
    private int maxComp;
    private int curComp;
    private int settle;
    private int tries;
    private float spotX;
    private float spotY;

    private final Runnable hideRing = new Runnable() {
        public void run() { ring.hide(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) focusAt(e.getX(), e.getY(), null);
                return true;
            }
        });
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ring = new FocusRing(this);
        root.addView(ring, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hint = UI.text(this, "Tap the picture to focus and set exposure there. Trackpad press = focus, Enter = take photo.", 13, Color.WHITE, false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(Color.parseColor("#99000000"));
        int p = UI.dp(this, 6);
        hint.setPadding(p, p, p, p);
        root.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        Bg.MAIN.postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing() && !hint.getText().toString().startsWith("Cannot") && !hint.getText().toString().startsWith("Could")) {
                    hint.setVisibility(View.GONE);
                }
            }
        }, 6000);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        shoot = UI.button(this, "Take photo");
        shoot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { capture(); }
        });
        bar.addView(shoot);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.bottomMargin = UI.dp(this, 10);
        root.addView(bar, bl);

        root.addView(new DotsView(this), new FrameLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 48), Gravity.TOP | Gravity.RIGHT));
        setContentView(root);
    }

    private void showMenu() {
        final String[] items = {"Focus", "Camera info", "Cancel"};
        new android.app.AlertDialog.Builder(this).setItems(items, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int which) {
                if (which == 0) focusAt(-1, -1, null);
                else if (which == 1) showInfo();
                else finish();
            }
        }).show();
    }

    /** The three dots in the top corner that open the menu. */
    private final class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsView(Context c) {
            super(c);
            paint.setColor(Color.WHITE);
            setBackgroundColor(Color.parseColor("#55000000"));
            setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showMenu(); }
            });
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = UI.dp(getContext(), 2.2f);
            float gap = UI.dp(getContext(), 7);
            cv.drawCircle(cx, cy - gap, r, paint);
            cv.drawCircle(cx, cy, r, paint);
            cv.drawCircle(cx, cy + gap, r, paint);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceReady) start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_FOCUS || code == KeyEvent.KEYCODE_F) {
            if (ev.getRepeatCount() == 0) focusAt(-1, -1, null);
            return true;
        }
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_CAMERA) {
            if (ev.getRepeatCount() == 0) capture();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        start();
    }

    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}

    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stop();
    }

    private void start() {
        if (camera != null) return;
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            Camera.Size prev = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (prev == null || Math.abs(s.width - 800) < Math.abs(prev.width - 800)) prev = s;
            }
            if (prev != null) {
                p.setPreviewSize(prev.width, prev.height);
                previewW = prev.width;
                previewH = prev.height;
            }
            Camera.Size pic = null; // about 1600 px wide: plenty for chat, light on memory
            for (Camera.Size s : p.getSupportedPictureSizes()) {
                if (pic == null || Math.abs(s.width - 1600) < Math.abs(pic.width - 1600)) pic = s;
            }
            if (pic != null) p.setPictureSize(pic.width, pic.height);
            List<String> modes = p.getSupportedFocusModes();
            canAutoFocus = modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO);
            hasContinuous = modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            if (hasContinuous) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (canAutoFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            sensorOrientation = info.orientation;
            int degrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
            p.setRotation((info.orientation + degrees) % 360);
            camera.setParameters(p);
            camera.setDisplayOrientation((info.orientation - degrees + 360) % 360);
            camera.setPreviewDisplay(surface.getHolder());
            camera.startPreview();
        } catch (Exception e) {
            stop();
            showHint("Cannot open the camera (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void stop() {
        Bg.MAIN.removeCallbacks(hideRing);
        focusing = false;
        metering = false;
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.cancelAutoFocus();
                camera.stopPreview();
            } catch (RuntimeException e) {
                // already stopped
            }
            camera.release();
            camera = null;
        }
    }

    /** Lists every setting this camera reports, to find out which exposure controls it really has. */
    private void showInfo() {
        if (camera == null) return;
        String all;
        try {
            all = camera.getParameters().flatten().replace(";", "\n");
        } catch (RuntimeException e) {
            all = "Could not read the camera settings";
        }
        TextView t = UI.text(this, all, 12, Color.BLACK, false);
        int p = UI.dp(this, 10);
        t.setPadding(p, p, p, p);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new android.app.AlertDialog.Builder(this).setTitle("Camera settings").setView(sv)
                .setPositiveButton("Close", null).show();
    }

    private void showHint(String text) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
    }

    /**
     * Focus on a point in the view (x, y in pixels), or on the centre when x < 0. If afterwards is not
     * null it runs once focusing has finished (successfully or not), so the photo can follow.
     */
    private void focusAt(float x, float y, final Runnable afterwards) {
        if (camera == null || busy) return;
        if (focusing) { // already focusing: just chain the shot onto the running attempt
            if (afterwards != null) pendingShot = afterwards;
            return;
        }
        if (!canAutoFocus && !hasContinuous) { // fixed-focus camera: nothing to do
            if (afterwards != null) afterwards.run();
            return;
        }
        if (x >= 0) { // remember a tapped point so the shot focuses there too
            focusX = x;
            focusY = y;
        }
        focusing = true;
        pendingShot = afterwards;
        float vw = Math.max(1, surface.getWidth());
        float vh = Math.max(1, surface.getHeight());
        final float fx = x < 0 ? vw / 2f : x;
        final float fy = y < 0 ? vh / 2f : y;
        ring.show(fx, fy, FocusRing.WORKING);
        try {
            Camera.Parameters p = camera.getParameters();
            if (canAutoFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            if (p.getMaxNumFocusAreas() > 0) {
                List<Camera.Area> areas = new ArrayList<Camera.Area>();
                areas.add(new Camera.Area(sensorRect(fx / vw, fy / vh, 120), 1000));
                p.setFocusAreas(areas);
            }
            try {
                camera.setParameters(p);
            } catch (RuntimeException e) {
                // this camera dislikes focus areas: focus on the centre instead
            }
            meterAt(fx / vw, fy / vh);
            final Runnable timeout = new Runnable() {
                public void run() { finishFocus(false); }
            };
            Bg.MAIN.postDelayed(timeout, FOCUS_TIMEOUT_MS);
            camera.autoFocus(new Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean ok, Camera c) {
                    Bg.MAIN.removeCallbacks(timeout);
                    finishFocus(ok);
                }
            });
        } catch (RuntimeException e) {
            finishFocus(false);
        }
    }

    private Runnable pendingShot;

    private void finishFocus(boolean ok) {
        if (!focusing) return;
        focusing = false;
        ring.set(ok ? FocusRing.OK : FocusRing.FAIL);
        Bg.MAIN.removeCallbacks(hideRing);
        Bg.MAIN.postDelayed(hideRing, RING_HOLD_MS);
        Runnable shot = pendingShot;
        pendingShot = null;
        if (camera != null && shot == null && hasContinuous) {
            // hand focus back to the camera's continuous mode once the manual attempt is done
            try {
                Camera.Parameters p = camera.getParameters();
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                camera.setParameters(p);
                camera.cancelAutoFocus();
            } catch (RuntimeException e) {
                // keep whatever mode it is in
            }
        }
        if (shot != null) shot.run();
    }

    /**
     * Set exposure for the tapped spot. Cameras that support metering areas are asked to meter there;
     * this phone's camera apparently ignores them, so we also measure the brightness of the spot in the
     * live preview and nudge the exposure compensation until that spot is mid-grey.
     */
    private void meterAt(float nx, float ny) {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            if (p.getMaxNumMeteringAreas() > 0) {
                List<Camera.Area> areas = new ArrayList<Camera.Area>();
                areas.add(new Camera.Area(sensorRect(nx, ny, 220), 1000));
                p.setMeteringAreas(areas);
                camera.setParameters(p);
            }
        } catch (RuntimeException e) {
            // no spot metering on this camera: the compensation loop below still works
        }
        startExposureLoop(nx, ny);
    }

    private void startExposureLoop(float nx, float ny) {
        if (camera == null || previewW == 0) return;
        try {
            Camera.Parameters p = camera.getParameters();
            minComp = p.getMinExposureCompensation();
            maxComp = p.getMaxExposureCompensation();
            if (minComp == 0 && maxComp == 0) {
                flash("This camera cannot change exposure");
                return;
            }
            curComp = 0;
            p.setExposureCompensation(0);
            camera.setParameters(p);
        } catch (RuntimeException e) {
            flash("Exposure control failed (" + e.getClass().getSimpleName() + ")");
            return;
        }
        float[] s = sensorPoint(nx, ny);
        spotX = s[0];
        spotY = s[1];
        tries = 0;
        settle = SETTLE_FRAMES;
        metering = true;
        camera.setPreviewCallback(new Camera.PreviewCallback() {
            public void onPreviewFrame(byte[] data, Camera c) { adjustExposure(data, c); }
        });
    }

    private void adjustExposure(byte[] data, Camera c) {
        if (!metering) {
            c.setPreviewCallback(null);
            return;
        }
        if (settle > 0) {
            settle--;
            return;
        }
        int cx = Math.round(spotX * previewW);
        int cy = Math.round(spotY * previewH);
        int r = Math.max(8, previewW / 16);
        int x0 = Math.max(0, cx - r), x1 = Math.min(previewW - 1, cx + r);
        int y0 = Math.max(0, cy - r), y1 = Math.min(previewH - 1, cy + r);
        long sum = 0;
        int n = 0;
        for (int y = y0; y <= y1; y += 2) {
            for (int x = x0; x <= x1; x += 2) {
                int i = y * previewW + x;
                if (i < data.length) {
                    sum += data[i] & 0xff;
                    n++;
                }
            }
        }
        if (n == 0) return;
        int luma = (int) (sum / n);
        int err = TARGET_LUMA - luma;
        if (Math.abs(err) <= LUMA_TOLERANCE || tries >= MAX_TRIES) {
            finishExposure(c);
            return;
        }
        int step = Math.abs(err) > 60 ? 2 : 1;
        int next = Math.max(minComp, Math.min(maxComp, curComp + (err > 0 ? step : -step)));
        if (next == curComp) { // reached the limit of what this camera allows
            finishExposure(c);
            return;
        }
        try {
            Camera.Parameters p = c.getParameters();
            p.setExposureCompensation(next);
            c.setParameters(p);
            curComp = next;
        } catch (RuntimeException e) {
            finishExposure(c);
            return;
        }
        tries++;
        settle = SETTLE_FRAMES;
    }

    private void finishExposure(Camera c) {
        metering = false;
        c.setPreviewCallback(null);
        flash("Exposure " + (curComp > 0 ? "+" : "") + curComp);
    }

    private void flash(String text) {
        showHint(text);
        Bg.MAIN.removeCallbacks(hideHint);
        Bg.MAIN.postDelayed(hideHint, 2000);
    }

    private final Runnable hideHint = new Runnable() {
        public void run() {
            String t = hint.getText().toString();
            if (!t.startsWith("Cannot") && !t.startsWith("Could")) hint.setVisibility(View.GONE);
        }
    };

    /** Map a point on the screen (0..1 each way) to a point on the sensor image (0..1 each way). */
    private float[] sensorPoint(float nx, float ny) {
        switch (sensorOrientation) {
            case 90: return new float[] {ny, 1 - nx};
            case 180: return new float[] {1 - nx, 1 - ny};
            case 270: return new float[] {1 - ny, nx};
            default: return new float[] {nx, ny};
        }
    }

    /** Map a point on the screen (0..1 each way) to the camera sensor's -1000..1000 square, with a box of +/- half. */
    private Rect sensorRect(float nx, float ny, int half) {
        float sx, sy;
        switch (sensorOrientation) {
            case 90: sx = ny; sy = 1 - nx; break;
            case 180: sx = 1 - nx; sy = 1 - ny; break;
            case 270: sx = 1 - ny; sy = nx; break;
            default: sx = nx; sy = ny; break;
        }
        int cx = Math.round(sx * 2000 - 1000);
        int cy = Math.round(sy * 2000 - 1000);
        return new Rect(Math.max(-1000, cx - half), Math.max(-1000, cy - half),
                Math.min(1000, cx + half), Math.min(1000, cy + half));
    }

    /** Focus first, then take the picture, so the shot is sharp. */
    private void capture() {
        if (camera == null || busy) return;
        focusAt(focusX, focusY, new Runnable() {
            public void run() { takePicture(); }
        });
    }

    private void takePicture() {
        if (camera == null || busy) return;
        busy = true;
        metering = false;
        shoot.setEnabled(false);
        try {
            camera.setPreviewCallback(null);
            camera.takePicture(null, null, new Camera.PictureCallback() {
                public void onPictureTaken(byte[] data, Camera c) {
                    try {
                        File f = new File(getCacheDir(), "capture.jpg");
                        FileOutputStream out = new FileOutputStream(f);
                        out.write(data);
                        out.close();
                        Intent r = new Intent();
                        r.putExtra("path", f.getPath());
                        setResult(RESULT_OK, r);
                        finish();
                    } catch (Exception e) {
                        busy = false;
                        shoot.setEnabled(true);
                        showHint("Could not save the photo");
                    }
                }
            });
        } catch (RuntimeException e) {
            busy = false;
            shoot.setEnabled(true);
            showHint("Could not take the photo");
        }
    }

    /** The square that shows where the camera is focusing: white while working, green when sharp, red if not. */
    private static final class FocusRing extends View {
        static final int WORKING = 0;
        static final int OK = 1;
        static final int FAIL = 2;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x;
        private float y;
        private int state;
        private boolean visible;

        FocusRing(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(UI.dp(c, 3));
        }

        void show(float px, float py, int s) {
            x = px;
            y = py;
            state = s;
            visible = true;
            invalidate();
        }

        void set(int s) {
            state = s;
            invalidate();
        }

        void hide() {
            visible = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (!visible) return;
            paint.setColor(state == OK ? Color.parseColor("#25D366") : state == FAIL ? Color.parseColor("#FF5252") : Color.WHITE);
            float r = UI.dp(getContext(), 36);
            cv.drawRect(x - r, y - r, x + r, y + r, paint);
        }
    }
}
