package com.hari.bbwa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Photos and stickers from the bridge: memory cache of decoded bitmaps plus a size-capped disk cache. */
final class Media {
    interface Ready {
        void ready(Bitmap b);
    }

    private static final long DISK_MAX = 120L * 1024 * 1024;
    private static final long DISK_TRIM_TO = 80L * 1024 * 1024;

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) { return b.getRowBytes() * b.getHeight(); }
    };
    private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<String>());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private Media() {}

    private static String key(String id, int maxPx) { return id + "@" + maxPx; }

    static Bitmap cached(String id, int maxPx) { return MEM.get(key(id, maxPx)); }

    /** Fetch and decode off the UI thread; calls back on it only when an image was produced. */
    static void load(Context ctx, String id, int maxPx, Ready cb) { load(ctx, id, maxPx, cb, null); }

    /** Tapped by the user: forget an earlier failure and try again; `failed` runs on the UI thread if it still fails. */
    static void retry(Context ctx, String id, int maxPx, Ready cb, Runnable failed) {
        MISSING.remove(id);
        PENDING.remove(key(id, maxPx));
        load(ctx, id, maxPx, cb, failed);
    }

    static void load(final Context ctx, final String id, final int maxPx, final Ready cb, final Runnable failed) {
        final String k = key(id, maxPx);
        if (MISSING.contains(id) || !PENDING.add(k)) return;
        final Context app = ctx.getApplicationContext();
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap out = null;
                try {
                    byte[] data = bytes(app, id);
                    if (data == null) MISSING.add(id);
                    else {
                        out = decode(data, maxPx);
                        if (out == null) { // e.g. an animated WebP sticker: Android 4.3 cannot read it, the bridge sends a still
                            byte[] still = stillBytes(app, id);
                            if (still != null) out = decode(still, maxPx);
                        }
                    }
                } catch (Exception e) {
                    // network problem: leave unmarked so a later view retries
                } catch (OutOfMemoryError e) {
                    MEM.evictAll();
                }
                if (out != null) MEM.put(k, out);
                PENDING.remove(k);
                final Bitmap fb = out;
                if (fb != null) {
                    Bg.MAIN.post(new Runnable() {
                        public void run() { cb.ready(fb); }
                    });
                } else if (failed != null) {
                    Bg.MAIN.post(failed);
                }
            }
        });
    }

    /** Download once into the cache and return the file; audio/video are played from disk. fmt may be null. */
    static File file(Context c, String id, String fmt) throws IOException {
        File dir = new File(c.getCacheDir(), "media");
        dir.mkdirs();
        File f = new File(dir, id.replaceAll("[^0-9A-Za-z]", "_") + (fmt == null ? ".file" : "." + fmt));
        if (f.exists() && f.length() > 0) return f;
        Api.download(c, "/api/media/" + Api.enc(id) + (fmt == null ? "" : "?fmt=" + fmt), f);
        trim(dir);
        return f;
    }

    private static byte[] bytes(Context c, String id) throws IOException {
        File dir = new File(c.getCacheDir(), "media");
        dir.mkdirs();
        File f = new File(dir, id.replaceAll("[^0-9A-Za-z]", "_"));
        if (f.exists()) return readFile(f);
        byte[] data = Api.bytes(c, "/api/media/" + Api.enc(id));
        if (data == null) return null;
        File tmp = new File(dir, f.getName() + ".part");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(data);
        out.close();
        tmp.renameTo(f);
        trim(dir);
        return data;
    }

    /** A PNG still of an image the phone cannot decode itself, kept next to the original. */
    private static byte[] stillBytes(Context c, String id) throws IOException {
        File dir = new File(c.getCacheDir(), "media");
        File f = new File(dir, id.replaceAll("[^0-9A-Za-z]", "_") + ".png");
        if (f.exists() && f.length() > 0) return readFile(f);
        byte[] data = Api.bytes(c, "/api/media/" + Api.enc(id) + "?fmt=png");
        if (data == null) return null;
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
        return data;
    }

    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File x : files) total += x.length();
        if (total <= DISK_MAX) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.lastModified() < b.lastModified() ? -1 : (a.lastModified() > b.lastModified() ? 1 : 0);
            }
        });
        for (File x : files) {
            if (total <= DISK_TRIM_TO) break;
            total -= x.length();
            x.delete();
        }
    }

    private static byte[] readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        in.close();
        return buf.toByteArray();
    }

    /** Decode no larger than needed: the biggest power-of-two shrink that keeps the long side >= maxPx. */
    private static Bitmap decode(byte[] data, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int longSide = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (longSide / (sample * 2) >= maxPx) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
