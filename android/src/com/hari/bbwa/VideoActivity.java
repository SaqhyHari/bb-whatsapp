package com.hari.bbwa;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

/** Downloads a video from the bridge, then plays it. Back closes. */
public class VideoActivity extends Activity {
    private VideoView video;
    private TextView msg;

    @Override
    protected void onCreate(Bundle b) {
        UI.theme(this);
        super.onCreate(b);
        final String id = getIntent().getStringExtra("id");
        if (id == null) {
            finish();
            return;
        }
        long size = getIntent().getLongExtra("size", 0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        root.addView(video, vl);
        msg = UI.text(this, "Downloading video" + (size > 0 ? " (" + mb(size) + ")" : "") + "...", 16, Color.WHITE, false);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Media.file(VideoActivity.this, id, null); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                if (e != null) {
                    msg.setText("Could not download this video.\n" + Api.why(e));
                    return;
                }
                play((File) result);
            }
        });
    }

    private void play(File f) {
        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                msg.setText("This phone cannot play this video.");
                msg.setVisibility(View.VISIBLE);
                return true;
            }
        });
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) { msg.setVisibility(View.GONE); }
        });
        video.setVideoPath(f.getPath());
        video.start();
    }

    private static String mb(long bytes) {
        return bytes >= 1024 * 1024 ? String.format("%.1f MB", bytes / 1048576.0) : (bytes / 1024) + " KB";
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null) video.pause();
    }
}
