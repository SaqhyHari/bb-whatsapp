package com.hari.stash;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

/** Memory-friendly picture decoding. */
final class Pics {
    private Pics() {}

    static int sample(int w, int h, int px) {
        int s = 1;
        int big = Math.max(w, h);
        while (big / (s * 2) >= px) s *= 2;
        return s;
    }

    /** Decodes a file to about px on its long side and turns it upright using the EXIF tag; null if it cannot. */
    static Bitmap decode(String path, int px, boolean argb) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            o.inSampleSize = sample(o.outWidth, o.outHeight, px);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = argb ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            return bm == null ? null : upright(path, bm);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Same as decode(), for a content:// URI; degrees is the rotation the media database reports. */
    static Bitmap decodeUri(android.content.Context c, String uri, int px, boolean argb, int degrees) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            java.io.InputStream in = c.getContentResolver().openInputStream(android.net.Uri.parse(uri));
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (o.outWidth <= 0) return null;
            o.inSampleSize = sample(o.outWidth, o.outHeight, px);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = argb ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            in = c.getContentResolver().openInputStream(android.net.Uri.parse(uri));
            Bitmap bm;
            try {
                bm = BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (bm == null || degrees == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(degrees);
            return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap upright(String path, Bitmap bm) {
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        } catch (Exception e) {
            return bm;
        } catch (OutOfMemoryError e) {
            return bm;
        }
    }
}
