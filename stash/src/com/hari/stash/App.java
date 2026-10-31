package com.hari.stash;

import android.app.Application;

import java.io.File;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Cfg.app = this;
        // photos opened from the cloud are cached here; drop yesterday's
        File[] old = new File(getCacheDir(), "view").listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
            for (File f : old) if (f.lastModified() < cutoff) f.delete();
        }
    }
}
