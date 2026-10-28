package com.hari.bbwa;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scans the pairing QR shown on the bridge's admin page: bbchat://pair?h=<host:port>&c=<code>.
 * Returns RESULT_OK with extras "host" and "code"; cancelling returns to manual entry.
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final int FRAME_GAP_MS = 400;
    private static final int FOCUS_GAP_MS = 2500;

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final MultiFormatReader reader = new MultiFormatReader();
    private Camera camera;
    private SurfaceView surface;
    private TextView hint;
    private boolean surfaceReady;
    private boolean decoding;
    private boolean finished;
    private boolean autoFocusLoop;
    private String cameraInfo = "camera: not started";
    private int frames;
    private String lastError = "";
    private int previewW;
    private int previewH;

    private final Runnable frameTick = new Runnable() {
        public void run() {
            if (camera == null || finished) return;
            if (!decoding) {
                try {
                    camera.setOneShotPreviewCallback(ScanActivity.this);
                } catch (RuntimeException e) {
                    // camera went away
                }
            }
            Bg.MAIN.postDelayed(this, FRAME_GAP_MS);
        }
    };

    private final Runnable focusTick = new Runnable() {
        public void run() {
            if (camera == null || finished || !autoFocusLoop) return;
            try {
                camera.autoFocus(null);
            } catch (RuntimeException e) {
                // ignore: focus is best effort
            }
            Bg.MAIN.postDelayed(this, FOCUS_GAP_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        UI.theme(this);
        super.onCreate(b);
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hint = UI.text(this, "Point the camera at the QR code on the bridge's admin page", 14, Color.WHITE, false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(Color.parseColor("#99000000"));
        int p = UI.dp(this, 8);
        hint.setPadding(p, p, p, p);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(hint, hl);

        Button cancel = UI.button(this, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = UI.dp(this, 10);
        root.addView(cancel, cl);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceReady) startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        startCamera();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {}

    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopCamera();
    }

    private void startCamera() {
        if (camera != null || finished) return;
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                // about 800 px wide: sharp enough to read a QR on a monitor, quick enough to decode
                if (best == null || Math.abs(s.width - 800) < Math.abs(best.width - 800)) best = s;
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            java.util.List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                autoFocusLoop = true;
            }
            camera.setParameters(p);
            Camera.Size ps = camera.getParameters().getPreviewSize();
            previewW = ps.width;
            previewH = ps.height;
            camera.setDisplayOrientation(displayOrientation());
            camera.setPreviewDisplay(surface.getHolder());
            camera.startPreview();
            cameraInfo = "camera OK " + previewW + "x" + previewH + " focus=" + p.getFocusMode();
            status("Point the camera at the QR on the admin page");
            Bg.MAIN.postDelayed(frameTick, FRAME_GAP_MS);
            if (autoFocusLoop) Bg.MAIN.post(focusTick);
        } catch (Exception e) {
            stopCamera();
            hint.setText("Cannot open the camera (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "). Press Cancel and type the details instead.");
        }
    }

    private void stopCamera() {
        Bg.MAIN.removeCallbacks(frameTick);
        Bg.MAIN.removeCallbacks(focusTick);
        if (camera != null) {
            try {
                camera.setOneShotPreviewCallback(null);
                camera.stopPreview();
            } catch (RuntimeException e) {
                // already stopped
            }
            camera.release();
            camera = null;
        }
    }

    private int displayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        int degrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                ? (360 - ((info.orientation + degrees) % 360)) % 360
                : (info.orientation - degrees + 360) % 360;
    }

    public void onPreviewFrame(final byte[] data, Camera c) {
        if (decoding || finished || data == null) return;
        decoding = true;
        final int w = previewW;
        final int h = previewH;
        decoder.execute(new Runnable() {
            public void run() {
                String text = null;
                try {
                    PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
                    text = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src))).getText();
                } catch (ReaderException e) {
                    // no code in this frame (NotFoundException and friends): try the next one
                } catch (Throwable t) {
                    // an unexpected library failure must not kill the app; report it on screen
                    lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage());
                } finally {
                    reader.reset();
                }
                final String found = text;
                Bg.MAIN.post(new Runnable() {
                    public void run() {
                        decoding = false;
                        frames++;
                        if (found != null) accept(found);
                        else status("Point the camera at the QR on the admin page");
                    }
                });
            }
        });
    }

    /** Plain hint normally; technical detail only appears when the camera or decoder misbehaves. */
    private void status(String extra) {
        if (lastError.length() > 0) {
            hint.setText(cameraInfo + " | frames " + frames + " | decoder error: " + lastError + "\n" + extra);
        } else {
            hint.setText(extra);
        }
    }

    private void accept(String text) {
        Uri u;
        try {
            u = Uri.parse(text);
        } catch (Exception e) {
            return;
        }
        String host = u.getQueryParameter("h");
        String code = u.getQueryParameter("c");
        boolean ok = "bbchat".equals(u.getScheme()) && "pair".equals(u.getHost())
                && host != null && host.matches("[0-9A-Za-z.\\-]+(:[0-9]{1,5})?")
                && code != null && code.matches("[0-9]{6}");
        if (!ok) {
            hint.setText("That is not a BB Whatsapp pairing code. Scan the QR on the bridge's admin page.");
            return;
        }
        finished = true;
        Intent r = new Intent();
        r.putExtra("host", host);
        r.putExtra("code", code);
        setResult(RESULT_OK, r);
        finish();
    }
}
