package com.hari.bbwa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatActivity extends Activity {
    private static final int POLL_MS = 4000;
    private static final int THUMB_PX = 360;
    private static final int REQ_CAPTURE = 11;
    private static final int REQ_PICK = 12;
    private static final int REQ_SYSTEM = 13;
    private static final int REQ_FORWARD = 14;

    /** Chat currently on screen, so the background service does not notify for it. */
    static volatile String openChat = "";

    private final List<JSONObject> msgs = new ArrayList<JSONObject>();
    private final Set<String> ids = new HashSet<String>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm");
    private final SimpleDateFormat dated = new SimpleDateFormat("d MMM HH:mm");
    private final SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd");
    private String chatId;
    private String title;
    private boolean group;
    private long seq = -1;
    private boolean visible;
    private boolean polling;
    private BaseAdapter adapter;
    private ListView list;
    private LinearLayout replyBar;
    private TextView replyText;
    private JSONObject replyTo; // the message the next text message answers
    private TextView olderBtn;
    private static final int OLDER_STEP = 40;
    private static final int OLDER_MAX = 300;
    private int shown = 40; // how many recent messages have been requested so far
    private boolean olderBusy;
    private boolean hasMore = true;
    private EditText input;
    private Button sendBtn;
    private TextView status;

    // voice-note playback: one clip at a time
    private MediaPlayer player;
    private String audioId = "";
    private int audioState; // 0 idle, 1 loading, 2 playing

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!visible) return;
            poll();
            Bg.MAIN.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        UI.theme(this);
        super.onCreate(b);
        chatId = getIntent().getStringExtra("id");
        String name = getIntent().getStringExtra("name");
        if (chatId == null) {
            finish();
            return;
        }
        group = chatId.endsWith("@g.us");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UI.BG);
        title = name == null || name.length() == 0 ? chatId : name;
        LinearLayout head = UI.header(this, title);
        head.addView(UI.avatar(this, chatId, title, 36, 10), 0);
        root.addView(head, UI.wrap());

        status = UI.text(this, "Loading...", 12, UI.GREY, false);
        status.setPadding(UI.dp(this, 10), UI.dp(this, 2), UI.dp(this, 10), UI.dp(this, 2));
        root.addView(status, UI.wrap());

        list = new ListView(this);
        list.setDivider(null);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        list.setCacheColorHint(Color.TRANSPARENT);
        // header row at the very top of the conversation: tap to fetch older messages
        LinearLayout olderWrap = new LinearLayout(this);
        olderBtn = UI.text(this, "Load older messages", 14, UI.ACCENT, true);
        olderBtn.setGravity(Gravity.CENTER);
        olderBtn.setVisibility(View.GONE); // shown once the first messages are in, so it is never the initial selection
        olderBtn.setPadding(0, UI.dp(this, 12), 0, UI.dp(this, 12));
        olderWrap.addView(olderBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // selectable, so the trackpad cursor can reach it as well as a finger; it is activated through the list's item click
        list.addHeaderView(olderWrap, null, true);
        adapter = new MsgAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 0) loadOlder();
            }
        });
        // press and hold (finger or trackpad) on one of your own text messages to edit it
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                int i = pos - list.getHeaderViewsCount();
                if (i < 0 || i >= msgs.size()) return false;
                messageMenu(msgs.get(i));
                return true;
            }
        });
        root.addView(list, UI.fill());

        // shown above the message box while you are replying to a message
        replyBar = new LinearLayout(this);
        replyBar.setOrientation(LinearLayout.HORIZONTAL);
        replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setBackgroundColor(UI.REPLY);
        replyBar.setPadding(UI.dp(this, 10), 0, 0, 0);
        replyText = UI.text(this, "", 13, UI.TEXT, false);
        replyText.setSingleLine(true);
        replyText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        replyBar.addView(replyText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button replyCancel = UI.button(this, "X");
        replyCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearReply(); }
        });
        replyBar.addView(replyCancel);
        replyBar.setVisibility(View.GONE);
        root.addView(replyBar, UI.wrap());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(UI.SURFACE);
        Button photoBtn = UI.button(this, "Photo");
        photoBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { photoMenu(); }
        });
        bar.addView(photoBtn);
        input = new EditText(this);
        input.setHint("Message");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (actionId == EditorInfo.IME_ACTION_SEND || (enter && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                    send();
                    return true;
                }
                return enter; // swallow the key-up too
            }
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button emojiBtn = UI.button(this, "Emoji");
        emojiBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { emojiPicker(); }
        });
        bar.addView(emojiBtn);
        sendBtn = UI.button(this, "Voice"); // becomes "Send" as soon as there is text, like the mic button in WhatsApp
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (input.getText().toString().trim().length() == 0) recordVoice();
                else send();
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            public void afterTextChanged(Editable s) {
                sendBtn.setText(s.toString().trim().length() == 0 ? "Voice" : "Send");
                EmojiText.refresh(ChatActivity.this, s, input.getTextSize());
            }
        });
        bar.addView(sendBtn);
        root.addView(bar, UI.wrap());
        setContentView(root);

        EmojiText.warmUp(this); // so the emoji picker opens at once
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        openChat = chatId == null ? "" : chatId;
        if (chatId != null) {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(chatId.hashCode());
        }
        Bg.MAIN.removeCallbacks(tick);
        Bg.MAIN.postDelayed(tick, POLL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        stopAudio();
        if (voice != null) { // leaving the screen abandons a recording in progress
            Voice v = voice;
            AlertDialog d = voiceDialog;
            voice = null;
            voiceDialog = null;
            v.cancel();
            if (d != null) d.dismiss();
        }
        if (chatId != null && chatId.equals(openChat)) openChat = "";
        Bg.MAIN.removeCallbacks(tick);
    }

    // ---------- loading and live updates ----------

    private void load() {
        Bg.go(new Bg.Job() {
            public Object run() throws Exception {
                // Opening a chat marks it read, like opening it in any WhatsApp client.
                JSONArray history = Api.messages(ChatActivity.this, chatId, 40, true);
                // Asking for "everything after the end" just returns the current sequence number.
                long end = Api.updates(ChatActivity.this, Long.MAX_VALUE / 2).optLong("seq", 0);
                return new Object[] { history, Long.valueOf(end) };
            }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                if (e != null) {
                    status.setText(Api.why(e));
                    status.setTextColor(Color.parseColor("#B00020"));
                    return;
                }
                Object[] r = (Object[]) result;
                JSONArray a = (JSONArray) r[0];
                for (int i = 0; i < a.length(); i++) add(a.optJSONObject(i));
                seq = ((Long) r[1]).longValue();
                olderBtn.setVisibility(View.VISIBLE);
                sortAndShow();
                list.setSelection(list.getCount() - 1); // newest message is in view, not the top button
                input.requestFocus(); // typing goes straight to the message box
                status.setText("");
                status.setVisibility(View.GONE);
            }
        });
    }

    /** Fetches another page of history and keeps the reader exactly where they were in the conversation. */
    private void loadOlder() {
        if (olderBusy || !hasMore) return;
        olderBusy = true;
        olderBtn.setText("Loading...");
        final int want = Math.min(shown + OLDER_STEP, OLDER_MAX);
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.messages(ChatActivity.this, chatId, want); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                olderBusy = false;
                if (isFinishing()) return;
                if (e != null) {
                    olderBtn.setText("Could not load: " + Api.why(e) + " (tap to retry)");
                    return;
                }
                JSONArray a = (JSONArray) result;
                int first = list.getFirstVisiblePosition();
                View top = list.getChildAt(0);
                int offset = top == null ? 0 : top.getTop();
                int before = msgs.size();
                for (int i = 0; i < a.length(); i++) add(a.optJSONObject(i));
                int added = msgs.size() - before;
                shown = want;
                if (added == 0 || want >= OLDER_MAX) {
                    hasMore = false;
                    olderBtn.setText(added == 0 ? "Start of the conversation" : "Older messages limit reached");
                    olderBtn.setClickable(false);
                } else {
                    olderBtn.setText("Load older messages");
                }
                list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED); // don't jump to the bottom
                sortAndShow();
                list.setSelectionFromTop(first + added, offset);
                Bg.MAIN.postDelayed(new Runnable() {
                    public void run() { list.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL); }
                }, 400);
            }
        });
    }

    private static final long EDIT_WINDOW_S = 15 * 60; // WhatsApp's own limit
    private static final long REVOKE_WINDOW_S = 2 * 86400L + 12 * 3600L; // "delete for everyone" window (2 days 12 hours)
    private JSONObject forwarding; // the message waiting for the chat the user picks

    /** Press and hold on a message: edit, forward and delete choices that apply to it. */
    private void messageMenu(final JSONObject m) {
        String type = m.optString("type");
        boolean mine = m.optBoolean("fromMe");
        boolean gone = type.equals("revoked");
        long age = System.currentTimeMillis() / 1000L - m.optLong("ts");
        final List<String> labels = new ArrayList<String>();
        if (!gone) labels.add("Reply");
        if (mine && type.equals("chat") && !m.optBoolean("hasMedia") && age <= EDIT_WINDOW_S + 60) labels.add("Edit");
        if (!gone) labels.add("Forward");
        labels.add("Delete for me");
        if (mine && !gone && age <= REVOKE_WINDOW_S) labels.add("Delete for everyone");
        new AlertDialog.Builder(this).setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                String pick = labels.get(which);
                if (pick.equals("Reply")) setReply(m);
                else if (pick.equals("Edit")) offerEdit(m);
                else if (pick.equals("Forward")) startForward(m);
                else confirmDelete(m, pick.equals("Delete for everyone"));
            }
        }).show();
    }

    /** Starts a reply: shows who and what is being answered above the message box. */
    private void setReply(JSONObject m) {
        replyTo = m;
        String who = m.optBoolean("fromMe") ? "yourself" : group ? number(m.optString("sender")) : title;
        String what = describe(m).replace('\n', ' ');
        if (what.length() > 60) what = what.substring(0, 60) + "...";
        replyText.setText(EmojiText.renderSp(this, "Replying to " + who + ": " + what, 13));
        replyBar.setVisibility(View.VISIBLE);
        input.requestFocus();
    }

    private void clearReply() {
        replyTo = null;
        replyBar.setVisibility(View.GONE);
    }

    /**
     * The BlackBerry key does what press-and-hold does for a finger: it opens the menu for the message the
     * trackpad cursor is on (the newest one until you move it).
     */
    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_MENU) {
            if (ev.getRepeatCount() == 0) menuForSelected();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    private void menuForSelected() {
        int i = list.getSelectedItemPosition() - list.getHeaderViewsCount();
        if (i < 0 || i >= msgs.size()) {
            Toast.makeText(this, "Move the cursor onto a message with the trackpad first", Toast.LENGTH_SHORT).show();
            return;
        }
        messageMenu(msgs.get(i));
    }

    private void startForward(JSONObject m) {
        forwarding = m;
        Intent i = new Intent(this, ChatListActivity.class);
        i.putExtra("pick", true);
        startActivityForResult(i, REQ_FORWARD);
    }

    private void confirmForward(final String toId, String toName) {
        final JSONObject m = forwarding;
        forwarding = null;
        if (m == null) return;
        new AlertDialog.Builder(this).setTitle("Forward message?")
                .setMessage("Send it to " + (toName.length() > 0 ? toName : toId) + "?")
                .setPositiveButton("Forward", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doForward(m, toId); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void doForward(final JSONObject m, final String toId) {
        Toast.makeText(this, "Forwarding...", Toast.LENGTH_SHORT).show();
        Bg.go(new Bg.Job() {
            public Object run() throws Exception {
                Api.forward(ChatActivity.this, chatId, m.optString("id"), toId);
                return null;
            }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                Toast.makeText(ChatActivity.this, e == null ? "Forwarded" : "Not forwarded: " + Api.why(e), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void confirmDelete(final JSONObject m, final boolean everyone) {
        new AlertDialog.Builder(this).setTitle(everyone ? "Delete for everyone?" : "Delete for you?")
                .setMessage(everyone ? "It will be removed for everyone in this chat."
                        : "It will be removed from your WhatsApp. Other people in the chat still see it.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doDelete(m, everyone); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void doDelete(final JSONObject m, final boolean everyone) {
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.delete(ChatActivity.this, chatId, m.optString("id"), everyone); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                if (e != null) {
                    Toast.makeText(ChatActivity.this, "Not deleted: " + Api.why(e), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONObject r = (JSONObject) result;
                if (r != null && add(r)) sortAndShow();
            }
        });
    }

    /** Offers editing for your own text messages. Returns true when the press was used. */
    private boolean offerEdit(final JSONObject m) {
        if (!m.optBoolean("fromMe") || !m.optString("type").equals("chat") || m.optBoolean("hasMedia")) return false;
        long age = System.currentTimeMillis() / 1000L - m.optLong("ts");
        if (age > EDIT_WINDOW_S + 60) { // a minute of slack for clock differences; the server has the final say
            Toast.makeText(this, "Messages can only be edited within 15 minutes", Toast.LENGTH_SHORT).show();
            return true;
        }
        final EditText box = new EditText(this);
        box.setText(m.optString("body"));
        box.setSelection(box.getText().length());
        new AlertDialog.Builder(this).setTitle("Edit message").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { saveEdit(m, box.getText().toString().trim()); }
                }).setNegativeButton("Cancel", null).show();
        return true;
    }

    private void saveEdit(final JSONObject m, final String text) {
        if (text.length() == 0 || text.equals(m.optString("body"))) return;
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show();
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.edit(ChatActivity.this, chatId, m.optString("id"), text); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                if (e != null) {
                    Toast.makeText(ChatActivity.this, "Not edited: " + Api.why(e), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONObject r = (JSONObject) result;
                if (r != null && r.has("body") && add(r)) sortAndShow();
            }
        });
    }

    private void poll() {
        if (polling || seq < 0) return;
        polling = true;
        final long since = seq;
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.updates(ChatActivity.this, since); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                polling = false;
                if (isFinishing() || e != null) return;
                JSONObject u = (JSONObject) result;
                JSONArray a = u.optJSONArray("messages");
                boolean changed = false;
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject m = a.optJSONObject(i);
                    if (m != null && chatId.equals(m.optString("chatId")) && add(m)) changed = true;
                }
                seq = Math.max(seq, u.optLong("seq", seq));
                if (changed) sortAndShow();
            }
        });
    }

    /** Adds a message, or replaces the stored copy when it was edited. True when the list changed. */
    private boolean add(JSONObject m) {
        if (m == null) return false;
        String id = m.optString("id");
        if (m.optString("type").equals("deleted")) { // deleted for you (here or on another device): drop it
            ids.remove(id);
            for (int i = 0; i < msgs.size(); i++) {
                if (msgs.get(i).optString("id").equals(id)) {
                    msgs.remove(i);
                    return true;
                }
            }
            return false;
        }
        if (ids.add(id)) {
            msgs.add(m);
            return true;
        }
        for (int i = 0; i < msgs.size(); i++) {
            JSONObject old = msgs.get(i);
            if (!old.optString("id").equals(id)) continue;
            if (old.optString("body").equals(m.optString("body")) && old.optString("type").equals(m.optString("type"))
                    && isEdited(old) == isEdited(m)
                    && old.optInt("ack", -9) == m.optInt("ack", -9)) return false;
            msgs.set(i, m);
            return true;
        }
        return false;
    }

    private static boolean isEdited(JSONObject m) {
        JSONObject meta = m.optJSONObject("meta");
        return meta != null && meta.optBoolean("edited");
    }

    private void sortAndShow() {
        Collections.sort(msgs, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return Long.valueOf(a.optLong("ts")).compareTo(Long.valueOf(b.optLong("ts")));
            }
        });
        adapter.notifyDataSetChanged();
    }

    // ---------- voice messages ----------

    private Voice voice;
    private AlertDialog voiceDialog;

    /** Records from the microphone in a small dialog; Send uploads it, Cancel throws it away. */
    private void recordVoice() {
        if (voice != null) return;
        final Voice v = new Voice(new File(getCacheDir(), "voice.wav"));
        try {
            v.start();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot record: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        voice = v;
        final TextView clockView = UI.text(this, "0:00", 30, UI.TEXT, true);
        clockView.setGravity(Gravity.CENTER);
        clockView.setPadding(0, UI.dp(this, 16), 0, UI.dp(this, 16));
        final Runnable tickClock = new Runnable() {
            public void run() {
                if (voice != v) return;
                int s = v.seconds();
                clockView.setText(s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60);
                if (v.full()) {
                    voiceDialog.setTitle("Time limit reached");
                    return;
                }
                Bg.MAIN.postDelayed(this, 250);
            }
        };
        voiceDialog = new AlertDialog.Builder(this).setTitle("Recording...").setView(clockView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finishVoice(v, true); }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finishVoice(v, false); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface d) { finishVoice(v, false); } // Back key
                })
                .create();
        voiceDialog.setCanceledOnTouchOutside(false);
        voiceDialog.show();
        Bg.MAIN.postDelayed(tickClock, 250);
    }

    private void finishVoice(Voice v, boolean send) {
        if (voice != v) return;
        voice = null;
        voiceDialog = null;
        if (!send) {
            v.cancel();
            return;
        }
        final int secs = v.seconds();
        final File wav = v.stop();
        if (wav == null || secs < 1) {
            Toast.makeText(this, "Recording too short", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Sending voice message...", Toast.LENGTH_SHORT).show();
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.sendVoice(ChatActivity.this, chatId, wav); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                if (e != null) {
                    Toast.makeText(ChatActivity.this, "Voice message not sent: " + Api.why(e), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONObject m = (JSONObject) result;
                if (m != null && m.has("type") && add(m)) sortAndShow();
            }
        });
    }

    // ---------- sending ----------

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        final JSONObject quoting = replyTo;
        final String quotedId = quoting == null ? null : quoting.optString("id");
        input.setText("");
        clearReply();
        sendBtn.setEnabled(false);
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.send(ChatActivity.this, chatId, text, quotedId); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                sendBtn.setEnabled(true);
                if (isFinishing()) return;
                if (e != null) {
                    input.setText(text);
                    input.setSelection(text.length());
                    if (quoting != null) setReply(quoting); // keep the reply so it can be sent again
                    Toast.makeText(ChatActivity.this, "Not sent: " + Api.why(e), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONObject m = (JSONObject) result;
                if (m != null && m.has("body") && add(m)) sortAndShow();
            }
        });
    }

    private void emojiPicker() {
        final List<List<String>> cats = EmojiText.categories(this);
        final List<String> recent = EmojiText.recent(this);
        final List<String> shown = new ArrayList<String>();
        final GridView grid = new GridView(this);
        grid.setNumColumns(6);
        final int cell = UI.dp(this, 46);
        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }

            public Object getItem(int i) { return shown.get(i); }

            public long getItemId(int i) { return i; }

            public View getView(int i, View reuse, ViewGroup parent) {
                ImageView v = reuse instanceof ImageView ? (ImageView) reuse : new ImageView(ChatActivity.this);
                v.setLayoutParams(new AbsListView.LayoutParams(cell, cell));
                v.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = UI.dp(ChatActivity.this, 5);
                v.setPadding(pad, pad, pad, pad);
                v.setImageBitmap(EmojiText.picture(ChatActivity.this, shown.get(i)));
                return v;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String name = shown.get(pos);
                String text = EmojiText.toText(name);
                int s = Math.max(0, input.getSelectionStart());
                int e = Math.max(0, input.getSelectionEnd());
                input.getText().replace(Math.min(s, e), Math.max(s, e), text, 0, text.length());
                EmojiText.used(ChatActivity.this, name);
            }
        });

        // one picture per category along the top; the cursor can reach them
        final List<List<String>> lists = new ArrayList<List<String>>();
        final List<String> icons = new ArrayList<String>();
        if (!recent.isEmpty()) {
            lists.add(recent);
            icons.add("1f553");
        }
        for (int i = 0; i < cats.size(); i++) {
            lists.add(cats.get(i));
            icons.add(EmojiText.CATEGORY_ICONS[i]);
        }
        final LinearLayout tabs = new LinearLayout(this);
        final ImageView[] tabViews = new ImageView[lists.size()];
        for (int i = 0; i < lists.size(); i++) {
            final int which = i;
            ImageView t = new ImageView(this);
            t.setImageBitmap(EmojiText.picture(this, icons.get(i)));
            t.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = UI.dp(this, 7);
            t.setPadding(pad, pad, pad, pad);
            t.setFocusable(true);
            t.setClickable(true);
            t.setBackgroundDrawable(UI.focusFill(UI.HILITE));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    shown.clear();
                    shown.addAll(lists.get(which));
                    adapter.notifyDataSetChanged();
                    grid.setSelection(0);
                    for (int k = 0; k < tabViews.length; k++) tabViews[k].setSelected(k == which);
                }
            });
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 44)));
        }
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.addView(tabs);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 46)));
        box.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 280)));
        tabViews[recent.size() >= 12 ? 0 : tabViews.length - cats.size()].performClick(); // Recent only opens first once it has something to show
        new AlertDialog.Builder(this).setTitle("Emoji").setView(box).setPositiveButton("Done", null).show();
    }

    private void photoMenu() {
        new AlertDialog.Builder(this).setTitle("Send a photo")
                .setItems(new String[] { "Take a photo", "Choose from gallery", "Browse files on this phone" },
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) startActivityForResult(new Intent(ChatActivity.this, CaptureActivity.class), REQ_CAPTURE);
                        else if (which == 1) pickFromGallery();
                        else startActivityForResult(new Intent(ChatActivity.this, PickImageActivity.class), REQ_PICK);
                    }
                }).show();
    }

    /** The phone's own photo chooser, if it has one; otherwise fall back to our file browser. */
    private void pickFromGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_SYSTEM);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No gallery found: opening the file browser", Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(this, PickImageActivity.class), REQ_PICK);
        } catch (SecurityException e) {
            Toast.makeText(this, "The gallery refused: try Browse files", Toast.LENGTH_LONG).show();
        }
    }

    /** Copy whatever the gallery handed back (a content:// or file:// address) into our cache. */
    private void importPicked(final Uri uri) {
        Bg.go(new Bg.Job() {
            public Object run() throws Exception {
                File out = new File(getCacheDir(), "picked.jpg");
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("cannot open the photo");
                FileOutputStream fo = new FileOutputStream(out);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close();
                in.close();
                return out.getPath();
            }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                if (e != null) {
                    Toast.makeText(ChatActivity.this, "Could not read that photo", Toast.LENGTH_LONG).show();
                    return;
                }
                confirmPhoto((String) result);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_FORWARD && result != RESULT_OK) forwarding = null;
        if (result != RESULT_OK || data == null) return;
        if (req == REQ_FORWARD) {
            confirmForward(data.getStringExtra("id") == null ? "" : data.getStringExtra("id"),
                    data.getStringExtra("name") == null ? "" : data.getStringExtra("name"));
            return;
        }
        if (req == REQ_SYSTEM) {
            if (data.getData() != null) importPicked(data.getData());
            return;
        }
        if (req != REQ_CAPTURE && req != REQ_PICK) return;
        String path = data.getStringExtra("path");
        if (path != null) confirmPhoto(path);
    }

    private void confirmPhoto(final String path) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UI.dp(this, 10);
        box.setPadding(p, p, p, p);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            Bitmap th = Photos.thumb(path, 400);
            if (th != null) preview.setImageBitmap(th);
        } catch (OutOfMemoryError e) {
            // no preview, still sendable
        }
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 200)));
        final EditText caption = new EditText(this);
        caption.setHint("Caption (optional)");
        caption.setSingleLine(true);
        box.addView(caption, UI.wrap());
        new AlertDialog.Builder(this).setTitle("Send this photo?").setView(box)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { sendPhoto(path, caption.getText().toString().trim()); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void sendPhoto(final String path, final String caption) {
        Toast.makeText(this, "Sending photo...", Toast.LENGTH_SHORT).show();
        Bg.go(new Bg.Job() {
            public Object run() throws Exception {
                byte[] jpeg = Photos.prepare(path, 1280, 82);
                return Api.sendPhoto(ChatActivity.this, chatId, jpeg, caption);
            }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                if (e != null) {
                    Toast.makeText(ChatActivity.this, "Photo not sent: " + Api.why(e), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONObject m = (JSONObject) result;
                if (m != null && m.has("type") && add(m)) sortAndShow();
            }
        });
    }

    // ---------- voice notes ----------

    private void toggleAudio(JSONObject m) {
        final String id = m.optString("id");
        if (id.equals(audioId) && audioState != 0) { // playing or loading: stop
            stopAudio();
            adapter.notifyDataSetChanged();
            return;
        }
        stopAudio();
        audioId = id;
        audioState = 1;
        adapter.notifyDataSetChanged();
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Media.file(ChatActivity.this, id, "wav"); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing() || !id.equals(audioId)) return; // stopped or switched meanwhile
                if (e != null) {
                    failAudio("Could not load this audio: " + Api.why(e));
                    return;
                }
                try {
                    player = new MediaPlayer();
                    player.setDataSource(((File) result).getPath());
                    player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        public void onCompletion(MediaPlayer mp) {
                            stopAudio();
                            adapter.notifyDataSetChanged();
                        }
                    });
                    player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        public boolean onError(MediaPlayer mp, int what, int extra) {
                            failAudio("This phone cannot play this audio");
                            return true;
                        }
                    });
                    player.prepare();
                    player.start();
                    audioState = 2;
                    adapter.notifyDataSetChanged();
                } catch (Exception ex) {
                    failAudio("This phone cannot play this audio");
                }
            }
        });
    }

    private void failAudio(String why) {
        stopAudio();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
    }

    private void stopAudio() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception e) {
                // not playing
            }
            player.release();
            player = null;
        }
        audioState = 0;
        audioId = "";
    }

    // ---------- drawing a message ----------

    private static String number(String id) {
        int at = id.indexOf('@');
        return at > 0 ? id.substring(0, at) : id;
    }

    private static String clockOf(long secs) {
        return (secs / 60) + ":" + (secs % 60 < 10 ? "0" : "") + (secs % 60);
    }

    private static String sizeOf(long bytes) {
        return bytes >= 1024 * 1024 ? String.format("%.1f MB", bytes / 1048576.0) : Math.max(1, bytes / 1024) + " KB";
    }

    /** What the bubble shows: the text, or a bracketed label for media/system messages. */
    private String describe(JSONObject m) {
        String body = m.optString("body");
        String type = m.optString("type");
        String label = null;
        if (type.equals("image")) label = "Photo";
        else if (type.equals("video")) label = "Video";
        else if (type.equals("ptt")) label = "Voice message";
        else if (type.equals("audio")) label = "Audio";
        else if (type.equals("sticker")) label = "Sticker";
        else if (type.equals("document")) label = "Document";
        else if (type.equals("location")) label = "Location";
        else if (type.equals("vcard") || type.equals("multi_vcard")) label = "Contact";
        else if (type.equals("poll_creation")) label = "Poll";
        else if (type.equals("revoked")) label = "This message was deleted";
        else if (type.equals("biz_content_placeholder")) label = "Business message";
        if (label == null) return body.length() > 0 ? body : "[Message]";
        // media with a caption: label first, then the caption
        return body.length() > 0 && !type.equals("revoked") ? "[" + label + "] " + body : "[" + label + "]";
    }

    /** The message being replied to: who wrote it and what it said, above the reply. */
    private View quoteView(Context c, JSONObject qt) {
        String from = qt.optString("from");
        String who;
        if ("You".equals(from)) who = "You";
        else if (group) who = number(qt.optString("participant"));
        else who = title;
        if (who.length() == 0) who = "Message";

        LinearLayout q = new LinearLayout(c);
        q.setOrientation(LinearLayout.HORIZONTAL);
        q.setBackgroundDrawable(UI.bubble(c, UI.QUOTE_BG));
        View bar = new View(c);
        bar.setBackgroundColor(UI.QUOTE_NAME);
        q.addView(bar, new LinearLayout.LayoutParams(UI.dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UI.dp(c, 6), UI.dp(c, 3), UI.dp(c, 6), UI.dp(c, 3));
        TextView w = UI.text(c, who, 12, UI.QUOTE_NAME, true);
        w.setSingleLine(true);
        col.addView(w);
        TextView t = UI.text(c, qt.optString("text"), 13, UI.QUOTE_TEXT, false);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(c.getResources().getDisplayMetrics().widthPixels * 6 / 10);
        col.addView(t);
        q.addView(col);
        LinearLayout.LayoutParams lp = UI.wrap();
        lp.bottomMargin = UI.dp(c, 4);
        q.setLayoutParams(lp);
        return q;
    }

    /** Photos and stickers are drawn as thumbnails; the bracketed label shows until (or unless) one loads. */
    private View mediaView(final Context c, final JSONObject m, boolean sticker) {
        JSONObject meta = m.optJSONObject("meta");
        int w = sticker ? Math.min(UI.dp(c, 150), c.getResources().getDisplayMetrics().widthPixels * 4 / 10)
                : Math.min(UI.dp(c, 230), c.getResources().getDisplayMetrics().widthPixels * 6 / 10);
        double ratio = sticker ? 1.0 : 0.75;
        if (meta != null && meta.optInt("w") > 0 && meta.optInt("h") > 0) {
            ratio = Math.max(0.5, Math.min(1.5, meta.optDouble("h") / meta.optDouble("w")));
        }
        int h = (int) (w * ratio);
        FrameLayout box = new FrameLayout(c);
        box.setBackgroundColor(UI.TINT);
        box.setLayoutParams(new LinearLayout.LayoutParams(w, h));

        final ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final TextView label = UI.text(c, sticker ? "[Sticker]" : "[Photo]", 14, UI.GREY, false);
        label.setGravity(Gravity.CENTER);
        box.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final String id = m.optString("id");
        Bitmap have = Media.cached(id, THUMB_PX);
        if (have != null) {
            iv.setImageBitmap(have);
            label.setVisibility(View.GONE);
        } else {
            iv.setTag(id);
            Media.load(c, id, THUMB_PX, new Media.Ready() {
                public void ready(Bitmap b) {
                    if (id.equals(iv.getTag())) {
                        iv.setImageBitmap(b);
                        label.setVisibility(View.GONE);
                    }
                }
            });
        }
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(c, ImageActivity.class);
                i.putExtra("id", id);
                startActivity(i);
            }
        });
        return box;
    }

    /** "[Play voice message] 0:03": tap to play, tap again to stop. */
    private View audioView(Context c, final JSONObject m) {
        JSONObject meta = m.optJSONObject("meta");
        long secs = meta == null ? 0 : meta.optLong("duration");
        boolean note = m.optString("type").equals("ptt");
        String what = note ? "voice message" : "audio";
        String text;
        if (m.optString("id").equals(audioId) && audioState == 1) text = "[Loading...]";
        else if (m.optString("id").equals(audioId) && audioState == 2) text = "[Stop " + what + "]" + (secs > 0 ? "  " + clockOf(secs) : "");
        else text = "[Play " + what + "]" + (secs > 0 ? "  " + clockOf(secs) : "");
        TextView t = UI.text(c, text, 16, UI.ACCENT, true);
        t.setPadding(UI.dp(c, 2), UI.dp(c, 6), UI.dp(c, 2), UI.dp(c, 6));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleAudio(m); }
        });
        return t;
    }

    /** "[Video] 0:19 - 4.5 MB, tap to play": downloaded and played full-screen on demand. */
    private View videoView(final Context c, final JSONObject m) {
        JSONObject meta = m.optJSONObject("meta");
        boolean gif = meta != null && meta.optBoolean("gif");
        long secs = meta == null ? 0 : meta.optLong("duration");
        final long size = meta == null ? 0 : meta.optLong("size");
        String text = (gif ? "[GIF]" : "[Video]") + (secs > 0 ? "  " + clockOf(secs) : "") + (size > 0 ? "  -  " + sizeOf(size) : "");
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(UI.TINT);
        int p = UI.dp(c, 10);
        box.setPadding(p, p, p, p);
        box.addView(UI.text(c, text, 16, UI.ACCENT, true));
        box.addView(UI.text(c, "Tap to play", 12, UI.GREY, false));
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(c, VideoActivity.class);
                i.putExtra("id", m.optString("id"));
                i.putExtra("size", size);
                startActivity(i);
            }
        });
        return box;
    }

    private final class MsgAdapter extends BaseAdapter {
        public int getCount() { return msgs.size(); }

        public Object getItem(int i) { return msgs.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View reuse, ViewGroup parent) {
            Context c = ChatActivity.this;
            JSONObject m = msgs.get(i);
            boolean mine = m.optBoolean("fromMe");

            LinearLayout wrap = new LinearLayout(c);
            wrap.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
            int p = UI.dp(c, 8);
            wrap.setPadding(p, UI.dp(c, 3), p, UI.dp(c, 3));

            LinearLayout b = new LinearLayout(c);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setBackgroundDrawable(UI.bubble(c, mine ? UI.MINE : UI.THEIRS));
            b.setPadding(UI.dp(c, 9), UI.dp(c, 5), UI.dp(c, 9), UI.dp(c, 4));
            if (group && !mine) {
                TextView t = UI.text(c, number(m.optString("sender")), 12, UI.ACCENT, true);
                t.setSingleLine(true);
                b.addView(t);
            }
            JSONObject qt = m.optJSONObject("quoted");
            if (qt != null) b.addView(quoteView(c, qt));

            String type = m.optString("type");
            boolean hasMedia = m.optBoolean("hasMedia");
            boolean pic = (type.equals("image") || type.equals("sticker")) && hasMedia;
            boolean voice = (type.equals("ptt") || type.equals("audio")) && hasMedia;
            boolean video = type.equals("video") && hasMedia;
            if (pic) b.addView(mediaView(c, m, type.equals("sticker")));
            else if (voice) b.addView(audioView(c, m));
            else if (video) b.addView(videoView(c, m));

            // under a picture, voice note or video only the caption is text
            String text = (pic || voice || video) ? m.optString("body") : describe(m);
            if (text.length() > 0) {
                TextView body = UI.text(c, text, 16, UI.TEXT, false);
                body.setMaxWidth(c.getResources().getDisplayMetrics().widthPixels * 7 / 10);
                b.addView(body);
            }
            Date when = new Date(m.optLong("ts") * 1000L);
            boolean today = day.format(when).equals(day.format(new Date()));
            TextView time = UI.text(c, (isEdited(m) ? "edited  " : "") + (today ? clock : dated).format(when), 11, UI.GREY, false);
            time.setGravity(Gravity.RIGHT);
            if (mine && m.has("ack") && !m.isNull("ack")) { // delivery ticks next to the time
                LinearLayout foot = new LinearLayout(c);
                foot.setOrientation(LinearLayout.HORIZONTAL);
                foot.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                foot.addView(time);
                TickView ticks = new TickView(c, m.optInt("ack"));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.leftMargin = UI.dp(c, 4);
                foot.addView(ticks, tp);
                b.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                b.addView(time);
            }
            wrap.addView(b);
            return wrap;
        }
    }
}
