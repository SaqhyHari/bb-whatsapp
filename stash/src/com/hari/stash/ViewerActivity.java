package com.hari.stash;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Full-screen photo viewer. Swipe (or trackpad left/right) for the next photo; pinch or double-tap to zoom;
 * tap to show or hide the caption. Photos from the drop-box are downloaded to the cache first.
 */
public class ViewerActivity extends Activity {
    /** Handed over by MainActivity (an Intent cannot carry the list). */
    static List<Item> items;
    static int start;

    private ImageView iv;
    private TextView msg, caption;
    private int pos;
    private int loadId;
    private Bitmap bm;
    private final Matrix m = new Matrix();
    private float base = 1f, scale = 1f, tx, ty;
    private ScaleGestureDetector scaler;
    private GestureDetector gestures;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (items == null || items.isEmpty()) {
            finish();
            return;
        }
        pos = Math.max(0, Math.min(start, items.size() - 1));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        msg = UI.text(this, "", 16, Color.WHITE, false);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg, new FrameLayout.LayoutParams(-1, -1));
        caption = UI.text(this, "", 13, Color.WHITE, false);
        caption.setBackgroundColor(0xAA000000);
        caption.setPadding(UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10), UI.dp(this, 6));
        root.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomTo(scale * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent a, MotionEvent e, float dx, float dy) {
                if (zoomed()) {
                    tx -= dx;
                    ty -= dy;
                    apply();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (!zoomed() && Math.abs(vx) > 700 && Math.abs(vx) > Math.abs(vy)) {
                    go(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomTo(zoomed() ? base : base * 2.5f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                caption.setVisibility(caption.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return true;
            }
        });
        show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !zoomed()) {
            go(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && !zoomed()) {
            go(-1);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void go(int dir) {
        int to = pos + dir;
        if (to < 0 || to >= items.size()) return;
        pos = to;
        show();
    }

    private void show() {
        final Item it = items.get(pos);
        final int id = ++loadId;
        caption.setText((pos + 1) + " / " + items.size() + "   " + it.name);
        msg.setText(it.cloud ? "Downloading..." : "Loading...");
        msg.setVisibility(View.VISIBLE);
        iv.setImageBitmap(null);
        bm = null;
        final int px = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) * 3 / 2;
        Bg.go(new Bg.Job() {
            public Object run() throws Exception {
                String path = it.path;
                if (!it.cloud && it.uri != null) return Pics.decodeUri(ViewerActivity.this, it.uri, px, true, it.orient);
                if (it.cloud) {
                    File dir = new File(getCacheDir(), "view");
                    dir.mkdirs();
                    File f = new File(dir, Integer.toHexString(it.name.hashCode()) + "-" + it.size + "-" + it.name.replaceAll("[^A-Za-z0-9.]", "_"));
                    if (!f.isFile() || f.length() == 0) Http.download(Cfg.file("view", it.name), f, null);
                    path = f.getPath();
                }
                return Pics.decode(path, px, true);
            }
        }, new Bg.Done() {
            public void done(Object r, Exception e) {
                if (isFinishing() || id != loadId) return;
                if (e != null) {
                    msg.setText("Could not load this photo.\n" + Http.why(e));
                } else if (r == null) {
                    msg.setText("This phone cannot show this picture.");
                } else {
                    bm = (Bitmap) r;
                    msg.setVisibility(View.GONE);
                    iv.setImageBitmap(bm);
                    fit();
                }
            }
        });
    }

    private boolean zoomed() {
        return bm != null && scale > base * 1.05f;
    }

    private void fit() {
        int vw = iv.getWidth(), vh = iv.getHeight();
        if (bm == null) return;
        if (vw == 0 || vh == 0) { // layout not done yet
            iv.post(new Runnable() {
                public void run() { fit(); }
            });
            return;
        }
        base = Math.min((float) vw / bm.getWidth(), (float) vh / bm.getHeight());
        scale = base;
        tx = (vw - bm.getWidth() * scale) / 2f;
        ty = (vh - bm.getHeight() * scale) / 2f;
        apply();
    }

    private void zoomTo(float target, float fx, float fy) {
        if (bm == null) return;
        target = Math.max(base, Math.min(base * 6f, target));
        float k = target / scale;
        tx = fx - (fx - tx) * k;
        ty = fy - (fy - ty) * k;
        scale = target;
        apply();
    }

    private void apply() {
        if (bm == null) return;
        float w = bm.getWidth() * scale, h = bm.getHeight() * scale;
        int vw = iv.getWidth(), vh = iv.getHeight();
        tx = w <= vw ? (vw - w) / 2f : Math.max(vw - w, Math.min(0, tx));
        ty = h <= vh ? (vh - h) / 2f : Math.max(vh - h, Math.min(0, ty));
        m.reset();
        m.postScale(scale, scale);
        m.postTranslate(tx, ty);
        iv.setImageMatrix(m);
    }
}
