package com.hari.bbwa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Round profile pictures: memory cache, 3-day disk cache, and coloured-initial placeholders. */
final class Avatars {
    interface Ready {
        void ready(Bitmap b);
    }

    private static final long DISK_TTL_MS = 3L * 24 * 3600 * 1000;
    private static final int[] PALETTE = {
        0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFF8E24AA, 0xFF3949AB, 0xFF00897B, 0xFFD81B60, 0xFF6D4C41
    };

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(120);
    private static final LruCache<String, Bitmap> PLACEHOLDERS = new LruCache<String, Bitmap>(120);
    private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<String>());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private Avatars() {}

    static Bitmap cached(String id) { return MEM.get(id); }

    /** Fetch in the background; calls back on the UI thread only if a real picture was found. */
    static void load(final Context ctx, final String id, final int px, final Ready cb) {
        if (MISSING.contains(id) || !PENDING.add(id)) return;
        final Context app = ctx.getApplicationContext();
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap out = null;
                try {
                    out = fetch(app, id, px);
                } catch (Exception e) {
                    // network trouble: leave it unmarked so a later refresh retries
                }
                if (out != null) MEM.put(id, out);
                PENDING.remove(id);
                final Bitmap fb = out;
                if (fb != null) {
                    Bg.MAIN.post(new Runnable() {
                        public void run() { cb.ready(fb); }
                    });
                }
            }
        });
    }

    private static Bitmap fetch(Context c, String id, int px) throws IOException {
        File f = new File(c.getCacheDir(), "av_" + id.replaceAll("[^0-9A-Za-z]", "_") + ".jpg");
        byte[] data = null;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_TTL_MS) {
            data = readFile(f);
        } else {
            data = Api.bytes(c, "/api/avatar/" + Api.enc(id));
            if (data == null) {
                MISSING.add(id);
                return null;
            }
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.close();
        }
        Bitmap src = decode(data, px);
        return src == null ? null : circle(src, px);
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

    private static Bitmap decode(byte[] data, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int side = Math.min(o.outWidth, o.outHeight);
        int sample = 1;
        while (side / (sample * 2) >= px) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** Centre-crop to a square and clip to a circle. */
    private static Bitmap circle(Bitmap src, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = px / (float) Math.min(src.getWidth(), src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((px - src.getWidth() * scale) / 2f, (px - src.getHeight() * scale) / 2f);
        shader.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(shader);
        new Canvas(out).drawCircle(px / 2f, px / 2f, px / 2f, p);
        return out;
    }

    /** Coloured circle with the first letter, used until (or instead of) a real picture. */
    static Bitmap placeholder(String key, String label, int px) {
        Bitmap hit = PLACEHOLDERS.get(key);
        if (hit != null) return hit;
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(PALETTE[(key.hashCode() & 0x7fffffff) % PALETTE.length]);
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
        String letter = "#";
        for (int i = 0; label != null && i < label.length(); i++) {
            if (Character.isLetterOrDigit(label.charAt(i))) {
                letter = String.valueOf(label.charAt(i)).toUpperCase();
                break;
            }
        }
        p.setColor(Color.WHITE);
        p.setTextSize(px * 0.46f);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(letter, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p);
        PLACEHOLDERS.put(key, out);
        return out;
    }
}
