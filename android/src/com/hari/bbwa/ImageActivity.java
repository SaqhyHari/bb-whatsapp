package com.hari.bbwa;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Full-screen photo viewer. Tap or press back to close. */
public class ImageActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String id = getIntent().getStringExtra("id");
        if (id == null) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final TextView msg = UI.text(this, "Loading...", 16, Color.WHITE, false);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        setContentView(root);

        final int px = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        Bitmap have = Media.cached(id, px);
        if (have != null) {
            iv.setImageBitmap(have);
            msg.setVisibility(View.GONE);
            return;
        }
        Media.load(this, id, px, new Media.Ready() {
            public void ready(Bitmap bm) {
                if (isFinishing()) return;
                iv.setImageBitmap(bm);
                msg.setVisibility(View.GONE);
            }
        });
        // If nothing arrives (expired media, server down) say so instead of spinning forever.
        Bg.MAIN.postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing() && msg.getVisibility() == View.VISIBLE) msg.setText("Could not load this image");
            }
        }, 20000);
    }
}
