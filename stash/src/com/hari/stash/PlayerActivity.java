package com.hari.stash;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Now-playing screen for the music service: cover, tags, seek bar and transport buttons. */
public class PlayerActivity extends Activity implements MusicService.Listener {
    private ImageView art;
    private Glyph artGlyph;
    private TextView title, sub, detail, pos, dur, status;
    private SeekBar seek;
    private Glyph play, shuffle, repeat;
    private boolean dragging;
    private String shownKey = "";
    private final Runnable tick = new Runnable() {
        public void run() {
            updateProgress();
            Bg.MAIN.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UI.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(UI.BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10), UI.dp(this, 6));
        TextView back = UI.text(this, "Back", 15, UI.TEXT, true);
        back.setPadding(UI.dp(this, 6), UI.dp(this, 6), UI.dp(this, 14), UI.dp(this, 6));
        back.setBackgroundDrawable(UI.focusFill());
        back.setFocusable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back);
        status = UI.text(this, "Now playing", 17, UI.TEXT, true);
        bar.addView(status);
        root.addView(bar, UI.wrap());

        FrameLayout artBox = new FrameLayout(this);
        artGlyph = new Glyph(this, Glyph.NOTE, UI.GREY);
        artBox.addView(artGlyph, new FrameLayout.LayoutParams(-1, -1));
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artBox.addView(art, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams alp = UI.fill();
        alp.topMargin = UI.dp(this, 8);
        root.addView(artBox, alp);

        title = UI.text(this, "", 18, UI.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setSelected(true);
        title.setPadding(UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 12), 0);
        root.addView(title, UI.wrap());
        sub = UI.text(this, "", 14, UI.GREY, false);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(sub, UI.wrap());
        detail = UI.text(this, "", 12, UI.ACCENT, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), 0);
        root.addView(detail, UI.wrap());

        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.setPadding(UI.dp(this, 10), UI.dp(this, 4), UI.dp(this, 10), 0);
        pos = UI.text(this, "0:00", 12, UI.GREY, false);
        dur = UI.text(this, "0:00", 12, UI.GREY, false);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) pos.setText(UI.clock((int) ((long) p * MusicService.duration() / 1000)));
            }

            public void onStartTrackingTouch(SeekBar s) { dragging = true; }

            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                MusicService.seekTo((int) ((long) s.getProgress() * MusicService.duration() / 1000));
            }
        });
        times.addView(pos);
        times.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(dur);
        root.addView(times, UI.wrap());

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, UI.dp(this, 4), 0, UI.dp(this, 10));
        shuffle = button(row, Glyph.SHUFFLE, 44, new View.OnClickListener() {
            public void onClick(View v) { MusicService.toggleShuffle(); }
        });
        button(row, Glyph.PREV, 52, new View.OnClickListener() {
            public void onClick(View v) { MusicService.prev(); }
        });
        play = button(row, Glyph.PLAY, 64, new View.OnClickListener() {
            public void onClick(View v) { MusicService.toggle(); }
        });
        button(row, Glyph.NEXT, 52, new View.OnClickListener() {
            public void onClick(View v) { MusicService.next(); }
        });
        repeat = button(row, Glyph.REPEAT, 44, new View.OnClickListener() {
            public void onClick(View v) { MusicService.cycleRepeat(); }
        });
        root.addView(row, UI.wrap());
        setContentView(root);
    }

    private Glyph button(LinearLayout row, int type, int sizeDp, View.OnClickListener l) {
        Glyph g = new Glyph(this, type, UI.TEXT);
        g.setFocusable(true);
        g.setBackgroundDrawable(UI.focusFill());
        g.setOnClickListener(l);
        int px = UI.dp(this, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
        lp.leftMargin = lp.rightMargin = UI.dp(this, 6);
        row.addView(g, lp);
        return g;
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicService.listen(this);
        changed();
        Bg.MAIN.post(tick);
    }

    @Override
    protected void onPause() {
        Bg.MAIN.removeCallbacks(tick);
        MusicService.unlisten(this);
        super.onPause();
    }

    public void changed() {
        final Item it = MusicService.current();
        if (it == null) {
            finish();
            return;
        }
        play.set(MusicService.playing() ? Glyph.PAUSE : Glyph.PLAY, true);
        shuffle.set(Glyph.SHUFFLE, MusicService.shuffle());
        repeat.one = MusicService.repeat() == MusicService.REPEAT_ONE;
        repeat.set(Glyph.REPEAT, MusicService.repeat() != MusicService.REPEAT_OFF);
        String err = MusicService.error();
        status.setText(err != null ? "Cannot play" : MusicService.loading() ? "Loading..."
                : "Track " + (MusicService.index() + 1) + " of " + MusicService.count());
        if (err != null) sub.setText(err);
        if (!it.key().equals(shownKey)) {
            shownKey = it.key();
            title.setText(it.title());
            if (err == null) sub.setText(it.cloud ? "From the drop-box" : "On this phone");
            detail.setText(Item.ext(it.name));
            art.setImageBitmap(null);
            final String key = shownKey;
            final int px = UI.dp(this, 260);
            Bg.go(new Bg.Job() {
                public Object run() { return Meta.read(it, px); }
            }, new Bg.Done() {
                public void done(Object r, Exception e) {
                    if (isFinishing() || r == null || !key.equals(shownKey)) return;
                    Meta m = (Meta) r;
                    if (m.title != null && m.title.trim().length() > 0) title.setText(m.title);
                    String who = (m.artist == null ? "" : m.artist) + (m.artist != null && m.album != null ? " - " : "")
                            + (m.album == null ? "" : m.album);
                    if (who.length() > 0 && MusicService.error() == null) sub.setText(who);
                    detail.setText(m.detail);
                    if (m.art != null) art.setImageBitmap(m.art);
                }
            });
        }
        updateProgress();
    }

    private void updateProgress() {
        int d = MusicService.duration();
        dur.setText(UI.clock(d));
        if (dragging) return;
        int p = MusicService.position();
        pos.setText(UI.clock(p));
        seek.setProgress(d > 0 ? (int) ((long) p * 1000 / d) : 0);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            MusicService.toggle();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
            MusicService.next();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            MusicService.prev();
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
