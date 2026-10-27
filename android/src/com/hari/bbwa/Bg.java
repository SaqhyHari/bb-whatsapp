package com.hari.bbwa;

import android.os.Handler;
import android.os.Looper;

/** Tiny background-job helper: run work off the UI thread, deliver the result on it. */
final class Bg {
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Job {
        Object run() throws Exception;
    }

    interface Done {
        void done(Object result, Exception error);
    }

    private Bg() {}

    static void go(final Job job, final Done done) {
        new Thread(new Runnable() {
            public void run() {
                Object r = null;
                Exception err = null;
                try {
                    r = job.run();
                } catch (Exception e) {
                    err = e;
                }
                final Object fr = r;
                final Exception fe = err;
                MAIN.post(new Runnable() {
                    public void run() {
                        done.done(fr, fe);
                    }
                });
            }
        }).start();
    }
}
