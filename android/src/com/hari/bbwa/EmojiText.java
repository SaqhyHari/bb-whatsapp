package com.hari.bbwa;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws emoji as pictures (Twemoji, assets/emoji/*.png, CC-BY 4.0) because the Android 4.3 font has
 * none of the modern ones. Text stays plain Unicode everywhere; only the display is swapped.
 */
final class EmojiText {
    /** Marks spans made here, so they can be replaced when an EditText changes. */
    private static final class EmojiSpan extends ImageSpan {
        EmojiSpan(Drawable d) { super(d, ImageSpan.ALIGN_BOTTOM); }
    }

    private static Set<String> files;
    private static LruCache<String, Bitmap> cache;
    private static Context app;

    private EmojiText() {}

    private static synchronized void init(Context c) {
        if (files != null) return;
        app = c.getApplicationContext();
        Set<String> set = new HashSet<String>();
        // names.txt (written by build.sh) is far quicker to read than AssetManager.list() on 4000 files
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(app.getAssets().open("emoji/names.txt")));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.length() > 0) set.add(line.trim());
                }
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        cache = new LruCache<String, Bitmap>(400);
        files = set; // last, so other threads never see a half-filled index
    }

    /** Loads the picture index, the picker lists and the tab icons on a background thread. */
    static void warmUp(final Context c) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    categories(c);
                    for (String icon : CATEGORY_ICONS) picture(c, icon);
                    for (String f : first(c, 36)) picture(c, f);
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    /** The first `n` pictures of the Smileys list (what the picker shows first). */
    private static List<String> first(Context c, int n) {
        List<String> all = categories(c).get(0);
        return all.subList(0, Math.min(n, all.size()));
    }

    private static boolean isBase(int cp) {
        if (cp >= 0x1F000 && cp <= 0x1FAFF) return cp < 0x1F1E6 || cp > 0x1F1FF;
        if (cp >= 0x2600 && cp <= 0x27BF) return true;
        if (cp >= 0x2190 && cp <= 0x21FF) return cp >= 0x2194 && cp <= 0x2199 || cp == 0x21A9 || cp == 0x21AA;
        if (cp >= 0x2300 && cp <= 0x23FF) return cp == 0x231A || cp == 0x231B || cp == 0x2328 || cp == 0x23CF
                || cp >= 0x23E9 && cp <= 0x23F3 || cp >= 0x23F8 && cp <= 0x23FA;
        if (cp >= 0x2B00 && cp <= 0x2BFF) return cp >= 0x2B05 && cp <= 0x2B07 || cp == 0x2B1B || cp == 0x2B1C
                || cp == 0x2B50 || cp == 0x2B55;
        switch (cp) {
            case 0xA9: case 0xAE: case 0x203C: case 0x2049: case 0x2122: case 0x2139: case 0x24C2:
            case 0x25AA: case 0x25AB: case 0x25B6: case 0x25C0: case 0x2934: case 0x2935:
            case 0x3030: case 0x303D: case 0x3297: case 0x3299:
                return true;
            default:
                return cp >= 0x25FB && cp <= 0x25FE;
        }
    }

    private static boolean isRegional(int cp) { return cp >= 0x1F1E6 && cp <= 0x1F1FF; }

    private static boolean isModifier(int cp) { return cp >= 0x1F3FB && cp <= 0x1F3FF; }

    private static boolean isKeycapBase(int cp) { return cp == '#' || cp == '*' || cp >= '0' && cp <= '9'; }

    /** Cheap test so ordinary text is not parsed at all. */
    private static boolean mayHaveEmoji(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x2000 || ch == 0xA9 || ch == 0xAE) return true;
        }
        return false;
    }

    private static String hex(List<Integer> cps, int n, boolean keepFe0f) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int cp = cps.get(i);
            if (cp == 0xFE0F && !keepFe0f) continue;
            if (b.length() > 0) b.append('-');
            b.append(Integer.toHexString(cp));
        }
        return b.toString();
    }

    private static String findName(List<Integer> cps, int n) {
        String full = hex(cps, n, true);
        if (files.contains(full)) return full;
        String bare = hex(cps, n, false);
        return files.contains(bare) ? bare : null;
    }

    /** Applies picture spans for every emoji in `out` (sizes in pixels). */
    private static void spanAll(Context c, Spannable out, float px) {
        init(c);
        int size = Math.round(px * 1.2f);
        String s = out.toString();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int start = i;
            List<Integer> cps = new ArrayList<Integer>();
            List<Integer> ends = new ArrayList<Integer>();
            int j = i + Character.charCount(cp);
            if (isRegional(cp)) {
                cps.add(cp);
                ends.add(j);
                if (j < s.length() && isRegional(s.codePointAt(j))) {
                    int cp2 = s.codePointAt(j);
                    j += Character.charCount(cp2);
                    cps.add(cp2);
                    ends.add(j);
                }
            } else if (isKeycapBase(cp)) {
                int k = j;
                int fe = k < s.length() && s.charAt(k) == 0xFE0F ? 1 : 0;
                if (k + fe < s.length() && s.charAt(k + fe) == 0x20E3) {
                    cps.add(cp);
                    ends.add(j);
                    cps.add(0x20E3);
                    j = k + fe + 1;
                    ends.add(j);
                }
            } else if (isBase(cp)) {
                cps.add(cp);
                ends.add(j);
                while (j < s.length()) {
                    int n = s.codePointAt(j);
                    int len = Character.charCount(n);
                    if (n == 0xFE0F || isModifier(n) || n >= 0xE0020 && n <= 0xE007F) {
                        cps.add(n);
                        j += len;
                        ends.add(j);
                    } else if (n == 0x200D && j + len < s.length() && isBase(s.codePointAt(j + len))) {
                        int b = s.codePointAt(j + len);
                        cps.add(n);
                        cps.add(b);
                        j += len + Character.charCount(b);
                        ends.add(j - Character.charCount(b));
                        ends.add(j);
                    } else {
                        break;
                    }
                }
            }
            if (cps.isEmpty()) {
                i = j;
                continue;
            }
            // use the longest prefix that has a picture (newer sequences than the set falls back to its parts)
            int used = 0;
            String name = null;
            for (int n = cps.size(); n >= 1 && name == null; n--) {
                name = findName(cps, n);
                if (name != null) used = n;
            }
            if (name == null) {
                i = j;
                continue;
            }
            int end = ends.get(used - 1);
            Bitmap bmp = bitmap(name);
            if (bmp != null) {
                BitmapDrawable d = new BitmapDrawable(app.getResources(), bmp);
                d.setBounds(0, 0, size, size);
                out.setSpan(new EmojiSpan(d), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = Math.max(end, start + 1);
        }
    }

    private static Bitmap bitmap(String name) {
        Bitmap b = cache.get(name);
        if (b != null) return b;
        try {
            InputStream in = app.getAssets().open("emoji/" + name + ".png");
            try {
                b = BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
        if (b != null) cache.put(name, b);
        return b;
    }

    /** `s` with emoji drawn as pictures; `px` is the text size in pixels. */
    static CharSequence render(Context c, CharSequence s, float px) {
        if (s == null || !mayHaveEmoji(s)) return s;
        SpannableStringBuilder out = new SpannableStringBuilder(s);
        spanAll(c, out, px);
        return out;
    }

    /** Same as {@link #render} for a text size given in sp. */
    static CharSequence renderSp(Context c, CharSequence s, float sp) {
        return render(c, s, sp * c.getResources().getDisplayMetrics().scaledDensity);
    }

    /** For an EditText: call from afterTextChanged. */
    static void refresh(Context c, Editable e, float px) {
        EmojiSpan[] old = e.getSpans(0, e.length(), EmojiSpan.class);
        for (EmojiSpan sp : old) e.removeSpan(sp);
        if (mayHaveEmoji(e)) spanAll(c, e, px);
    }

    static Bitmap picture(Context c, String name) {
        init(c);
        return bitmap(name);
    }

    // ---- picker data -------------------------------------------------------------------------

    static final String[] CATEGORY_TITLES = {
        "Smileys", "People", "Animals and nature", "Food and drink", "Activities and travel",
        "Objects", "Symbols", "Flags",
    };
    /** A picture that stands for each category in the tab row. */
    static final String[] CATEGORY_ICONS = {
        "1f600", "1f44d", "1f436", "1f354", "26bd", "1f4a1", "2764", "1f3c1",
    };

    private static final int[][][] RANGES = {
        // smileys
        { {0x1F600, 0x1F637}, {0x1F641, 0x1F644}, {0x1F910, 0x1F915}, {0x1F917, 0x1F917}, {0x1F920, 0x1F925},
          {0x1F927, 0x1F92F}, {0x1F970, 0x1F976}, {0x1F978, 0x1F97A}, {0x1F9D0, 0x1F9D0}, {0x1FAE0, 0x1FAE8},
          {0x1F608, 0x1F609}, {0x1F47B, 0x1F47D}, {0x1F4A9, 0x1F4A9}, {0x1F916, 0x1F916}, {0x1F480, 0x1F480} },
        // people and hands
        { {0x1F446, 0x1F450}, {0x1F44A, 0x1F44A}, {0x1F4AA, 0x1F4AA}, {0x1F64B, 0x1F64F}, {0x1F90C, 0x1F90F},
          {0x1F91A, 0x1F91F}, {0x1F930, 0x1F939}, {0x270A, 0x270D}, {0x261D, 0x261D}, {0x1F440, 0x1F445},
          {0x1F466, 0x1F487}, {0x1F57A, 0x1F57A}, {0x1F9B0, 0x1F9B3}, {0x1F9D1, 0x1F9DF}, {0x1F9E0, 0x1F9E0},
          {0x1FAC0, 0x1FAC5}, {0x1FAF0, 0x1FAF8} },
        // animals and nature
        { {0x1F400, 0x1F43F}, {0x1F638, 0x1F640}, {0x1F648, 0x1F64A}, {0x1F980, 0x1F9AE}, {0x1F330, 0x1F344},
          {0x1F490, 0x1F490}, {0x1F4AE, 0x1F4AE}, {0x1F300, 0x1F321}, {0x2600, 0x2603}, {0x2614, 0x2614},
          {0x26C4, 0x26C5}, {0x1F525, 0x1F525}, {0x1F4A7, 0x1F4A7}, {0x1FAB0, 0x1FABF} },
        // food and drink
        { {0x1F345, 0x1F37F}, {0x1F32D, 0x1F32F}, {0x1F950, 0x1F96F}, {0x1F9C0, 0x1F9CB}, {0x2615, 0x2615},
          {0x1F961, 0x1F96B}, {0x1FAD0, 0x1FADF} },
        // activities and travel
        { {0x1F380, 0x1F3FA}, {0x1F680, 0x1F6FF}, {0x1F3A0, 0x1F3CA}, {0x26BD, 0x26BE}, {0x26F0, 0x26FA},
          {0x1F93A, 0x1F94F}, {0x1F9D8, 0x1F9DD}, {0x1F3E0, 0x1F3F0}, {0x2708, 0x2708}, {0x1F6A8, 0x1F6B5} },
        // objects
        { {0x231A, 0x231B}, {0x23F0, 0x23F3}, {0x260E, 0x260E}, {0x2709, 0x2709}, {0x270F, 0x270F},
          {0x2702, 0x2702}, {0x1F4A1, 0x1F4A1}, {0x1F4B0, 0x1F4FF}, {0x1F50B, 0x1F52F}, {0x1F550, 0x1F567},
          {0x1F56F, 0x1F5FF}, {0x1F9E2, 0x1F9FF}, {0x1FA70, 0x1FAAF}, {0x1F4A3, 0x1F4A3}, {0x1F6AA, 0x1F6D2} },
        // symbols
        { {0x2764, 0x2764}, {0x1F493, 0x1F49F}, {0x1F5A4, 0x1F5A4}, {0x1F90D, 0x1F90E}, {0x1F9E1, 0x1F9E1},
          {0x1F4A2, 0x1F4A6}, {0x1F4A4, 0x1F4A5}, {0x1F4AB, 0x1F4AF}, {0x2B50, 0x2B50}, {0x1F31F, 0x1F320},
          {0x2728, 0x2728}, {0x2705, 0x2705}, {0x274C, 0x274C}, {0x274E, 0x274E}, {0x2753, 0x2757},
          {0x203C, 0x203C}, {0x2049, 0x2049}, {0x2795, 0x2797}, {0x267B, 0x267B}, {0x26A0, 0x26A1},
          {0x1F500, 0x1F50A}, {0x1F51A, 0x1F53D}, {0x2B05, 0x2B07}, {0x2194, 0x21AA}, {0x1F534, 0x1F53D},
          {0x25AA, 0x25FE}, {0x2600, 0x26FF}, {0x2700, 0x27BF}, {0x1F6AB, 0x1F6AF}, {0x1F6B1, 0x1F6B3},
          {0x1F6B7, 0x1F6BC}, {0x1F6C2, 0x1F6C5} },
    };

    private static boolean inRanges(int cp, int[][] ranges) {
        for (int[] r : ranges) if (cp >= r[0] && cp <= r[1]) return true;
        return false;
    }

    /** Picture file names for each category (single emoji only, no skin tones or joined sequences). */
    private static List<List<String>> categoryCache;

    static synchronized List<List<String>> categories(Context c) {
        if (categoryCache != null) return categoryCache;
        init(c);
        String[] all = files.toArray(new String[0]);
        Arrays.sort(all);
        List<List<String>> out = new ArrayList<List<String>>();
        for (int i = 0; i < CATEGORY_TITLES.length; i++) out.add(new ArrayList<String>());
        for (String f : all) {
            int dash = f.indexOf('-');
            if (dash < 0) {
                int cp = Integer.parseInt(f, 16);
                for (int k = 0; k < RANGES.length; k++) {
                    if (inRanges(cp, RANGES[k])) {
                        out.get(k).add(f);
                        break;
                    }
                }
            } else if (f.length() == 11 && f.startsWith("1f1") && f.indexOf('-') == 5 && f.indexOf("1f1", 6) == 6) {
                out.get(7).add(f); // country flag: two regional indicators
            } else if (f.equals("1f3f3-fe0f-200d-1f308") || f.equals("1f3f4-200d-2620-fe0f") || f.equals("1f3f3-fe0f-200d-26a7-fe0f")) {
                out.get(7).add(f);
            }
        }
        for (String flag : new String[] { "1f3c1", "1f6a9", "1f3f3", "1f3f4" }) {
            if (files.contains(flag) && !out.get(7).contains(flag)) out.get(7).add(0, flag);
            for (List<String> l : out.subList(0, 7)) l.remove(flag);
        }
        categoryCache = out;
        return out;
    }

    /** The text to insert for a picture file name. */
    static String toText(String name) {
        StringBuilder b = new StringBuilder();
        String[] parts = name.split("-");
        for (String p : parts) b.appendCodePoint(Integer.parseInt(p, 16));
        if (parts.length == 1 && Integer.parseInt(parts[0], 16) < 0x1F000) b.append('️');
        return b.toString();
    }

    private static final int RECENT_MAX = 32;

    static List<String> recent(Context c) {
        List<String> out = new ArrayList<String>();
        init(c);
        SharedPreferences p = c.getSharedPreferences("emoji", Context.MODE_PRIVATE);
        for (String s : p.getString("recent", "").split(",")) {
            if (s.length() > 0 && files.contains(s)) out.add(s);
        }
        return out;
    }

    static void used(Context c, String name) {
        List<String> r = recent(c);
        r.remove(name);
        r.add(0, name);
        while (r.size() > RECENT_MAX) r.remove(r.size() - 1);
        StringBuilder b = new StringBuilder();
        for (String s : r) b.append(b.length() > 0 ? "," : "").append(s);
        c.getSharedPreferences("emoji", Context.MODE_PRIVATE).edit().putString("recent", b.toString()).commit();
    }
}
