package com.hari.bbwa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The list of chats. With the extra "pick" it is a chooser instead (used to forward a message): tapping a
 * chat returns its "id" and "name" rather than opening it.
 */
public class ChatListActivity extends Activity {
    private static final int REFRESH_MS = 8000;

    private final List<JSONObject> items = new ArrayList<JSONObject>(); // everything from the server
    private final List<JSONObject> shown = new ArrayList<JSONObject>(); // the current tab
    private BaseAdapter adapter;
    private TextView status;
    private TextView tabChats;
    private TextView tabArchived;
    private View barChats;
    private View barArchived;
    private boolean showArchived;
    private boolean visible;
    private boolean loading;
    private boolean pick;
    private EditText search;
    private String query = "";

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!visible) return;
            refresh();
            Bg.MAIN.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Api.paired(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        pick = getIntent().getBooleanExtra("pick", false);
        showArchived = !pick && Api.sp(this).getBoolean("showArchived", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout head = UI.header(this, pick ? "Forward to..." : "BB Whatsapp");
        if (!pick) head.addView(new DotsView(this));
        root.addView(head, UI.wrap());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(UI.GREEN);
        View[] bar = new View[1];
        tabChats = tab(tabs, "Chats", false, bar);
        barChats = bar[0];
        tabArchived = tab(tabs, "Archived", true, bar);
        barArchived = bar[0];
        root.addView(tabs, UI.wrap());

        // typing here searches every chat (archived ones too) by name or last message
        search = new EditText(this);
        search.setHint("Search chats");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                applyTab();
            }
        });
        root.addView(search, UI.wrap());

        status = UI.text(this, "Loading...", 13, UI.GREY, false);
        status.setPadding(UI.dp(this, 10), UI.dp(this, 4), UI.dp(this, 10), UI.dp(this, 4));
        root.addView(status, UI.wrap());

        ListView list = new ListView(this);
        adapter = new ChatAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                JSONObject c = shown.get(pos);
                if (pick) { // chooser: hand the chat back instead of opening it
                    Intent r = new Intent();
                    r.putExtra("id", c.optString("id"));
                    r.putExtra("name", c.optString("name"));
                    setResult(RESULT_OK, r);
                    finish();
                    return;
                }
                Intent i = new Intent(ChatListActivity.this, ChatActivity.class);
                i.putExtra("id", c.optString("id"));
                i.putExtra("name", c.optString("name"));
                startActivity(i);
            }
        });
        root.addView(list, UI.fill());
        setContentView(root);
        list.requestFocus(); // the trackpad scrolls the chats; tap the search box to type
        applyTab();
    }

    /** One tab: a label with an underline bar that is white when selected. */
    private TextView tab(LinearLayout row, String label, final boolean archived, View[] barOut) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView t = UI.text(this, label, 15, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, UI.dp(this, 8), 0, UI.dp(this, 6));
        View bar = new View(this);
        cell.addView(t, UI.wrap());
        cell.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 3)));
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectTab(archived); }
        });
        // reachable with the trackpad: it lights up when the cursor is on it, and pressing the trackpad selects it
        cell.setFocusable(true);
        StateListDrawable focusLook = new StateListDrawable();
        focusLook.addState(new int[] { android.R.attr.state_focused }, new ColorDrawable(Color.parseColor("#55FFFFFF")));
        focusLook.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        cell.setBackgroundDrawable(focusLook);
        row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        barOut[0] = bar;
        return t;
    }

    private void selectTab(boolean archived) {
        showArchived = archived;
        if (!pick) Api.sp(this).edit().putBoolean("showArchived", archived).commit();
        applyTab();
    }

    /** Fill the visible list from the server data for the selected tab and update the tab labels. */
    private void applyTab() {
        shown.clear();
        int unreadChats = 0;
        int unreadArchived = 0;
        for (JSONObject o : items) {
            boolean archived = o.optBoolean("archived");
            if (o.optInt("unread") > 0) {
                if (archived) unreadArchived++;
                else unreadChats++;
            }
            boolean match = query.length() == 0
                    ? archived == showArchived
                    : o.optString("name").toLowerCase(Locale.getDefault()).contains(query)
                            || o.optString("last").toLowerCase(Locale.getDefault()).contains(query);
            if (match) shown.add(o);
        }
        tabChats.setText("Chats" + (unreadChats > 0 ? " (" + unreadChats + ")" : ""));
        tabArchived.setText("Archived" + (unreadArchived > 0 ? " (" + unreadArchived + ")" : ""));
        barChats.setBackgroundColor(showArchived ? Color.TRANSPARENT : Color.WHITE);
        barArchived.setBackgroundColor(showArchived ? Color.WHITE : Color.TRANSPARENT);
        adapter.notifyDataSetChanged();
        if (!items.isEmpty() && shown.isEmpty()) {
            status.setTextColor(UI.GREY);
            status.setText(query.length() > 0 ? "No chats match" : showArchived ? "No archived chats" : "No chats");
            status.setVisibility(View.VISIBLE);
        } else if (!items.isEmpty()) {
            status.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Api.paired(this)) return;
        visible = true;
        if (!pick) {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
            startService(new Intent(this, PollService.class));
        }
        Bg.MAIN.removeCallbacks(tick);
        Bg.MAIN.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        Bg.MAIN.removeCallbacks(tick);
    }

    /** The BlackBerry key opens the same menu as the three dots. */
    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent ev) {
        if (code == android.view.KeyEvent.KEYCODE_MENU && !pick) {
            if (ev.getRepeatCount() == 0) showMenu();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    public void onBackPressed() {
        if (query.length() > 0) search.setText(""); // Back clears a search first
        else if (showArchived) selectTab(false); // Back from Archived returns to Chats first
        else super.onBackPressed();
    }

    private void refresh() {
        if (loading) return;
        loading = true;
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.chats(ChatListActivity.this); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                loading = false;
                if (isFinishing()) return;
                if (e != null) {
                    status.setText(Api.why(e));
                    status.setTextColor(Color.parseColor("#B00020"));
                    status.setVisibility(View.VISIBLE);
                    return;
                }
                JSONArray a = (JSONArray) result;
                items.clear();
                for (int i = 0; i < a.length(); i++) items.add(a.optJSONObject(i));
                applyTab();
            }
        });
    }

    // ---------- the three-dots menu ----------

    private void showMenu() {
        new AlertDialog.Builder(this)
                .setItems(new String[] { "Refresh", "Status", "Unpair this phone" }, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) refresh();
                        else if (which == 1) showStatus();
                        else confirmUnpair();
                    }
                }).show();
    }

    private void showStatus() {
        int archived = 0;
        for (JSONObject o : items) if (o.optBoolean("archived")) archived++;
        new AlertDialog.Builder(this).setTitle("BB Whatsapp v" + Api.version(this))
                .setMessage("Server: " + Api.base(this) + "\n" + items.size() + " chats (" + archived + " archived)\n"
                        + PollService.status(this))
                .setPositiveButton("OK", null)
                .setNeutralButton("Test alert", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { PollService.testAlert(ChatListActivity.this); }
                }).show();
    }

    private void confirmUnpair() {
        new AlertDialog.Builder(this).setTitle("Unpair this phone?")
                .setMessage("You will need to scan a new pairing code to use BB Whatsapp again.")
                .setPositiveButton("Unpair", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { unpair(); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void unpair() {
        PollService.stop(this);
        Api.sp(this).edit().clear().commit();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** The vertical three dots; a view of its own so it does not depend on the phone's fonts. */
    private final class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsView(Context c) {
            super(c);
            paint.setColor(Color.WHITE);
            int box = UI.dp(c, 44);
            setLayoutParams(new LinearLayout.LayoutParams(box, box));
            setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showMenu(); }
            });
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f;
            float r = UI.dp(getContext(), 2.2f);
            float gap = UI.dp(getContext(), 7);
            float cy = getHeight() / 2f;
            cv.drawCircle(cx, cy - gap, r, paint);
            cv.drawCircle(cx, cy, r, paint);
            cv.drawCircle(cx, cy + gap, r, paint);
        }
    }

    private final class ChatAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }

        public Object getItem(int i) { return shown.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View reuse, ViewGroup parent) {
            Context c = ChatListActivity.this;
            JSONObject o = shown.get(i);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = UI.dp(c, 10);
            row.setPadding(p, UI.dp(c, 8), p, UI.dp(c, 8));

            row.addView(UI.avatar(c, o.optString("id"), o.optString("name"), 46, 10));

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = UI.text(c, o.optString("name"), 17, Color.BLACK, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView last = UI.text(c, o.optString("last"), 14, UI.GREY, false);
            last.setSingleLine(true);
            last.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(name);
            if (o.has("lastAck") && !o.isNull("lastAck")) { // your own message is last: show its ticks before the text
                LinearLayout lastRow = new LinearLayout(c);
                lastRow.setOrientation(LinearLayout.HORIZONTAL);
                lastRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.rightMargin = UI.dp(c, 4);
                lastRow.addView(new TickView(c, o.optInt("lastAck")), tp);
                lastRow.addView(last, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                col.addView(lastRow);
            } else {
                col.addView(last);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            int unread = o.optInt("unread");
            if (unread > 0) {
                TextView badge = UI.text(c, String.valueOf(unread), 13, Color.WHITE, true);
                badge.setGravity(Gravity.CENTER);
                badge.setBackgroundDrawable(UI.bubble(c, Color.parseColor("#25D366")));
                badge.setPadding(UI.dp(c, 7), UI.dp(c, 2), UI.dp(c, 7), UI.dp(c, 2));
                row.addView(badge);
            }
            return row;
        }
    }
}
