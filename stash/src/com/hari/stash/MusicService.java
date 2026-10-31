package com.hari.stash;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Music playback that keeps going with the screen off. State is static so activities can read it directly;
 * they are told about changes through the listener list.
 */
public class MusicService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    interface Listener {
        void changed();
    }

    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    private static final List<Item> queue = new ArrayList<Item>();
    private static final List<Listener> listeners = new ArrayList<Listener>();
    private static int index = -1;
    private static boolean shuffle;
    private static int repeat = REPEAT_OFF;
    private static String error;
    private static MusicService me;

    private MediaPlayer mp;
    private boolean prepared;
    private boolean wantPlay;      // user intent: play when ready
    private boolean pausedByFocus;
    private AudioManager audio;
    private final Random rnd = new Random();
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            pause(); // headphones pulled out
        }
    };

    // ---- control, called from activities ----

    static void play(Context c, List<Item> items, int start) {
        queue.clear();
        queue.addAll(items);
        index = start;
        error = null;
        c.startService(new Intent(c, MusicService.class));
        if (me != null) me.startTrack();
        notifyChanged();
    }

    static void toggle() {
        if (me == null) return;
        if (me.prepared && me.mp.isPlaying()) me.pause();
        else me.resume();
    }

    static void next() {
        if (me != null) me.step(1, true);
    }

    static void prev() {
        if (me == null) return;
        if (me.prepared && me.mp.getCurrentPosition() > 3000) me.mp.seekTo(0);
        else me.step(-1, true);
    }

    static void seekTo(int ms) {
        if (me != null && me.prepared) me.mp.seekTo(ms);
    }

    static void stop() {
        if (me != null) me.stopSelf();
    }

    static void toggleShuffle() {
        shuffle = !shuffle;
        notifyChanged();
    }

    static void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        notifyChanged();
    }

    // ---- state, read from activities ----

    static Item current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    static int index() { return index; }

    static int count() { return queue.size(); }

    static boolean shuffle() { return shuffle; }

    static int repeat() { return repeat; }

    static String error() { return error; }

    static boolean playing() { return me != null && me.prepared && me.mp.isPlaying(); }

    static boolean loading() { return me != null && current() != null && !me.prepared && error == null; }

    static int position() { return me != null && me.prepared ? me.mp.getCurrentPosition() : 0; }

    static int duration() { return me != null && me.prepared ? Math.max(0, me.mp.getDuration()) : 0; }

    static void listen(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    static void unlisten(Listener l) { listeners.remove(l); }

    private static void notifyChanged() {
        for (Listener l : new ArrayList<Listener>(listeners)) l.changed();
    }

    // ---- service ----

    @Override
    public void onCreate() {
        super.onCreate();
        me = this;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (current() != null && mp == null) startTrack();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        release();
        audio.abandonAudioFocus(this);
        try {
            unregisterReceiver(noisy);
        } catch (RuntimeException e) {
            // already gone
        }
        stopForeground(true);
        me = null;
        queue.clear();
        index = -1;
        notifyChanged();
        super.onDestroy();
    }

    private void release() {
        if (mp != null) {
            mp.release();
            mp = null;
        }
        prepared = false;
    }

    private void startTrack() {
        release();
        Item it = current();
        if (it == null) return;
        error = null;
        wantPlay = true;
        pausedByFocus = false;
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            String src = it.src();
            if (src.startsWith("/")) mp.setDataSource(src);
            else mp.setDataSource(this, android.net.Uri.parse(src));
            mp.prepareAsync();
        } catch (Exception e) {
            fail("Could not open " + it.name + ".");
        }
        foreground();
        notifyChanged();
    }

    public void onPrepared(MediaPlayer m) {
        prepared = true;
        if (wantPlay && audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            m.start();
        }
        foreground();
        notifyChanged();
    }

    public void onCompletion(MediaPlayer m) {
        if (repeat == REPEAT_ONE) {
            m.seekTo(0);
            m.start();
        } else {
            step(1, false);
        }
    }

    public boolean onError(MediaPlayer m, int what, int extra) {
        Item it = current();
        String fmt = it == null ? "" : Item.ext(it.name);
        fail("This phone could not play " + (it == null ? "this file" : it.name) + "."
                + (fmt.equals("FLAC") ? " (FLAC only plays here at normal quality; high-resolution 24-bit files may not.)" : ""));
        return true;
    }

    private void fail(String msg) {
        error = msg;
        release();
        wantPlay = false;
        stopForeground(true);
        notifyChanged();
    }

    /** Moves to the next/previous track. A manual press wraps around the list; a track ending stops at its last track unless repeat-all is on. */
    private void step(int dir, boolean manual) {
        if (queue.isEmpty()) return;
        int n = queue.size();
        int to;
        if (shuffle && n > 1) {
            do {
                to = rnd.nextInt(n);
            } while (to == index);
        } else {
            to = index + dir;
            if (to >= n || to < 0) {
                if (repeat == REPEAT_ALL || (manual && n > 1)) to = (to + n) % n;
                else {
                    // end of the list: stop on the last track, paused at the start
                    if (prepared) {
                        mp.seekTo(0);
                        mp.pause();
                    }
                    wantPlay = false;
                    stopForeground(false);
                    notifyChanged();
                    return;
                }
            }
        }
        index = to;
        startTrack();
    }

    private void pause() {
        wantPlay = false;
        if (prepared && mp.isPlaying()) mp.pause();
        stopForeground(false);
        notifyChanged();
    }

    private void resume() {
        wantPlay = true;
        if (prepared) {
            if (audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) mp.start();
            foreground();
        } else if (mp == null && current() != null) {
            startTrack();
        }
        notifyChanged();
    }

    public void onAudioFocusChange(int change) {
        if (mp == null || !prepared) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (mp.isPlaying()) {
                mp.pause();
                pausedByFocus = true;
                notifyChanged();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            mp.setVolume(0.25f, 0.25f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            mp.setVolume(1f, 1f);
            if (pausedByFocus) {
                pausedByFocus = false;
                mp.start();
                notifyChanged();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void foreground() {
        Item it = current();
        if (it == null) return;
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification n = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(it.title())
                .setContentText(Item.ext(it.name) + (it.cloud ? " from the drop-box" : " on this phone"))
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }
}
