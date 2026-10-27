package com.hari.bbwa;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Records the microphone as 16 kHz mono 16-bit PCM into a WAV file. The bridge turns it into the Ogg Opus
 * that WhatsApp wants (this phone's Android cannot encode Opus itself).
 */
final class Voice {
    static final int RATE = 16000;
    static final int MAX_SECONDS = 300;
    private static final int BYTES_PER_SECOND = RATE * 2;

    private final File file;
    private AudioRecord rec;
    private RandomAccessFile out;
    private Thread thread;
    private volatile boolean running;
    private volatile long bytes;

    Voice(File file) {
        this.file = file;
    }

    /** Opens the microphone and starts writing; throws with a readable reason if that is not possible. */
    void start() throws IOException {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("this phone cannot record at 16 kHz");
        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            rec = null;
            throw new IOException("could not open the microphone");
        }
        out = new RandomAccessFile(file, "rw");
        out.setLength(0);
        out.write(new byte[44]); // header, filled in when recording stops
        bytes = 0;
        running = true;
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            running = false;
            closeQuietly();
            throw new IOException("the microphone is busy");
        }
        thread = new Thread(new Runnable() {
            public void run() { pump(); }
        });
        thread.start();
    }

    private void pump() {
        byte[] buf = new byte[4096];
        try {
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) {
                    out.write(buf, 0, n);
                    bytes += n;
                    if (bytes >= (long) BYTES_PER_SECOND * MAX_SECONDS) running = false;
                } else if (n < 0) {
                    running = false;
                }
            }
        } catch (IOException e) {
            running = false;
        }
    }

    int seconds() {
        return (int) (bytes / BYTES_PER_SECOND);
    }

    /** True once the time limit was reached and recording stopped by itself. */
    boolean full() {
        return bytes >= (long) BYTES_PER_SECOND * MAX_SECONDS;
    }

    /** Stops recording and completes the WAV file. Returns the file, or null when nothing usable was recorded. */
    File stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (rec != null) rec.stop();
        } catch (IllegalStateException e) {
            // already stopped
        }
        long n = bytes;
        boolean ok = false;
        try {
            if (out != null && n > 0) {
                out.seek(0);
                out.write(header(n));
                ok = true;
            }
        } catch (IOException e) {
            ok = false;
        }
        closeQuietly();
        return ok ? file : null;
    }

    /** Stops and throws the recording away. */
    void cancel() {
        stop();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void closeQuietly() {
        try {
            if (rec != null) rec.release();
        } catch (RuntimeException e) {
            // ignore
        }
        rec = null;
        try {
            if (out != null) out.close();
        } catch (IOException e) {
            // ignore
        }
        out = null;
    }

    private static byte[] header(long dataBytes) {
        long total = dataBytes + 36;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, total);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le32(h, 16, 16);
        h[20] = 1; // PCM
        h[22] = 1; // mono
        le32(h, 24, RATE);
        le32(h, 28, BYTES_PER_SECOND);
        h[32] = 2; // block align
        h[34] = 16; // bits per sample
        put(h, 36, "data");
        le32(h, 40, dataBytes);
        return h;
    }

    private static void put(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void le32(byte[] b, int at, long v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }
}
