package com.hari.bbwa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Simple photo picker: lists the newest images on the phone's storage and returns the chosen "path". */
public class PickImageActivity extends Activity {
    private static final int MAX_FILES = 150;
    private static final int MAX_DEPTH = 5;

    private final List<File> files = new ArrayList<File>();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final SimpleDateFormat when = new SimpleDateFormat("d MMM yyyy HH:mm");
    private BaseAdapter adapter;
    private TextView status;
    private volatile String diag = "";

    @Override
    protected void onCreate(Bundle b) {
        UI.theme(this);
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UI.SURFACE);
        root.addView(UI.header(this, "Choose a photo"), UI.wrap());
        status = UI.text(this, "Looking for photos...", 13, UI.GREY, false);
        status.setPadding(UI.dp(this, 10), UI.dp(this, 4), UI.dp(this, 10), UI.dp(this, 4));
        root.addView(status, UI.wrap());

        ListView list = new ListView(this);
        adapter = new PhotoAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Intent r = new Intent();
                r.putExtra("path", files.get(pos).getPath());
                setResult(RESULT_OK, r);
                finish();
            }
        });
        root.addView(list, UI.fill());
        setContentView(root);

        Bg.go(new Bg.Job() {
            public Object run() { return scan(); }
        }, new Bg.Done() {
            @SuppressWarnings("unchecked")
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                files.addAll((List<File>) result);
                adapter.notifyDataSetChanged();
                status.setText(files.isEmpty() ? "No photos found.\n\n" + diag : files.size() + " photos, newest first");
            }
        });
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdownNow();
        super.onDestroy();
    }

    /** Every place an Android 4.x phone (or BB10's Android runtime) might keep shared photos. */
    private static File[] candidateRoots() {
        String[] paths = {
            "/sdcard", "/storage/sdcard0", "/storage/emulated/0", "/storage/emulated/legacy", "/mnt/sdcard",
            "/storage/sdcard1", "/storage/extSdCard", "/mnt/extSdCard", "/accounts/1000/shared",
            "/accounts/1000/removable/sdcard", "/data/media/0"
        };
        List<File> out = new ArrayList<File>();
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) out.add(ext);
        for (String p : paths) out.add(new File(p));
        return out.toArray(new File[out.size()]);
    }

    private List<File> scan() {
        List<File> found = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        StringBuilder d = new StringBuilder("Looked in:\n");
        for (File r : candidateRoots()) {
            if (r == null) continue;
            String state = !r.exists() ? "missing" : !r.isDirectory() ? "not a folder" : !r.canRead() ? "not readable" : null;
            if (state != null) {
                d.append(r.getPath()).append(" - ").append(state).append("\n");
                continue;
            }
            String[] names = r.list();
            d.append(r.getPath()).append(" - ").append(names == null ? "cannot list" : names.length + " items");
            if (names != null && names.length > 0) {
                d.append(" (");
                for (int i = 0; i < Math.min(6, names.length); i++) d.append(i > 0 ? ", " : "").append(names[i]);
                d.append(names.length > 6 ? ", ..." : "").append(")");
            }
            d.append("\n");
            if (seen.add(canonical(r))) walk(r, 0, found, seen);
        }
        diag = d.toString();
        Collections.sort(found, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.lastModified() < b.lastModified() ? 1 : (a.lastModified() > b.lastModified() ? -1 : 0);
            }
        });
        return found.size() > MAX_FILES ? new ArrayList<File>(found.subList(0, MAX_FILES)) : found;
    }

    private void walk(File dir, int depth, List<File> out, Set<String> seen) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName().toLowerCase();
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH && !n.startsWith(".") && !n.equals("android") && seen.add(canonical(f))) walk(f, depth + 1, out, seen);
            } else if ((n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) && f.length() > 2000) {
                out.add(f);
            }
        }
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getPath();
        }
    }

    private final class PhotoAdapter extends BaseAdapter {
        public int getCount() { return files.size(); }

        public Object getItem(int i) { return files.get(i); }

        public long getItemId(int i) { return i; }

        public View getView(int i, View reuse, ViewGroup parent) {
            Context c = PickImageActivity.this;
            final File f = files.get(i);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = UI.dp(c, 8);
            row.setPadding(p, UI.dp(c, 6), p, UI.dp(c, 6));

            final ImageView iv = new ImageView(c);
            int size = UI.dp(c, 56);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = UI.dp(c, 10);
            iv.setLayoutParams(lp);
            iv.setBackgroundColor(UI.TINT);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setTag(f.getPath());
            final int px = size;
            thumbs.execute(new Runnable() {
                public void run() {
                    final Bitmap bm = Photos.thumb(f.getPath(), px);
                    if (bm == null) return;
                    Bg.MAIN.post(new Runnable() {
                        public void run() {
                            if (f.getPath().equals(iv.getTag())) iv.setImageBitmap(bm);
                        }
                    });
                }
            });
            row.addView(iv);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = UI.text(c, f.getName(), 15, UI.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(name);
            col.addView(UI.text(c, when.format(new Date(f.lastModified())) + "  -  " + (f.length() / 1024) + " KB", 12, UI.GREY, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }
}
