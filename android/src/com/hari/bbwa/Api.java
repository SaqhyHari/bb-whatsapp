package com.hari.bbwa;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Blocking HTTP client for the bridge API. Call from a background thread only. */
final class Api {
    static final class Err extends IOException {
        final int code;

        Err(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private Api() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("bbwa", Context.MODE_PRIVATE);
    }

    static String base(Context c) { return sp(c).getString("base", ""); }

    static String token(Context c) { return sp(c).getString("token", ""); }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean paired(Context c) { return token(c).length() == 64 && base(c).length() > 0; }

    static String normalize(String s) {
        s = s.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    static String call(String base, String token, String method, String path, String body) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(40000);
            h.setRequestMethod(method);
            if (token != null && token.length() > 0) h.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] data = body.getBytes("UTF-8");
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(data.length);
                OutputStream out = h.getOutputStream();
                out.write(data);
                out.close();
            }
            int code = h.getResponseCode();
            InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) throw new Err(code, text);
            return text;
        } finally {
            h.disconnect();
        }
    }

    /** GET raw bytes (images). Returns null when the server says 404 (no such picture). */
    static byte[] bytes(Context c, String path) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(base(c) + path).openConnection();
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(40000);
            h.setRequestProperty("Authorization", "Bearer " + token(c));
            int code = h.getResponseCode();
            if (code == 404) return null;
            if (code >= 400) throw new Err(code, "http " + code);
            InputStream in = h.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            in.close();
            return buf.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        in.close();
        return buf.toString("UTF-8");
    }

    static String call(Context c, String method, String path, String body) throws IOException {
        return call(base(c), token(c), method, path, body);
    }

    static String pair(String base, String code, String name) throws Exception {
        JSONObject b = new JSONObject();
        b.put("code", code);
        b.put("name", name);
        return new JSONObject(call(base, null, "POST", "/api/pair", b.toString())).getString("token");
    }

    static JSONArray chats(Context c) throws Exception {
        String body = call(c, "GET", "/api/chats?limit=300", null);
        JSONArray a = new JSONArray(body);
        try { // kept so the chat list can appear at once next time, before the server answers
            java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(c.getCacheDir(), "chats.json"));
            try { out.write(body.getBytes("UTF-8")); } finally { out.close(); }
        } catch (Exception ignored) {
        }
        return a;
    }

    /** The chat list as last fetched, or null. */
    static JSONArray cachedChats(Context c) {
        try {
            java.io.File f = new java.io.File(c.getCacheDir(), "chats.json");
            if (!f.exists()) return null;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n < 0) break;
                    got += n;
                }
            } finally {
                in.close();
            }
            return new JSONArray(new String(buf, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static JSONArray messages(Context c, String chatId, int limit) throws Exception {
        return messages(c, chatId, limit, false);
    }

    /** markRead also clears the chat's unread badge (and sends read receipts, like opening it on any device). */
    static JSONArray messages(Context c, String chatId, int limit, boolean markRead) throws Exception {
        return new JSONArray(call(c, "GET", "/api/chats/" + enc(chatId) + "/messages?limit=" + limit
                + (markRead ? "&markRead=1" : ""), null));
    }

    /** Stream a (possibly large) response straight to a file: audio and video are played from disk. */
    static void download(Context c, String path, File dest) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(base(c) + path).openConnection();
        File tmp = new File(dest.getPath() + ".part");
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(60000);
            h.setRequestProperty("Authorization", "Bearer " + token(c));
            int code = h.getResponseCode();
            if (code >= 400) throw new Err(code, "http " + code);
            InputStream in = h.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
            out.close();
            in.close();
            if (!tmp.renameTo(dest)) throw new IOException("could not save download");
        } finally {
            h.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    static JSONObject sendPhoto(Context c, String chatId, byte[] jpeg, String caption) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(base(c) + "/api/chats/" + enc(chatId) + "/photo"
                + (caption.length() > 0 ? "?caption=" + enc(caption) : "")).openConnection();
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(90000);
            h.setRequestMethod("POST");
            h.setRequestProperty("Authorization", "Bearer " + token(c));
            h.setRequestProperty("Content-Type", "image/jpeg");
            h.setRequestProperty("Connection", "close"); // never reuse a stale pooled connection for a big POST
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(jpeg.length);
            OutputStream out = h.getOutputStream();
            out.write(jpeg);
            out.close();
            int code = h.getResponseCode();
            InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) throw new Err(code, text);
            return new JSONObject(text);
        } finally {
            h.disconnect();
        }
    }

    /** Uploads a recorded WAV; the bridge encodes it to Ogg Opus and sends it as a voice message. */
    static JSONObject sendVoice(Context c, String chatId, File wav) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(base(c) + "/api/chats/" + enc(chatId) + "/voice").openConnection();
        java.io.FileInputStream in = new java.io.FileInputStream(wav);
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(120000);
            h.setRequestMethod("POST");
            h.setRequestProperty("Authorization", "Bearer " + token(c));
            h.setRequestProperty("Content-Type", "audio/wav");
            h.setRequestProperty("Connection", "close");
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode((int) wav.length());
            OutputStream out = h.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            int code = h.getResponseCode();
            InputStream resp = code < 400 ? h.getInputStream() : h.getErrorStream();
            String text = resp == null ? "" : readAll(resp);
            if (code >= 400) throw new Err(code, text);
            return new JSONObject(text);
        } finally {
            in.close();
            h.disconnect();
        }
    }

    static JSONObject updates(Context c, long since) throws Exception {
        return new JSONObject(call(c, "GET", "/api/updates?since=" + since, null));
    }

    /** Edit one of your own text messages; the server answers with the updated message. */
    static JSONObject edit(Context c, String chatId, String msgId, String text) throws Exception {
        JSONObject b = new JSONObject();
        b.put("text", text);
        return new JSONObject(call(c, "POST", "/api/chats/" + enc(chatId) + "/messages/" + enc(msgId) + "/edit", b.toString()));
    }

    /** Reacts to a message with an emoji (empty removes your reaction); returns the message's new state. */
    static JSONObject react(Context c, String chatId, String msgId, String emoji) throws Exception {
        JSONObject b = new JSONObject();
        b.put("emoji", emoji);
        return new JSONObject(call(c, "POST", "/api/chats/" + enc(chatId) + "/messages/" + enc(msgId) + "/react", b.toString()));
    }

    /** Deletes a message for you, or for everyone (own messages, within WhatsApp's time limit). */
    static JSONObject delete(Context c, String chatId, String msgId, boolean everyone) throws Exception {
        JSONObject b = new JSONObject();
        b.put("everyone", everyone);
        return new JSONObject(call(c, "POST", "/api/chats/" + enc(chatId) + "/messages/" + enc(msgId) + "/delete", b.toString()));
    }

    /** Forwards a message to another chat. */
    static void forward(Context c, String chatId, String msgId, String toChatId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("to", toChatId);
        call(c, "POST", "/api/chats/" + enc(chatId) + "/messages/" + enc(msgId) + "/forward", b.toString());
    }

    static JSONObject send(Context c, String chatId, String text) throws Exception {
        return send(c, chatId, text, null);
    }

    /** Sends text; with quotedId it is a reply to that message. */
    static JSONObject send(Context c, String chatId, String text, String quotedId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("text", text);
        if (quotedId != null && quotedId.length() > 0) b.put("quotedId", quotedId);
        return new JSONObject(call(c, "POST", "/api/chats/" + enc(chatId) + "/messages", b.toString()));
    }

    /** Human-readable reason for a failed call. */
    static String why(Exception e) {
        if (e instanceof Err) {
            Err x = (Err) e;
            if (x.code == 401) return "Not authorised. Pair this device again.";
            if (x.code == 503) return "WhatsApp is not ready on the server yet.";
            if (x.code == 403) return "Refused by the server.";
            if (x.code == 400) { // the bridge explains what was wrong with the request
                try {
                    String why = new JSONObject(x.getMessage()).optString("error");
                    if (why.length() > 0) return why;
                } catch (Exception ignored) {
                    // not JSON: fall through to the generic text
                }
            }
            if (x.code == 409) { // the bridge says why WhatsApp refused (too old, not allowed, ...)
                try {
                    String why = new JSONObject(x.getMessage()).optString("error");
                    if (why.length() > 0) return why.substring(0, 1).toUpperCase() + why.substring(1);
                } catch (Exception ignored) {
                    // not JSON: fall through to the generic text
                }
                return "WhatsApp refused this (it may be too old)";
            }
            if (x.code == 404) return "Message not found on the server.";
            return "Server error " + x.code;
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException || e instanceof java.net.NoRouteToHostException) {
            return "Cannot reach the server";
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
