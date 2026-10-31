package com.hari.stash;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Drop-box address and PIN, plus the URLs the drop-box server answers. */
final class Cfg {
    static Context app;

    private Cfg() {}

    private static SharedPreferences sp() {
        return app.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static String base() {
        String s = sp().getString("base", "").trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.length() > 0 && s.indexOf("://") < 0) s = "http://" + s;
        // the drop-box defaults to port 8000; a bare address would otherwise hit port 80
        if (s.length() > 0 && s.indexOf(':', s.indexOf("://") + 3) < 0) s += ":8000";
        return s;
    }

    static String pin() {
        return sp().getString("pin", "");
    }

    static boolean ready() {
        return base().length() > 0;
    }

    static void save(String base, String pin) {
        sp().edit().putString("base", base.trim()).putString("pin", pin.trim()).commit();
    }

    static String getPref(String key, String def) {
        return sp().getString(key, def);
    }

    static void putPref(String key, String value) {
        sp().edit().putString(key, value).commit();
    }

    private static String q(String lead) {
        String p = pin();
        return p.length() == 0 ? "" : lead + "pin=" + Uri.encode(p);
    }

    static String list() {
        return base() + "/api/list" + q("?");
    }

    static String ping() {
        return base() + "/api/ping" + q("?");
    }

    /** kind is "view" (inline, supports Range), "thumb" or "files" (download). */
    static String file(String kind, String name) {
        return base() + "/" + kind + "/" + Uri.encode(name) + q("?");
    }

    static String delete(String name) {
        return base() + "/delete?name=" + Uri.encode(name) + q("&");
    }

    static String raw(String name) {
        return base() + "/raw?name=" + Uri.encode(name) + q("&");
    }
}
