package com.hari.stash;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look (one dark palette) and small view builders. The UI is built in code, no layout XML. */
final class UI {
    static final int BG = Color.parseColor("#101418");
    static final int SURFACE = Color.parseColor("#181E24");
    static final int BAR = Color.parseColor("#0D47A1");
    static final int ACCENT = Color.parseColor("#42A5F5");
    static final int TEXT = Color.parseColor("#ECEFF1");
    static final int GREY = Color.parseColor("#90A4AE");
    static final int TILE = Color.parseColor("#26323B");
    static final int FOCUS = Color.parseColor("#33FFFFFF");

    private UI() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Transparent, but filled while selected, focused or pressed (so the trackpad cursor is visible). */
    static StateListDrawable focusFill() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] { android.R.attr.state_selected }, new ColorDrawable(FOCUS));
        d.addState(new int[] { android.R.attr.state_focused }, new ColorDrawable(FOCUS));
        d.addState(new int[] { android.R.attr.state_pressed }, new ColorDrawable(FOCUS));
        d.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    /** Grid cursor: a bright outline with a light tint, drawn over the tile so photos do not hide it. */
    static StateListDrawable cursor(Context c) {
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(0x33FFFFFF);
        on.setStroke(dp(c, 3), Color.WHITE);
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] { android.R.attr.state_focused }, on);
        d.addState(new int[] { android.R.attr.state_pressed }, on);
        d.addState(new int[] { android.R.attr.state_selected }, on);
        d.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    static android.graphics.drawable.GradientDrawable bubble(Context c, int fill) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 6));
        return d;
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static String size(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format("%.1f GB", b / 1073741824.0);
        if (b >= 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return Math.max(1, b / 1024) + " KB";
    }

    static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
