package com.hari.stash;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the photos, videos and music on the phone. BB10's Android runtime hides the real shared folders from
 * file access but lists them in the media database, so that is the main source (files are opened through
 * content:// URIs). Walking the app's own storage adds files saved there (downloads).
 */
final class Local {
    private static final int MAX_DEPTH = 6;
    private static final int MAX_FILES = 8000;
    private static final long MIN_PHOTO_BYTES = 4000; // skips icons and cache thumbnails

    /** What the last scan looked at, for the Storage info screen. */
    static volatile String diag = "";

    private Local() {}

    /** Places an Android 4.x phone (or BB10's Android runtime) might keep files. */
    static List<File> roots() {
        String[] paths = {
            "/sdcard", "/storage/sdcard0", "/storage/emulated/0", "/storage/emulated/legacy", "/mnt/sdcard",
            "/storage/sdcard1", "/storage/extSdCard", "/mnt/extSdCard", "/accounts/1000/shared",
            "/accounts/1000/removable/sdcard", "/data/media/0",
            // BB10's shared folders (the ones File Manager shows under Device)
            "/accounts/1000/shared/camera", "/accounts/1000/shared/photos", "/accounts/1000/shared/videos",
            "/accounts/1000/shared/music", "/accounts/1000/shared/documents", "/accounts/1000/shared/downloads",
            "/accounts/1000/shared/books", "/accounts/1000/shared/misc", "/accounts/1000/shared/voice"
        };
        List<File> out = new ArrayList<File>();
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) out.add(ext);
        for (String p : paths) out.add(new File(p));
        return out;
    }

    static List<Item> scan(Context c) {
        List<Item> found = new ArrayList<Item>();
        Set<String> paths = new HashSet<String>();
        StringBuilder d = new StringBuilder();

        // 1. the media database
        d.append("Media database:\n");
        query(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Item.PHOTO, null, found, paths, d);
        query(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Item.VIDEO, null, found, paths, d);
        query(c, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Item.AUDIO, null, found, paths, d);
        // everything else (documents, APKs, ...): media_type 0 = "not media"
        query(c, MediaStore.Files.getContentUri("external"), Item.OTHER, "media_type=0", found, paths, d);

        // 2. the app's own storage (and any folder that happens to be readable)
        d.append("\nFolders:\n");
        Set<String> seen = new HashSet<String>();
        for (File r : roots()) {
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
            if (seen.add(canonical(r))) walk(r, 0, found, seen, paths);
        }
        diag = d.toString();
        return found;
    }

    private static void query(Context c, Uri base, int kind, String where, List<Item> out, Set<String> paths, StringBuilder d) {
        Cursor q = null;
        int before = out.size();
        try {
            String[] cols = kind == Item.PHOTO
                    ? new String[] { "_id", "_display_name", "_data", "_size", "date_modified", "orientation" }
                    : new String[] { "_id", "_display_name", "_data", "_size", "date_modified" };
            q = c.getContentResolver().query(base, cols, where, null, null);
            if (q == null) {
                d.append(" no answer for kind ").append(kind).append("\n");
                return;
            }
            while (q.moveToNext() && out.size() < MAX_FILES) {
                Item it = new Item();
                it.mid = q.getLong(0);
                String data = q.getString(2);
                String name = q.getString(1);
                if (name == null || name.length() == 0) name = data == null ? "file" + it.mid : new File(data).getName();
                if (kind == Item.OTHER && (name.startsWith(".") || q.getLong(3) == 0)) continue; // folders, hidden files
                it.name = name;
                it.path = data;
                it.uri = ContentUris.withAppendedId(base, it.mid).toString();
                it.size = q.getLong(3);
                it.mtime = q.getLong(4) * 1000;
                it.kind = kind;
                if (kind == Item.PHOTO) it.orient = q.getInt(5);
                if (it.size == 0 && data != null) it.size = new File(data).length();
                out.add(it);
                if (data != null) paths.add(data);
                paths.add(it.name + "|" + it.size);
            }
        } catch (Exception e) {
            d.append(" error: ").append(e).append("\n");
        } finally {
            if (q != null) q.close();
        }
        d.append(" ").append(base.getPathSegments().get(1)).append(": ").append(out.size() - before).append("\n");
    }

    private static void walk(File dir, int depth, List<Item> out, Set<String> seen, Set<String> have) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_FILES) return;
            String n = f.getName();
            if (f.isDirectory()) {
                String low = n.toLowerCase();
                if (depth < MAX_DEPTH && !low.startsWith(".") && !low.equals("android") && seen.add(canonical(f)))
                    walk(f, depth + 1, out, seen, have);
                continue;
            }
            if (n.startsWith(".") || n.endsWith(".part") || have.contains(f.getPath())) continue;
            int kind = Item.kindOf(n);
            long len = f.length();
            if (len == 0 || (kind == Item.PHOTO && len < MIN_PHOTO_BYTES) || have.contains(n + "|" + len)) continue;
            Item it = new Item();
            it.name = n;
            it.path = f.getPath();
            it.size = len;
            it.mtime = f.lastModified();
            it.kind = kind;
            out.add(it);
        }
    }

    /** Folders to try for saving downloads, best first: the BB10 shared Downloads folder, then the app's own space. */
    private static List<File> saveCandidates() {
        List<File> out = new ArrayList<File>();
        out.add(new File("/accounts/1000/shared/downloads"));
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) {
            out.add(new File(ext, "downloads"));
            out.add(new File(ext, "Download"));
            out.add(new File(ext, "Stash"));
        }
        return out;
    }

    /** Really writes a file, because canWrite() is not trustworthy on BB10. */
    private static boolean writable(File d) {
        if (!d.isDirectory()) return false;
        File t = new File(d, ".stash-write-test");
        try {
            FileOutputStream o = new FileOutputStream(t);
            o.write(1);
            o.close();
            return t.delete();
        } catch (Exception e) {
            t.delete();
            return false;
        }
    }

    /** A folder the app can write downloads into, or null. Only the last choice (our own Stash folder) is ever created. */
    static File saveDir() {
        List<File> c = saveCandidates();
        for (int i = 0; i < c.size(); i++) {
            File d = c.get(i);
            if (i == c.size() - 1 && !d.isDirectory()) d.mkdirs();
            if (writable(d)) return d;
        }
        return null;
    }

    /** Plain-text report of what the phone lets the app see, for the Storage info screen. Blocks. */
    static String report(Context c, List<Item> all) {
        StringBuilder d = new StringBuilder();
        d.append("Phone storage: ").append(Environment.getExternalStorageDirectory()).append(" (")
                .append(Environment.getExternalStorageState()).append(")\n");
        File save = saveDir();
        d.append("Downloads are saved in: ").append(save == null ? "nowhere (no writable folder found)" : save.getPath()).append("\n");
        for (File f : saveCandidates()) d.append("  ").append(f.getPath()).append(": ").append(!f.isDirectory() ? "no such folder" : writable(f) ? "writable" : "read-only").append("\n");
        int[] n = new int[4];
        if (all != null) for (Item it : all) n[it.kind]++;
        d.append("\nListed: ").append(n[Item.PHOTO]).append(" photos, ").append(n[Item.VIDEO]).append(" videos, ")
                .append(n[Item.AUDIO]).append(" music\n");
        int shown = 0;
        if (all != null) {
            for (Item it : all) {
                if (it.uri == null || it.path == null || shown >= 3) continue;
                File f = new File(it.path);
                d.append("e.g. ").append(it.path).append(" - file ").append(f.exists() ? "visible" : "not visible").append("\n");
                shown++;
            }
        }
        d.append("\n").append(diag);
        return d.toString();
    }

    static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getPath();
        }
    }
}
