package com.hari.stash;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/** Plays a video from the phone, or streams it from the drop-box. Back closes. */
public class VideoActivity extends Activity {
    private VideoView video;
    private TextView msg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String src = getIntent().getStringExtra("src");
        if (src == null) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        msg = UI.text(this, src.startsWith("http") ? "Connecting..." : "Opening...", 16, Color.WHITE, false);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                msg.setText("This phone cannot play this video.\n" + getIntent().getStringExtra("name"));
                msg.setVisibility(View.VISIBLE);
                return true;
            }
        });
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) { msg.setVisibility(View.GONE); }
        });
        if (src.startsWith("/")) video.setVideoPath(src);
        else video.setVideoURI(Uri.parse(src));
        video.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null) video.pause();
    }
}
