package com.hari.bbwa;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Prepares a photo for sending: upright, no bigger than needed, JPEG-compressed. */
final class Photos {
    private Photos() {}

    static byte[] prepare(String path, int maxSide, int quality) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0) throw new IOException("not an image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;

        Bitmap bmp = null;
        for (int tries = 0; bmp == null && tries < 3; tries++) {
            try {
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                bmp = BitmapFactory.decodeFile(path, d);
            } catch (OutOfMemoryError e) {
                sample *= 2; // tight on memory: decode smaller
            }
        }
        if (bmp == null) throw new IOException("could not read the image");

        Matrix m = new Matrix();
        int turn = exifDegrees(path);
        if (turn != 0) m.postRotate(turn);
        float scale = maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight());
        if (scale < 1f) m.postScale(scale, scale);
        if (!m.isIdentity()) {
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (t != bmp) bmp.recycle();
            bmp = t;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        bmp.recycle();
        return out.toByteArray();
    }

    /** Small preview for the confirmation dialog. */
    static Bitmap thumb(String path, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(path, d);
        if (b == null) return null;
        int turn = exifDegrees(path);
        if (turn == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(turn);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    private static int exifDegrees(String path) {
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception e) {
            // no EXIF: assume upright
        }
        return 0;
    }
}
