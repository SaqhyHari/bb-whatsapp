package com.hari.stash;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

/** Plain HttpURLConnection calls to the drop-box. All of these block: call them off the UI thread. */
final class Http {
    abstract static class Progress {
        volatile boolean cancel;

        abstract void bytes(long done, long total);
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private Http() {}

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        return c;
    }

    private static HttpURLConnection get(String url) throws IOException {
        HttpURLConnection c = open(url, "GET");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new HttpError(code);
        }
        return c;
    }

    static String text(String url) throws IOException {
        HttpURLConnection c = get(url);
        try {
            return new String(readAll(c.getInputStream(), 4 * 1024 * 1024), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** A decoded picture no bigger than about px on its long side (used for the drop-box's thumbnails). */
    static Bitmap bitmap(String url, int px) throws IOException {
        HttpURLConnection c = get(url);
        try {
            byte[] data = readAll(c.getInputStream(), 16 * 1024 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = Pics.sample(o.outWidth, o.outHeight, px);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } finally {
            c.disconnect();
        }
    }

    static void download(String url, File dest, Progress p) throws IOException {
        HttpURLConnection c = get(url);
        File part = new File(dest.getPath() + ".part");
        try {
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[32768];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (p != null && p.cancel) throw new IOException("cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.bytes(done, total);
                }
            } finally {
                out.close();
                in.close();
            }
            if (!part.renameTo(dest)) throw new IOException("could not save " + dest.getName());
        } finally {
            part.delete();
            c.disconnect();
        }
    }

    /** An empty POST that must answer 200 (the drop-box's delete). */
    static void post(String url) throws IOException {
        HttpURLConnection c = open(url, "POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(0);
        try {
            c.getOutputStream().close();
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
        } finally {
            c.disconnect();
        }
    }

    static void upload(InputStream in, long length, String url, Progress p) throws IOException {
        HttpURLConnection c = open(url, "POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode((int) Math.min(length, Integer.MAX_VALUE));
        c.setReadTimeout(60000);
        try {
            OutputStream out = c.getOutputStream();
            try {
                byte[] buf = new byte[32768];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (p != null && p.cancel) throw new IOException("cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.bytes(done, length);
                }
                out.flush();
            } finally {
                in.close();
                out.close();
            }
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("response too large");
        }
        return out.toByteArray();
    }

    /** Short, truthful text for an error. */
    static String why(Exception e) {
        if (e instanceof HttpError) {
            int c = ((HttpError) e).code;
            if (c == 403) return "The drop-box refused the PIN.";
            if (c == 404) return "The drop-box does not have that file.";
            if (c == 413) return "The drop-box refused the file as too large.";
            return "The drop-box answered with error " + c + ".";
        }
        if (e instanceof UnknownHostException || e instanceof ConnectException)
            return "Cannot reach the drop-box. Is it running, and is the phone on the same Wi-Fi?";
        if (e instanceof SocketTimeoutException) return "The drop-box did not answer in time.";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
