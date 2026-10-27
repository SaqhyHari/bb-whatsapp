#!/usr/bin/env python3
"""BB Drop: a tiny Wi-Fi file drop-box for the BlackBerry Q20 (BB10).

Run:  python3 server.py [--port 8000] [--dir ./shared] [--pin 1234]
Then on the phone open  http://<mac-ip>:8000

Only the Python standard library is used. The page is plain HTML with a
little ES5 JavaScript so the old BB10 browser can handle it.
"""
import argparse
import email.parser
import hashlib
import html
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_UPLOAD = 2 * 1024 * 1024 * 1024  # 2 GB
FORM_MAX = 100 * 1024 * 1024  # multipart fallback is parsed in memory
THUMB_SIZE = 160
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tif", ".tiff", ".heic")
TEXT_EXT = (".txt", ".md", ".log", ".csv", ".json", ".xml", ".ini", ".conf")
TEXT_PREVIEW_BYTES = 400
# Only these are ever served inline; anything else (notably .html) is download-only,
# so an uploaded page can't run script on this origin.
INLINE_PREFIXES = ("image/", "video/", "audio/")
INLINE_TYPES = ("application/pdf",)

PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BB Drop</title>
<style>
body{{font-family:sans-serif;margin:0;background:#111;color:#eee}}
h1{{font-size:22px;margin:0;padding:12px;background:#0073bc}}
.box{{padding:12px;border-bottom:1px solid #333}}
a{{color:#6cf;text-decoration:none;word-break:break-all}}
.row{{padding:10px 12px;border-bottom:1px solid #222}}
.sz{{color:#999;font-size:13px}}
.th{{float:left;width:80px;height:80px;object-fit:cover;margin-right:10px;background:#222}}
.row:after{{content:"";display:block;clear:both}}
pre{{margin:6px 0 0;padding:6px;background:#1b1b1b;color:#ccc;font-size:12px;white-space:pre-wrap;word-break:break-all}}
input,button{{font-size:18px;margin:6px 0}}
button{{padding:8px 14px}}
#bar{{height:10px;background:#333;margin-top:8px}}
#fill{{height:10px;width:0;background:#6c6}}
</style></head><body>
<h1>BB Drop</h1>
<div class="box">
<form id="f" method="post" action="/upload{q}" enctype="multipart/form-data">
<input type="file" name="file" id="file" multiple><br>
<button type="submit">Upload to Mac</button>
</form>
<div id="msg" class="sz"></div><div id="bar"><div id="fill"></div></div>
</div>
{rows}
<script>
(function(){{
var f=document.getElementById('f'),msg=document.getElementById('msg'),
fill=document.getElementById('fill'),pin="{pin}";
if(!window.XMLHttpRequest||!window.File){{return}}
f.onsubmit=function(){{
var files=document.getElementById('file').files;
if(!files||!files.length){{msg.innerHTML='Choose a file first';return false}}
var i=0;
function next(){{
if(i>=files.length){{msg.innerHTML='Done';location.reload();return}}
var fl=files[i],x=new XMLHttpRequest();
x.open('POST','/raw?name='+encodeURIComponent(fl.name)+pin);
x.upload.onprogress=function(e){{if(e.lengthComputable){{
fill.style.width=Math.round(e.loaded*100/e.total)+'%'}}}};
x.onload=function(){{if(x.status==200){{i++;next()}}else{{msg.innerHTML='Failed: '+x.responseText}}}};
x.onerror=function(){{msg.innerHTML='Network error'}};
msg.innerHTML='Uploading '+fl.name+' ('+(i+1)+'/'+files.length+')';
x.send(fl);
}}
next();return false;
}};
}})();
</script>
</body></html>"""


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%d %s" % (n, unit)) if unit == "B" else ("%.1f %s" % (n, unit))
        n /= 1024.0


def safe_name(name):
    name = os.path.basename(name.replace("\\", "/"))
    name = re.sub(r"[\x00-\x1f]", "", name).strip().lstrip(".")
    return name or "upload"


def guess_type(name):
    return mimetypes.guess_type(name)[0] or "application/octet-stream"


def inline_type(name):
    """Content type to serve inline, or None when the file must be downloaded."""
    if name.lower().endswith(TEXT_EXT):
        return "text/plain; charset=utf-8"
    ctype = guess_type(name)
    if ctype.startswith(INLINE_PREFIXES) or ctype in INLINE_TYPES:
        return ctype
    return None


def make_thumb(root, name):
    """Small JPEG for an image, cached in root/.thumbs. Needs macOS `sips`; None if unavailable."""
    src = os.path.join(root, name)
    key = hashlib.sha1(("%s:%s" % (name, os.path.getmtime(src))).encode()).hexdigest()
    cache = os.path.join(root, ".thumbs")
    dest = os.path.join(cache, key + ".jpg")
    if os.path.isfile(dest):
        return dest
    os.makedirs(cache, exist_ok=True)
    try:
        subprocess.run(
            ["sips", "-s", "format", "jpeg", "-Z", str(THUMB_SIZE), src, "--out", dest],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=True,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return dest if os.path.isfile(dest) else None


def text_preview(path):
    with open(path, "rb") as fh:
        data = fh.read(TEXT_PREVIEW_BYTES + 1)
    text = data[:TEXT_PREVIEW_BYTES].decode("utf-8", "replace")
    return text + ("\n..." if len(data) > TEXT_PREVIEW_BYTES else "")


def unique_path(root, name):
    path = os.path.join(root, name)
    base, ext = os.path.splitext(name)
    n = 1
    while os.path.exists(path):
        path = os.path.join(root, "%s (%d)%s" % (base, n, ext))
        n += 1
    return path


def make_handler(root, pin):
    class Handler(BaseHTTPRequestHandler):
        server_version = "BBDrop/1.0"

        def log_message(self, fmt, *args):
            print("%s %s" % (self.address_string(), fmt % args))

        def authed(self, query):
            return not pin or query.get("pin", [""])[0] == pin

        def send_text(self, code, text, ctype="text/plain; charset=utf-8"):
            body = text.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            url = urllib.parse.urlparse(self.path)
            query = urllib.parse.parse_qs(url.query)
            if not self.authed(query):
                return self.send_text(403, "PIN required: add ?pin=<pin> to the URL")
            q = "?pin=" + urllib.parse.quote(pin) if pin else ""
            if url.path == "/":
                rows = []
                for name in sorted(os.listdir(root), key=str.lower):
                    full = os.path.join(root, name)
                    if name.startswith(".") or not os.path.isfile(full):
                        continue
                    enc = urllib.parse.quote(name)
                    href = "/files/" + enc + q
                    view = "/view/" + enc + q
                    lower = name.lower()
                    thumb = ""
                    if lower.endswith(IMAGE_EXT):
                        thumb = '<a href="%s"><img class="th" src="/thumb/%s%s" alt=""></a>' % (view, enc, q)
                    links = '<a href="%s">%s</a>' % (href, html.escape(name))
                    if inline_type(name):
                        links += ' &nbsp; <a href="%s">[view]</a>' % view
                    extra = ""
                    if lower.endswith(TEXT_EXT):
                        extra = "<pre>%s</pre>" % html.escape(text_preview(full))
                    rows.append(
                        '<div class="row">%s%s<div class="sz">%s</div>%s</div>'
                        % (thumb, links, human(os.path.getsize(full)), extra)
                    )
                if not rows:
                    rows.append('<div class="row sz">No files yet. Put files in the shared folder on the Mac.</div>')
                page = PAGE.format(
                    q=q, rows="\n".join(rows),
                    pin=("&pin=" + urllib.parse.quote(pin)) if pin else "",
                )
                return self.send_text(200, page, "text/html; charset=utf-8")
            if url.path.startswith(("/thumb/", "/view/")):
                kind = url.path.split("/")[1]
                name = safe_name(urllib.parse.unquote(url.path[len(kind) + 2:]))
                full = os.path.join(root, name)
                if not os.path.isfile(full):
                    return self.send_text(404, "Not found")
                ctype = inline_type(name)
                if kind == "thumb":
                    full = (make_thumb(root, name) if name.lower().endswith(IMAGE_EXT) else None) or full
                    ctype = "image/jpeg" if full.endswith(".jpg") and ".thumbs" in full else ctype
                if not ctype:
                    return self.send_text(415, "No inline preview for this file type")
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(os.path.getsize(full)))
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                with open(full, "rb") as fh:
                    shutil.copyfileobj(fh, self.wfile)
                return
            if url.path.startswith("/files/"):
                name = safe_name(urllib.parse.unquote(url.path[len("/files/"):]))
                full = os.path.join(root, name)
                if not os.path.isfile(full):
                    return self.send_text(404, "Not found")
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(os.path.getsize(full)))
                self.send_header(
                    "Content-Disposition",
                    "attachment; filename*=UTF-8''" + urllib.parse.quote(name),
                )
                self.end_headers()
                with open(full, "rb") as fh:
                    shutil.copyfileobj(fh, self.wfile)
                return
            self.send_text(404, "Not found")

        def do_POST(self):
            url = urllib.parse.urlparse(self.path)
            query = urllib.parse.parse_qs(url.query)
            if not self.authed(query):
                return self.send_text(403, "PIN required")
            length = int(self.headers.get("Content-Length", "0"))
            if url.path == "/raw":
                if length <= 0 or length > MAX_UPLOAD:
                    return self.send_text(413, "Bad or too-large upload")
                name = safe_name(query.get("name", ["upload"])[0])
                dest = unique_path(root, name)
                with open(dest, "wb") as out:
                    left = length
                    while left:
                        chunk = self.rfile.read(min(65536, left))
                        if not chunk:
                            break
                        out.write(chunk)
                        left -= len(chunk)
                if left:
                    os.remove(dest)
                    return self.send_text(400, "Upload cut short")
                print("saved %s (%s)" % (dest, human(length)))
                return self.send_text(200, "ok")
            if url.path == "/upload":
                if length <= 0 or length > FORM_MAX:
                    return self.send_text(413, "Too large for the plain form; use a browser with JavaScript")
                body = self.rfile.read(length)
                head = ("Content-Type: %s\r\n\r\n" % self.headers["Content-Type"]).encode()
                msg = email.parser.BytesParser().parsebytes(head + body)
                saved = 0
                for part in msg.get_payload() if msg.is_multipart() else []:
                    fname = part.get_filename()
                    if fname:
                        data = part.get_payload(decode=True) or b""
                        with open(unique_path(root, safe_name(fname)), "wb") as out:
                            out.write(data)
                        saved += 1
                self.send_response(303)
                self.send_header("Location", "/" + ("?pin=" + urllib.parse.quote(pin) if pin else ""))
                self.end_headers()
                return
            self.send_text(404, "Not found")

    return Handler


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.0.1", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "shared"))
    ap.add_argument("--pin", default="", help="optional PIN required on every request")
    args = ap.parse_args()
    root = os.path.abspath(args.dir)
    os.makedirs(root, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(root, args.pin))
    suffix = "/?pin=" + args.pin if args.pin else ""
    print("Sharing %s" % root)
    print("On the Q20 open:  http://%s:%d%s" % (lan_ip(), args.port, suffix))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
