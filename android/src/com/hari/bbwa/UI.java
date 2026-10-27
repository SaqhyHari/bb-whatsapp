package com.hari.bbwa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look and small view builders (the UI is built in code, no layout XML). */
final class UI {
    static final int GREEN = Color.parseColor("#075E54");
    static final int BG = Color.parseColor("#ECE5DD");
    static final int MINE = Color.parseColor("#DCF8C6");
    static final int GREY = Color.parseColor("#667781");

    private UI() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        return b;
    }

    static GradientDrawable bubble(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 8));
        return d;
    }

    /** Green title bar with a title that takes the spare width; add buttons after it. */
    static LinearLayout header(Context c, String title) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(GREEN);
        h.setPadding(dp(c, 10), dp(c, 6), dp(c, 6), dp(c, 6));
        TextView t = text(c, title, 18, Color.WHITE, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return h;
    }

    /** Round profile picture: placeholder first, swapped for the real photo when it loads. */
    static android.widget.ImageView avatar(final Context c, final String id, String label, int sizeDp, int rightMarginDp) {
        final android.widget.ImageView v = new android.widget.ImageView(c);
        final int px = dp(c, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
        lp.rightMargin = dp(c, rightMarginDp);
        v.setLayoutParams(lp);
        Bitmap have = Avatars.cached(id);
        v.setImageBitmap(have != null ? have : Avatars.placeholder(id, label, px));
        if (have == null) {
            v.setTag(id);
            Avatars.load(c, id, px, new Avatars.Ready() {
                public void ready(Bitmap b) {
                    if (id.equals(v.getTag())) v.setImageBitmap(b);
                }
            });
        }
        return v;
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static void show(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }
}
