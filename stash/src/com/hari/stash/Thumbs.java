package com.hari.stash;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** List thumbnails: photos (local or from the drop-box) and local video frames, cached in memory. */
final class Thumbs {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 12 * 1024 * 1024)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Thumbs() {}

    /** Fills iv when the picture is ready; items without a picture leave it empty (the row's glyph shows). */
    static void into(final ImageView iv, final Item it, final int px) {
        final String key = it.key();
        iv.setTag(key);
        Bitmap have = CACHE.get(key);
        iv.setImageBitmap(have);
        if (have != null || (it.kind != Item.PHOTO && !(it.kind == Item.VIDEO && !it.cloud))) return;
        final android.content.Context ctx = Cfg.app;
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap bm = null;
                try {
                    if (it.kind == Item.PHOTO) {
                        bm = it.cloud ? Http.bitmap(Cfg.file("thumb", it.name), px)
                                : it.uri != null ? Pics.decodeUri(ctx, it.uri, px, false, it.orient) : Pics.decode(it.path, px, false);
                    } else if (it.uri != null) {
                        bm = MediaStore.Video.Thumbnails.getThumbnail(ctx.getContentResolver(), it.mid,
                                MediaStore.Video.Thumbnails.MINI_KIND, null);
                    } else {
                        bm = ThumbnailUtils.createVideoThumbnail(it.path, MediaStore.Video.Thumbnails.MINI_KIND);
                    }
                } catch (Exception e) {
                    // no thumbnail; the glyph stays
                } catch (OutOfMemoryError e) {
                    // same
                }
                if (bm == null) return;
                CACHE.put(key, bm);
                final Bitmap fb = bm;
                Bg.MAIN.post(new Runnable() {
                    public void run() {
                        if (key.equals(iv.getTag())) iv.setImageBitmap(fb);
                    }
                });
            }
        });
    }
}
