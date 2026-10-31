package com.hari.stash;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.util.HashMap;

/** Tags and embedded cover art for the track in the player (best effort: any failure just means fewer details). */
final class Meta {
    String title, artist, album;
    String detail = "";   // e.g. "FLAC, 912 kbps"
    Bitmap art;

    static Meta read(Item it, int artPx) {
        Meta m = new Meta();
        m.detail = Item.ext(it.name);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (it.cloud) r.setDataSource(it.src(), new HashMap<String, String>());
            else if (it.uri != null) r.setDataSource(Cfg.app, android.net.Uri.parse(it.uri));
            else r.setDataSource(it.path);
            m.title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            m.artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            m.album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (br != null) {
                try {
                    m.detail += ", " + Long.parseLong(br) / 1000 + " kbps";
                } catch (NumberFormatException e) {
                    // leave it out
                }
            }
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                o.inSampleSize = Pics.sample(o.outWidth, o.outHeight, artPx);
                o.inJustDecodeBounds = false;
                m.art = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            }
        } catch (Exception e) {
            // not readable: filename and format are shown instead
        } catch (OutOfMemoryError e) {
            // cover too big
        } finally {
            try {
                r.release();
            } catch (Exception e) {
                // ignore
            }
        }
        return m;
    }
}
