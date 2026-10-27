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
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
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
        olderBtn = UI.text(this, "Load older messages", 14, UI.GREEN, true);
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
        root.addView(list, UI.fill());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
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
        sendBtn = UI.button(this, "Send");
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { send(); }
        });
        bar.addView(sendBtn);
        root.addView(bar, UI.wrap());
        setContentView(root);

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
                list.setSelection(list.getCount() - 1); // cursor starts on the newest message, not on the top button
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

    private boolean add(JSONObject m) {
        if (m == null || !ids.add(m.optString("id"))) return false;
        msgs.add(m);
        return true;
    }

    private void sortAndShow() {
        Collections.sort(msgs, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return Long.valueOf(a.optLong("ts")).compareTo(Long.valueOf(b.optLong("ts")));
            }
        });
        adapter.notifyDataSetChanged();
    }

    // ---------- sending ----------

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        input.setText("");
        sendBtn.setEnabled(false);
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.send(ChatActivity.this, chatId, text); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                sendBtn.setEnabled(true);
                if (isFinishing()) return;
                if (e != null) {
                    input.setText(text);
                    input.setSelection(text.length());
                    Toast.makeText(ChatActivity.this, "Not sent: " + Api.why(e), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONObject m = (JSONObject) result;
                if (m != null && m.has("body") && add(m)) sortAndShow();
            }
        });
    }

    private void emojiPicker() {
        final String[] emoji = Emoji.all();
        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return emoji.length; }

            public Object getItem(int i) { return emoji[i]; }

            public long getItemId(int i) { return i; }

            public View getView(int i, View reuse, ViewGroup parent) {
                TextView t = new TextView(ChatActivity.this);
                t.setText(emoji[i]);
                t.setTextSize(26);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, UI.dp(ChatActivity.this, 6), 0, UI.dp(ChatActivity.this, 6));
                return t;
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                int s = Math.max(0, input.getSelectionStart());
                int e = Math.max(0, input.getSelectionEnd());
                input.getText().replace(Math.min(s, e), Math.max(s, e), emoji[pos], 0, emoji[pos].length());
            }
        });
        LinearLayout box = new LinearLayout(this);
        box.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 300)));
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
        if (result != RESULT_OK || data == null) return;
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
        q.setBackgroundDrawable(UI.bubble(c, Color.parseColor("#14000000")));
        View bar = new View(c);
        bar.setBackgroundColor(UI.GREEN);
        q.addView(bar, new LinearLayout.LayoutParams(UI.dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UI.dp(c, 6), UI.dp(c, 3), UI.dp(c, 6), UI.dp(c, 3));
        TextView w = UI.text(c, who, 12, UI.GREEN, true);
        w.setSingleLine(true);
        col.addView(w);
        TextView t = UI.text(c, qt.optString("text"), 13, UI.GREY, false);
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
        box.setBackgroundColor(Color.parseColor("#14000000"));
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
        TextView t = UI.text(c, text, 16, UI.GREEN, true);
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
        box.setBackgroundColor(Color.parseColor("#14000000"));
        int p = UI.dp(c, 10);
        box.setPadding(p, p, p, p);
        box.addView(UI.text(c, text, 16, UI.GREEN, true));
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
            b.setBackgroundDrawable(UI.bubble(c, mine ? UI.MINE : Color.WHITE));
            b.setPadding(UI.dp(c, 9), UI.dp(c, 5), UI.dp(c, 9), UI.dp(c, 4));
            if (group && !mine) {
                TextView t = UI.text(c, number(m.optString("sender")), 12, UI.GREEN, true);
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
                TextView body = UI.text(c, text, 16, Color.BLACK, false);
                body.setMaxWidth(c.getResources().getDisplayMetrics().widthPixels * 7 / 10);
                b.addView(body);
            }
            Date when = new Date(m.optLong("ts") * 1000L);
            boolean today = day.format(when).equals(day.format(new Date()));
            TextView time = UI.text(c, (today ? clock : dated).format(when), 11, UI.GREY, false);
            time.setGravity(Gravity.RIGHT);
            b.addView(time);
            wrap.addView(b);
            return wrap;
        }
    }
}
