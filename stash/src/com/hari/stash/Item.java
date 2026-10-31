package com.hari.stash;

/** One file, either on the phone (path set) or in the drop-box (cloud). */
final class Item {
    static final int ALL = -1, PHOTO = 0, VIDEO = 1, AUDIO = 2, OTHER = 3;

    String name;
    String path;      // local files: the file path (may not be readable on BB10; see uri)
    String uri;       // local files from the media database: content:// URI, the way BB10 lets us open them
    long mid;         // media database id (for video thumbnails)
    int orient;       // degrees, from the media database (photos)
    boolean cloud;
    long size;
    long mtime;       // seconds since 1970 for cloud, milliseconds for local; compare via time()
    int kind;

    static int kindOf(String name) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        String e = dot < 0 ? "" : n.substring(dot + 1);
        if (e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("gif") || e.equals("bmp") || e.equals("webp")) return PHOTO;
        if (e.equals("mp4") || e.equals("m4v") || e.equals("3gp") || e.equals("3gpp") || e.equals("mov") || e.equals("webm")
                || e.equals("mkv") || e.equals("avi")) return VIDEO;
        if (e.equals("mp3") || e.equals("flac") || e.equals("m4a") || e.equals("aac") || e.equals("wav") || e.equals("ogg")
                || e.equals("oga") || e.equals("wma") || e.equals("amr") || e.equals("mid") || e.equals("opus")) return AUDIO;
        return OTHER;
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toUpperCase();
    }

    /** Where a player reads it from: a file path, or an http URL on the drop-box. */
    String src() {
        return cloud ? Cfg.file("view", name) : (uri != null ? uri : path);
    }

    /** Key that is stable for the same file (caches, "is this the track that is playing"). */
    String key() {
        return cloud ? "c:" + name : "l:" + (uri != null ? uri : path);
    }

    /** Reads a local file whichever way it can be opened. */
    java.io.InputStream open() throws java.io.IOException {
        if (uri != null) {
            java.io.InputStream in = Cfg.app.getContentResolver().openInputStream(android.net.Uri.parse(uri));
            if (in != null) return in;
        }
        return new java.io.FileInputStream(path);
    }

    long millis() {
        return cloud ? mtime * 1000 : mtime;
    }

    String title() {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
