package com.hari.stash;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/** Two tabs: Cloud (the home drop-box) and Local (this phone). Both list photos, videos and music. */
public class MainActivity extends Activity implements MusicService.Listener {
    private interface Work {
        /** Returns the message to show when done, or null for the default one. */
        String run(Http.Progress p) throws Exception;
    }

    private static final int[] CLOUD_FILTERS = { Item.ALL, Item.PHOTO, Item.VIDEO, Item.AUDIO, Item.OTHER };
    private static final String[] CLOUD_LABELS = { "All", "Photos", "Videos", "Music", "Files" };
    private static final int[] LOCAL_FILTERS = CLOUD_FILTERS;
    private static final String[] LOCAL_LABELS = CLOUD_LABELS;

    private final SimpleDateFormat when = new SimpleDateFormat("d MMM yyyy");
    private final List<Item> shown = new ArrayList<Item>();
    private List<Item> cloudAll, localAll;   // null = not loaded yet
    private String cloudErr;
    private boolean loadingCloud, loadingLocal;
    private long cloudLoadedAt;
    private boolean cloudTab;
    private int cloudFilter = Item.ALL, localFilter = Item.ALL;

    private boolean selecting;
    private final Set<String> selected = new HashSet<String>();
    private View selBar;
    private TextView titleView, selTransfer, selDelete;

    private TextView tabCloud, tabLocal, info, empty, miniTitle;
    private View lineCloud, lineLocal, mini;
    private Glyph miniPlay;
    private LinearLayout chips;
    private ListView list;
    private GridView grid;
    private BaseAdapter adapter, tileAdapter;
    private boolean gridMode;
    private int tilePx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cloudTab = "cloud".equals(Cfg.getPref("tab", "local"));
        gridMode = !"list".equals(Cfg.getPref("layout", "grid"));
        cloudFilter = parse(Cfg.getPref("cloudFilter", ""), Item.ALL);
        localFilter = parse(Cfg.getPref("localFilter", ""), Item.ALL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UI.BG);

        LinearLayout head = new LinearLayout(this);
        head.setBackgroundColor(UI.BAR);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 6), UI.dp(this, 6));
        titleView = UI.text(this, "BB File Manager", 20, Color.WHITE, true);
        head.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView menu = UI.text(this, "Menu", 15, Color.WHITE, true);
        menu.setPadding(UI.dp(this, 12), UI.dp(this, 8), UI.dp(this, 12), UI.dp(this, 8));
        menu.setBackgroundDrawable(UI.focusFill());
        menu.setFocusable(true);
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(); }
        });
        head.addView(menu);
        root.addView(head, UI.wrap());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundColor(UI.SURFACE);
        tabCloud = new TextView(this);
        tabLocal = new TextView(this);
        lineCloud = new View(this);
        lineLocal = new View(this);
        tabs.addView(tab(tabCloud, lineCloud, "Cloud", true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tab(tabLocal, lineLocal, "Local", false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs, UI.wrap());

        chips = new LinearLayout(this);
        chips.setPadding(UI.dp(this, 4), UI.dp(this, 4), UI.dp(this, 4), UI.dp(this, 4));
        root.addView(chips, UI.wrap());

        info = UI.text(this, "", 12, UI.GREY, false);
        info.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 4));
        root.addView(info, UI.wrap());

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (selecting) toggle(shown.get(pos));
                else open(pos);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (!selecting) startSelect();
                toggle(shown.get(pos));
                return true;
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));

        int gap = UI.dp(this, 3);
        int cols = 3;
        tilePx = (getResources().getDisplayMetrics().widthPixels - gap * (cols + 1)) / cols;
        grid = new GridView(this);
        tileAdapter = new TileAdapter();
        grid.setAdapter(tileAdapter);
        grid.setNumColumns(cols);
        grid.setColumnWidth(tilePx);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(gap, gap, gap, gap);
        grid.setClipToPadding(false);
        grid.setSelector(UI.cursor(this));
        grid.setDrawSelectorOnTop(true);
        grid.setOnItemClickListener(list.getOnItemClickListener());
        grid.setOnItemLongClickListener(list.getOnItemLongClickListener());
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = UI.text(this, "", 15, UI.GREY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(UI.dp(this, 20), UI.dp(this, 20), UI.dp(this, 20), UI.dp(this, 20));
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, UI.fill());

        selBar = buildSelBar();
        root.addView(selBar, UI.wrap());
        mini = buildMini();
        root.addView(mini, UI.wrap());
        setContentView(root);

        buildChips();
        styleTabs();
        applyMode();
        render();
    }

    private void applyMode() {
        list.setVisibility(gridMode ? View.GONE : View.VISIBLE);
        grid.setVisibility(gridMode ? View.VISIBLE : View.GONE);
    }

    private void changeViews() {
        adapter.notifyDataSetChanged();
        tileAdapter.notifyDataSetChanged();
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private View tab(final TextView t, View line, String label, final boolean cloud) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        t.setText(label);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, UI.dp(this, 10), 0, UI.dp(this, 8));
        col.addView(t, UI.wrap());
        col.addView(line, new LinearLayout.LayoutParams(-1, UI.dp(this, 3)));
        col.setFocusable(true);
        col.setBackgroundDrawable(UI.focusFill());
        col.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectTab(cloud); }
        });
        return col;
    }

    private View buildMini() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(UI.TILE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UI.dp(this, 10), UI.dp(this, 4), UI.dp(this, 4), UI.dp(this, 4));
        bar.setFocusable(true);
        bar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, PlayerActivity.class)); }
        });
        Glyph note = new Glyph(this, Glyph.NOTE, UI.ACCENT);
        bar.addView(note, new LinearLayout.LayoutParams(UI.dp(this, 28), UI.dp(this, 28)));
        miniTitle = UI.text(this, "", 14, UI.TEXT, false);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniTitle.setPadding(UI.dp(this, 8), 0, UI.dp(this, 8), 0);
        bar.addView(miniTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        miniPlay = new Glyph(this, Glyph.PAUSE, UI.TEXT);
        miniPlay.setFocusable(true);
        miniPlay.setBackgroundDrawable(UI.focusFill());
        miniPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { MusicService.toggle(); }
        });
        bar.addView(miniPlay, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 44)));
        bar.setVisibility(View.GONE);
        return bar;
    }

    // ---- tabs and filters ----

    @Override
    public void onBackPressed() {
        if (selecting) endSelect();
        else super.onBackPressed();
    }

    private void selectTab(boolean cloud) {
        if (cloud == cloudTab) return;
        if (selecting) endSelect();
        cloudTab = cloud;
        Cfg.putPref("tab", cloud ? "cloud" : "local");
        buildChips();
        styleTabs();
        ((View) (cloud ? tabCloud : tabLocal).getParent()).requestFocus();
        render();
        load(false);
    }

    private void styleTabs() {
        tabCloud.setTextColor(cloudTab ? UI.ACCENT : UI.GREY);
        tabLocal.setTextColor(cloudTab ? UI.GREY : UI.ACCENT);
        lineCloud.setBackgroundColor(cloudTab ? UI.ACCENT : Color.TRANSPARENT);
        lineLocal.setBackgroundColor(cloudTab ? Color.TRANSPARENT : UI.ACCENT);
    }

    private void buildChips() {
        buildChips(Integer.MIN_VALUE);
    }

    /** Rebuilds the filter chips; focusKind is the chip that keeps the cursor (the views are new, so focus must be put back). */
    private void buildChips(int focusKind) {
        chips.removeAllViews();
        final int[] kinds = cloudTab ? CLOUD_FILTERS : LOCAL_FILTERS;
        String[] labels = cloudTab ? CLOUD_LABELS : LOCAL_LABELS;
        int cur = cloudTab ? cloudFilter : localFilter;
        for (int i = 0; i < kinds.length; i++) {
            final int kind = kinds[i];
            TextView c = UI.text(this, labels[i], 14, kind == cur ? Color.WHITE : UI.GREY, kind == cur);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, UI.dp(this, 7), 0, UI.dp(this, 7));
            c.setBackgroundDrawable(kind == cur ? UI.bubble(this, UI.BAR) : UI.focusFill());
            c.setFocusable(true);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (cloudTab) cloudFilter = kind;
                    else localFilter = kind;
                    Cfg.putPref(cloudTab ? "cloudFilter" : "localFilter", String.valueOf(kind));
                    buildChips(kind);
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = lp.rightMargin = UI.dp(this, 2);
            chips.addView(c, lp);
            if (kind == focusKind) c.requestFocus();
        }
    }

    // ---- loading ----

    @Override
    protected void onResume() {
        super.onResume();
        MusicService.listen(this);
        changed();
        load(false);
    }

    @Override
    protected void onPause() {
        MusicService.unlisten(this);
        super.onPause();
    }

    private void load(boolean force) {
        if (cloudTab) {
            if (Cfg.ready() && !loadingCloud && (force || cloudAll == null || System.currentTimeMillis() - cloudLoadedAt > 30000)) loadCloud();
        } else if (!loadingLocal && (force || localAll == null)) {
            loadLocal();
        }
        render();
    }

    private void loadCloud() {
        loadingCloud = true;
        Bg.go(new Bg.Job() {
            public Object run() throws Exception {
                JSONArray a = new JSONArray(Http.text(Cfg.list()));
                List<Item> out = new ArrayList<Item>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Item it = new Item();
                    it.name = o.getString("name");
                    it.size = o.getLong("size");
                    it.mtime = o.getLong("mtime");
                    it.cloud = true;
                    it.kind = Item.kindOf(it.name);
                    out.add(it);
                }
                return out;
            }
        }, new Bg.Done() {
            @SuppressWarnings("unchecked")
            public void done(Object r, Exception e) {
                loadingCloud = false;
                cloudLoadedAt = System.currentTimeMillis();
                if (e != null) {
                    cloudErr = e instanceof org.json.JSONException ? "That address answered, but it is not the drop-box." : Http.why(e);
                } else {
                    cloudErr = null;
                    cloudAll = (List<Item>) r;
                }
                render();
            }
        });
        render();
    }

    private void loadLocal() {
        loadingLocal = true;
        Bg.go(new Bg.Job() {
            public Object run() { return Local.scan(MainActivity.this); }
        }, new Bg.Done() {
            @SuppressWarnings("unchecked")
            public void done(Object r, Exception e) {
                loadingLocal = false;
                localAll = r == null ? new ArrayList<Item>() : (List<Item>) r;
                render();
            }
        });
        render();
    }

    private void render() {
        List<Item> src = cloudTab ? cloudAll : localAll;
        int f = cloudTab ? cloudFilter : localFilter;
        shown.clear();
        if (src != null) {
            for (Item it : src) if (f == Item.ALL || it.kind == f) shown.add(it);
        }
        final boolean byName = f == Item.AUDIO;
        Collections.sort(shown, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                if (byName) return a.name.compareToIgnoreCase(b.name);
                return a.millis() < b.millis() ? 1 : (a.millis() > b.millis() ? -1 : 0);
            }
        });
        changeViews();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);

        String msg;
        String line = "";
        if (cloudTab && !Cfg.ready()) {
            msg = "Not connected to the drop-box yet.\n\nPress the BlackBerry key (or Menu), then Set up cloud, and enter the address and PIN you use to open the drop-box page.";
        } else if (cloudTab && cloudAll == null) {
            msg = cloudErr != null ? cloudErr + "\n\nPress Menu to refresh or change the address." : "Loading the drop-box...";
        } else if (!cloudTab && localAll == null) {
            msg = "Looking through the phone for photos, videos and music...";
        } else if (shown.isEmpty()) {
            boolean anyLocal = !cloudTab && localAll != null && localAll.isEmpty();
            msg = "Nothing here.";
            if (anyLocal) msg += "\n\nNo photos, videos or music were found on this phone.\n\n" + Local.diag;
            else if (cloudTab) msg += "\n\nPut files in the drop-box's shared folder on the computer.";
        } else {
            msg = "";
        }
        empty.setText(msg);
        if (cloudTab) {
            if (loadingCloud && cloudAll != null) line = "Refreshing...";
            else if (cloudErr != null && cloudAll != null) line = cloudErr;
            else if (cloudAll != null) line = shown.size() + " in the drop-box";
        } else if (localAll != null) {
            line = loadingLocal ? "Rescanning..." : shown.size() + " on this phone";
        }
        info.setText(line);
    }

    // ---- opening and actions ----

    private void open(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        Item it = shown.get(pos);
        if (it.kind == Item.PHOTO) {
            List<Item> photos = new ArrayList<Item>();
            int idx = 0;
            for (Item x : shown) {
                if (x.kind != Item.PHOTO) continue;
                if (x == it) idx = photos.size();
                photos.add(x);
            }
            ViewerActivity.items = photos;
            ViewerActivity.start = idx;
            startActivity(new Intent(this, ViewerActivity.class));
        } else if (it.kind == Item.VIDEO) {
            startActivity(new Intent(this, VideoActivity.class).putExtra("src", it.src()).putExtra("name", it.name));
        } else if (it.kind == Item.AUDIO) {
            List<Item> tracks = new ArrayList<Item>();
            int idx = 0;
            for (Item x : shown) {
                if (x.kind != Item.AUDIO) continue;
                if (x == it) idx = tracks.size();
                tracks.add(x);
            }
            MusicService.play(this, tracks, idx);
            startActivity(new Intent(this, PlayerActivity.class));
        } else if (it.cloud) {
            actions(it);
        } else {
            openOther(it);
        }
    }

    /** Hands a local file that is not a photo, video or song to whatever app on the phone can open it (APKs install). */
    private void openOther(Item it) {
        String ext = Item.ext(it.name).toLowerCase();
        String mime = ext.equals("apk") ? "application/vnd.android.package-archive"
                : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        Uri data = it.path != null && new File(it.path).canRead() ? Uri.fromFile(new File(it.path))
                : it.uri != null ? Uri.parse(it.uri) : null;
        try {
            if (data == null) throw new android.content.ActivityNotFoundException();
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(data, mime == null ? "*/*" : mime));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Nothing on this phone can open this kind of file.", Toast.LENGTH_SHORT).show();
        }
    }

    private void actions(final Item it) {
        final String label = it.cloud ? "Download to this phone" : "Upload to the drop-box";
        new AlertDialog.Builder(this).setTitle(it.name)
                .setItems(new String[] { label }, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (it.cloud) download(it);
                        else upload(it);
                    }
                }).show();
    }

    private void download(final Item it) {
        File dir = Local.saveDir();
        if (dir == null) {
            Toast.makeText(this, "Found no folder on this phone that the app can write to.", Toast.LENGTH_LONG).show();
            return;
        }
        final File dest = Local.unique(dir, it.name);
        transfer("Downloading " + it.name, new Work() {
            public String run(Http.Progress p) throws Exception {
                Http.download(Cfg.file("files", it.name), dest, p);
                return null;
            }
        }, "Saved to " + dest.getPath(), new Runnable() {
            public void run() {
                android.media.MediaScannerConnection.scanFile(MainActivity.this, new String[] { dest.getPath() }, null, null);
                localAll = null;
                if (!cloudTab) loadLocal();
            }
        });
    }

    private void upload(final Item it) {
        if (!Cfg.ready()) {
            Toast.makeText(this, "Set up the cloud first (Menu).", Toast.LENGTH_LONG).show();
            return;
        }
        transfer("Uploading " + it.name, new Work() {
            public String run(Http.Progress p) throws Exception {
                Http.upload(it.open(), it.size, Cfg.raw(it.name), p);
                return null;
            }
        }, "Uploaded " + it.name, new Runnable() {
            public void run() { cloudAll = null; if (cloudTab) loadCloud(); }
        });
    }

    private void transfer(String title, final Work work, final String okMsg, final Runnable after) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(title);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(true);
        final Http.Progress p = new Http.Progress() {
            private int last = -1;

            void bytes(long done, long total) {
                if (total <= 0) return;
                final int pct = (int) (done * 100 / total);
                if (pct == last) return;
                last = pct;
                Bg.MAIN.post(new Runnable() {
                    public void run() { pd.setProgress(pct); }
                });
            }
        };
        pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { p.cancel = true; }
        });
        pd.show();
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return work.run(p); }
        }, new Bg.Done() {
            public void done(Object r, Exception e) {
                try {
                    pd.dismiss();
                } catch (RuntimeException x) {
                    // activity already gone
                }
                if (e != null) {
                    Toast.makeText(MainActivity.this, p.cancel ? "Cancelled." : Http.why(e), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, r != null ? (String) r : okMsg, Toast.LENGTH_LONG).show();
                }
                after.run();
            }
        });
    }

    // ---- menu and setup ----

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) { // the BlackBerry key arrives as MENU
            showMenu();
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void showMenu() {
        final List<String> labels = new ArrayList<String>();
        labels.add("Refresh");
        labels.add("Select files");
        labels.add(gridMode ? "List view" : "Grid view");
        labels.add("Set up cloud");
        labels.add("Storage info");
        if (MusicService.current() != null) labels.add("Stop music");
        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String l = labels.get(which);
                        if (l.equals("Refresh")) {
                            if (cloudTab) loadCloudIfReady();
                            else loadLocal();
                        } else if (l.equals("Select files")) {
                            startSelect();
                        } else if (l.equals("List view") || l.equals("Grid view")) {
                            gridMode = l.equals("Grid view");
                            Cfg.putPref("layout", gridMode ? "grid" : "list");
                            applyMode();
                        } else if (l.equals("Set up cloud")) {
                            setup();
                        } else if (l.equals("Storage info")) {
                            storageInfo();
                        } else {
                            MusicService.stop();
                        }
                    }
                }).show();
    }

    private void storageInfo() {
        final List<Item> snapshot = localAll;
        Bg.go(new Bg.Job() {
            public Object run() { return Local.report(MainActivity.this, snapshot); }
        }, new Bg.Done() {
            public void done(Object r, Exception e) {
                if (isFinishing()) return;
                new AlertDialog.Builder(MainActivity.this).setTitle("Storage info")
                        .setMessage(e != null ? String.valueOf(e) : (String) r).setPositiveButton("Close", null).show();
            }
        });
    }

    private void loadCloudIfReady() {
        if (Cfg.ready() && !loadingCloud) loadCloud();
        else render();
    }

    private void setup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UI.dp(this, 16);
        box.setPadding(p, p / 2, p, 0);
        final EditText addr = new EditText(this);
        addr.setHint("192.168.1.50:8000");
        addr.setText(Cfg.base().replace("http://", ""));
        addr.setSingleLine(true);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText pin = new EditText(this);
        pin.setHint("PIN (leave empty if none)");
        pin.setText(Cfg.pin());
        pin.setSingleLine(true);
        box.addView(UI.text(this, "Drop-box address (as in the browser, without http://)", 13, UI.GREY, false));
        box.addView(addr);
        box.addView(UI.text(this, "PIN", 13, UI.GREY, false));
        box.addView(pin);
        new AlertDialog.Builder(this).setTitle("Set up cloud").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save and connect", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Cfg.save(addr.getText().toString(), pin.getText().toString());
                        cloudAll = null;
                        cloudErr = null;
                        if (!cloudTab) selectTab(true);
                        else load(true);
                    }
                }).show();
    }

    // ---- selecting, bulk transfer and delete ----

    private View buildSelBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(UI.BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UI.dp(this, 4), UI.dp(this, 2), UI.dp(this, 4), UI.dp(this, 2));
        bar.addView(barButton("All", new View.OnClickListener() {
            public void onClick(View v) {
                for (Item it : shown) selected.add(it.key());
                selectionChanged();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selTransfer = barButton("", new View.OnClickListener() {
            public void onClick(View v) { transferSelected(); }
        });
        bar.addView(selTransfer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        selDelete = barButton("Delete", new View.OnClickListener() {
            public void onClick(View v) { deleteSelected(); }
        });
        bar.addView(selDelete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(barButton("Cancel", new View.OnClickListener() {
            public void onClick(View v) { endSelect(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.setVisibility(View.GONE);
        return bar;
    }

    private TextView barButton(String label, View.OnClickListener l) {
        TextView t = UI.text(this, label, 14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, UI.dp(this, 10), 0, UI.dp(this, 10));
        t.setBackgroundDrawable(UI.focusFill());
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    private void startSelect() {
        selecting = true;
        selected.clear();
        selectionChanged();
    }

    private void endSelect() {
        selecting = false;
        selected.clear();
        selectionChanged();
        (gridMode ? (View) grid : list).requestFocus(); // the bar that had the cursor is now hidden
    }

    private void toggle(Item it) {
        if (!selected.remove(it.key())) selected.add(it.key());
        selectionChanged();
    }

    private void selectionChanged() {
        selBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        titleView.setText(selecting ? selected.size() + " selected" : "BB File Manager");
        selTransfer.setText(cloudTab ? "Download" : "Upload");
        boolean any = !selected.isEmpty();
        selTransfer.setAlpha(any ? 1f : 0.4f);
        selDelete.setAlpha(any ? 1f : 0.4f);
        changeViews();
    }

    private List<Item> picked() {
        List<Item> out = new ArrayList<Item>();
        List<Item> src = cloudTab ? cloudAll : localAll;
        if (src != null) for (Item it : src) if (selected.contains(it.key())) out.add(it);
        return out;
    }

    /** Download (cloud tab) or upload (local tab) everything selected. */
    private void transferSelected() {
        final List<Item> items = picked();
        if (items.isEmpty()) return;
        final boolean down = cloudTab;
        if (down) {
            if (Local.saveDir() == null) {
                Toast.makeText(this, "Found no folder on this phone that the app can write to.", Toast.LENGTH_LONG).show();
                return;
            }
        } else if (!Cfg.ready()) {
            Toast.makeText(this, "Set up the cloud first (Menu).", Toast.LENGTH_LONG).show();
            return;
        }
        final File dir = down ? Local.saveDir() : null;
        final int n = items.size();
        transfer((down ? "Downloading " : "Uploading ") + n + (n == 1 ? " file" : " files"), new Work() {
            public String run(final Http.Progress p) throws Exception {
                int ok = 0;
                for (int i = 0; i < n && !p.cancel; i++) {
                    final Item it = items.get(i);
                    final int base = i * 100;
                    Http.Progress sub = new Http.Progress() {
                        void bytes(long done, long total) {
                            p.bytes(base + (total > 0 ? done * 100 / total : 0), n * 100L);
                            if (p.cancel) cancel = true;
                        }
                    };
                    if (down) Http.download(Cfg.file("files", it.name), Local.unique(dir, it.name), sub);
                    else Http.upload(it.open(), it.size, Cfg.raw(it.name), sub);
                    ok++;
                }
                return (down ? "Downloaded " : "Uploaded ") + ok + (ok == 1 ? " file" : " files")
                        + (down ? " to " + dir.getPath() : " to the drop-box");
            }
        }, "", new Runnable() {
            public void run() {
                endSelect();
                if (down) {
                    localAll = null;
                } else {
                    cloudAll = null;
                }
                load(true);
            }
        });
    }

    private void deleteSelected() {
        final List<Item> items = picked();
        if (items.isEmpty()) return;
        final int n = items.size();
        final boolean fromCloud = cloudTab;
        String what = n == 1 ? "\"" + items.get(0).name + "\"" : n + " files";
        new AlertDialog.Builder(this).setTitle("Delete " + what + "?")
                .setMessage(fromCloud ? "They move to the .trash folder inside the drop-box's shared folder on the computer, so you can still get them back there."
                        : "They are deleted from this phone. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { runDelete(items, fromCloud); }
                }).show();
    }

    private void runDelete(final List<Item> items, final boolean fromCloud) {
        final int n = items.size();
        transfer("Deleting", new Work() {
            public String run(Http.Progress p) throws Exception {
                int ok = 0;
                String firstError = null;
                for (int i = 0; i < n && !p.cancel; i++) {
                    Item it = items.get(i);
                    try {
                        if (fromCloud) {
                            Http.post(Cfg.delete(it.name));
                            ok++;
                        } else if (deleteLocal(it)) {
                            ok++;
                        } else if (firstError == null) {
                            firstError = "the phone would not delete " + it.name;
                        }
                    } catch (Exception e) {
                        if (firstError == null) firstError = Http.why(e);
                    }
                    p.bytes(i + 1, n);
                }
                return "Deleted " + ok + " of " + n + (firstError != null ? " (" + firstError + ")" : "");
            }
        }, "", new Runnable() {
            public void run() {
                endSelect();
                if (fromCloud) cloudAll = null;
                else localAll = null;
                load(true);
            }
        });
    }

    /** Removes a file from the phone: the file itself when we can reach it, and its entry in the media database. */
    private boolean deleteLocal(Item it) {
        boolean ok = false;
        if (it.path != null) {
            File f = new File(it.path);
            if (f.exists()) ok = f.delete();
        }
        if (it.uri != null) {
            try {
                ok = getContentResolver().delete(Uri.parse(it.uri), null, null) > 0 || ok;
            } catch (RuntimeException e) {
                // keep whatever the file delete said
            }
        }
        return ok;
    }

    // ---- now playing bar ----

    public void changed() {
        Item it = MusicService.current();
        mini.setVisibility(it == null ? View.GONE : View.VISIBLE);
        if (it != null) {
            miniTitle.setText(MusicService.error() != null ? "Cannot play " + it.name : it.title());
            miniPlay.set(MusicService.playing() ? Glyph.PAUSE : Glyph.PLAY, true);
        }
    }

    // ---- rows ----

    private final class RowAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }

        public Object getItem(int i) { return shown.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View reuse, ViewGroup parent) {
            Row r;
            if (reuse == null) {
                r = new Row(MainActivity.this);
                reuse = r.root;
                reuse.setTag(r);
            } else {
                r = (Row) reuse.getTag();
            }
            Item it = shown.get(i);
            r.glyph.set(Glyph.forKind(it.kind), true);
            Thumbs.into(r.pic, it, r.px);
            r.name.setText(it.kind == Item.AUDIO ? it.title() : it.name);
            String ext = Item.ext(it.name);
            r.ck.setVisibility(selecting ? View.VISIBLE : View.GONE);
            r.ck.setChecked(selected.contains(it.key()));
            r.sub.setText((it.kind == Item.AUDIO ? ext + "  -  " : "") + UI.size(it.size) + "  -  " + when.format(new Date(it.millis())));
            return reuse;
        }
    }

    private final class TileAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }

        public Object getItem(int i) { return shown.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View reuse, ViewGroup parent) {
            Tile t;
            if (reuse == null) {
                t = new Tile(MainActivity.this, tilePx);
                reuse = t.root;
                reuse.setTag(t);
            } else {
                t = (Tile) reuse.getTag();
            }
            Item it = shown.get(i);
            boolean media = it.kind == Item.PHOTO || it.kind == Item.VIDEO;
            t.glyph.set(Glyph.forKind(it.kind), true);
            Thumbs.into(t.pic, it, tilePx);
            t.name.setText(it.kind == Item.AUDIO ? it.title() : it.name);
            t.name.setVisibility(media ? View.GONE : View.VISIBLE);
            t.badge.setVisibility(it.kind == Item.VIDEO ? View.VISIBLE : View.GONE);
            boolean on = selecting && selected.contains(it.key());
            t.ck.setVisibility(selecting ? View.VISIBLE : View.GONE);
            t.ck.setChecked(on);
            t.dim.setVisibility(on ? View.VISIBLE : View.GONE);
            return reuse;
        }
    }

    private static final class Tile {
        final FrameLayout root;
        final Glyph glyph, badge;
        final ImageView pic;
        final TextView name;
        final CheckBox ck;
        final View dim;

        Tile(Activity a, int size) {
            root = new FrameLayout(a);
            root.setLayoutParams(new android.widget.AbsListView.LayoutParams(size, size));
            root.setBackgroundColor(UI.TILE);
            glyph = new Glyph(a, Glyph.FILE, UI.GREY);
            int g = size / 2;
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(g, g, Gravity.CENTER);
            gl.bottomMargin = UI.dp(a, 10);
            root.addView(glyph, gl);
            pic = new ImageView(a);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(pic, new FrameLayout.LayoutParams(-1, -1));
            badge = new Glyph(a, Glyph.PLAY, Color.WHITE);
            int b = UI.dp(a, 24);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(b, b, Gravity.BOTTOM | Gravity.LEFT);
            bl.leftMargin = bl.bottomMargin = UI.dp(a, 4);
            root.addView(badge, bl);
            name = UI.text(a, "", 12, UI.TEXT, false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setBackgroundColor(0x99000000);
            name.setPadding(UI.dp(a, 4), UI.dp(a, 3), UI.dp(a, 4), UI.dp(a, 3));
            root.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            dim = new View(a);
            dim.setBackgroundColor(0x5542A5F5);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            ck = new CheckBox(a);
            ck.setFocusable(false);
            ck.setClickable(false);
            root.addView(ck, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
        }
    }

    private static final class Row {
        final LinearLayout root;
        final Glyph glyph;
        final ImageView pic;
        final TextView name, sub;
        final CheckBox ck;
        final int px;

        Row(Activity a) {
            px = UI.dp(a, 56);
            root = new LinearLayout(a);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(UI.dp(a, 10), UI.dp(a, 6), UI.dp(a, 10), UI.dp(a, 6));
            FrameLayout thumb = new FrameLayout(a);
            thumb.setBackgroundColor(UI.TILE);
            glyph = new Glyph(a, Glyph.FILE, UI.GREY);
            thumb.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
            pic = new ImageView(a);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.addView(pic, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(px, px);
            tl.rightMargin = UI.dp(a, 10);
            root.addView(thumb, tl);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            name = UI.text(a, "", 15, UI.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub = UI.text(a, "", 12, UI.GREY, false);
            sub.setSingleLine(true);
            col.addView(name);
            col.addView(sub);
            root.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ck = new CheckBox(a);
            ck.setFocusable(false);
            ck.setClickable(false);
            ck.setVisibility(View.GONE);
            root.addView(ck);
        }
    }
}
