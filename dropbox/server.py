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
import json
import mimetypes
import os
import re
import socket
import subprocess
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_UPLOAD = 2 * 1024 * 1024 * 1024  # 2 GB
FORM_MAX = 100 * 1024 * 1024  # multipart fallback is parsed in memory
THUMB_SIZE = 160
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tif", ".tiff", ".heic")
TEXT_EXT = (".txt", ".md", ".log", ".csv", ".json", ".xml", ".ini", ".conf")
TEXT_PREVIEW_BYTES = 400
for _ext, _type in ((".flac", "audio/flac"), (".m4a", "audio/mp4"), (".opus", "audio/ogg"), (".mkv", "video/x-matroska")):
    mimetypes.add_type(_type, _ext)

# Only these are ever served inline; anything else (notably .html) is download-only,
# so an uploaded page can't run script on this origin.
INLINE_PREFIXES = ("image/", "video/", "audio/")
INLINE_TYPES = ("application/pdf",)

PAGE = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BB Drop</title>
<style>
:root{--bg:#f5f6f8;--card:#fff;--text:#1c1e21;--sub:#6b7280;--line:#e5e7eb;--accent:#2563eb;--accent2:#1d4ed8;--soft:#eef2ff;--shadow:0 1px 2px rgba(16,24,40,.06),0 1px 3px rgba(16,24,40,.08)}
@media (prefers-color-scheme:dark){:root{--bg:#0e1116;--card:#171b22;--text:#e8eaed;--sub:#8b95a5;--line:#262c36;--accent:#4c8dff;--accent2:#6aa2ff;--soft:#1b2540;--shadow:none}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:#f5f6f8;background:var(--bg);color:#1c1e21;color:var(--text);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
a{color:#2563eb;color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
.wrap{max-width:960px;margin:0 auto;padding:0 16px 48px}
header{display:-webkit-box;display:flex;-webkit-box-align:center;align-items:center;justify-content:space-between;padding:22px 0 14px}
.logo{font-size:22px;font-weight:700;letter-spacing:-.02em}
.logo i{display:inline-block;width:28px;height:28px;margin-right:10px;vertical-align:-6px;border-radius:8px;background:#2563eb;background:linear-gradient(135deg,#4c8dff,#2563eb);position:relative}
.logo i:after{content:"";position:absolute;left:10px;top:7px;border-left:10px solid #fff;border-top:7px solid transparent;border-bottom:7px solid transparent}
.stats{color:#6b7280;color:var(--sub);font-size:13px}
.card{background:#fff;background:var(--card);border:1px solid var(--line);border-radius:14px;box-shadow:var(--shadow)}
#drop{margin:4px 0 18px;padding:22px 16px;text-align:center;border:2px dashed #cbd5e1;border-color:var(--line);border-radius:14px;background:var(--card);transition:all .15s}
#drop.over{border-color:#2563eb;border-color:var(--accent);background:var(--soft)}
#drop b{display:block;font-size:16px;margin-bottom:2px}
#drop span{color:#6b7280;color:var(--sub);font-size:13px}
.btn{display:inline-block;margin-top:12px;padding:9px 18px;border:0;border-radius:10px;background:#2563eb;background:var(--accent);color:#fff;font:600 14px/1 inherit;cursor:pointer}
.btn:hover{background:var(--accent2);text-decoration:none}
#file{display:none}
#bar{height:6px;border-radius:3px;background:var(--line);margin:14px auto 0;max-width:420px;overflow:hidden;display:none}
#fill{height:6px;width:0;background:#2563eb;background:var(--accent);transition:width .2s}
#msg{margin-top:8px;font-size:13px;color:#6b7280;color:var(--sub)}
.bar{display:-webkit-box;display:flex;flex-wrap:wrap;align-items:center;margin-bottom:6px}
.pills{display:-webkit-box;display:flex;flex-wrap:wrap;margin:0 -3px;-webkit-box-flex:1;flex:1}
.pill{margin:3px;padding:6px 13px;border-radius:999px;background:#fff;background:var(--card);border:1px solid var(--line);color:inherit;color:var(--text);font-size:13.5px}
.pill b{font-weight:600;margin-left:4px;color:#6b7280;color:var(--sub)}
.pill:hover{text-decoration:none;border-color:#2563eb;border-color:var(--accent)}
.pill.on{background:#2563eb;background:var(--accent);border-color:#2563eb;border-color:var(--accent);color:#fff}
.pill.on b{color:#dbe6ff}
.tools{display:-webkit-box;display:flex;align-items:center;margin:6px 0 4px;width:100%}
.seg{display:-webkit-box;display:flex;border:1px solid var(--line);border-radius:10px;overflow:hidden;background:var(--card)}
.seg a{padding:6px 13px;font-size:13px;color:inherit;color:var(--text);border-right:1px solid var(--line)}
.seg a:last-child{border-right:0}
.seg a.on{background:#2563eb;background:var(--accent);color:#fff}
.seg a:hover{text-decoration:none}
#q{-webkit-box-flex:1;flex:1;margin-left:10px;padding:8px 12px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:inherit;color:var(--text);font:14px inherit;outline:0}
#q:focus{border-color:#2563eb;border-color:var(--accent)}
h2{margin:22px 2px 8px;font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:#6b7280;color:var(--sub);font-weight:700}
h2 span{font-weight:500;margin-left:4px}
.list{overflow:hidden}
.item{display:-webkit-box;display:flex;align-items:center;padding:11px 14px;border-top:1px solid var(--line)}
.item:first-child{border-top:0}
.item:hover{background:rgba(127,127,127,.06)}
.tile{-webkit-box-flex:0;flex:0 0 auto;width:42px;height:42px;margin-right:14px;border-radius:10px;color:#fff;font-size:11px;font-weight:700;line-height:42px;text-align:center;letter-spacing:.02em;overflow:hidden}
.t-photos{background:#f59e0b}.t-videos{background:#8b5cf6}.t-music{background:#ec4899}.t-docs{background:#3b82f6}.t-other{background:#6b7280}
.meta{-webkit-box-flex:1;flex:1;min-width:0}
.name{display:block;font-weight:600;color:inherit;color:var(--text);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sz{color:#6b7280;color:var(--sub);font-size:12.5px}
.acts{-webkit-box-flex:0;flex:0 0 auto;margin-left:12px;white-space:nowrap}
.acts a{display:inline-block;margin-left:6px;padding:5px 11px;border-radius:8px;background:var(--soft);font-size:12.5px;font-weight:600}
.acts a:hover{text-decoration:none;background:#2563eb;background:var(--accent);color:#fff}
.ck{display:none;width:20px;height:20px;margin:0 12px 0 0;-webkit-box-flex:0;flex:0 0 auto;accent-color:#2563eb}
.selecting .ck{display:block}
.selecting .item,.selecting .ph .in{cursor:pointer}
.selecting .acts{display:none}
.selecting .ph .dl{display:none}
.item.on{background:var(--soft)}
.ph{position:relative}
.ph .ck{position:absolute;top:14px;left:14px;z-index:2;margin:0;width:22px;height:22px}
.ph.on .in{outline:3px solid #2563eb;outline-color:var(--accent)}
.sbtn{margin-left:10px;padding:7px 14px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:inherit;color:var(--text);font:600 13px inherit;cursor:pointer}
.selecting #selbtn{background:#2563eb;background:var(--accent);border-color:#2563eb;border-color:var(--accent);color:#fff}
#selbar{display:none;position:fixed;left:0;right:0;bottom:0;z-index:5;padding:12px 16px;background:var(--card);border-top:1px solid var(--line);box-shadow:0 -4px 16px rgba(0,0,0,.12);-webkit-box-align:center;align-items:center}
.selecting #selbar{display:-webkit-box;display:flex}
#selbar .c{-webkit-box-flex:1;flex:1;font-weight:600}
#selbar button{margin-left:8px;padding:8px 14px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:inherit;color:var(--text);font:600 13px inherit;cursor:pointer}
#selbar button.del{background:#dc2626;border-color:#dc2626;color:#fff}
#selbar button[disabled]{opacity:.45;cursor:default}
.selecting .wrap{padding-bottom:90px}
audio{display:block;width:100%;max-width:420px;height:32px;margin-top:6px}
pre{margin:6px 0 0;padding:8px 10px;background:rgba(127,127,127,.1);border-radius:8px;color:inherit;font-size:12px;white-space:pre-wrap;word-break:break-all}
.grid{display:-webkit-box;display:flex;flex-wrap:wrap;margin:0 -5px}
.ph{width:25%;padding:5px}
.ph .in{display:block;overflow:hidden;border-radius:12px;background:var(--card);border:1px solid var(--line);color:inherit;color:var(--text)}
.ph .in:hover{text-decoration:none;box-shadow:0 4px 14px rgba(0,0,0,.18)}
.ph img{display:block;width:100%;height:150px;object-fit:cover;background:var(--line)}
.ph .cap{padding:7px 10px 8px}
.ph .cap .name{font-size:12.5px;font-weight:600}
.ph .cap .sz{font-size:11.5px}
.ph .dl{display:block;padding:0 10px 9px;font-size:12px;font-weight:600}
.empty{padding:40px 16px;text-align:center;color:#6b7280;color:var(--sub)}
@media (max-width:760px){.ph{width:33.33%}}
@media (max-width:520px){.ph{width:50%}.acts a.v{display:none}.tools{-webkit-box-orient:vertical;flex-wrap:wrap}#q{margin:8px 0 0;width:100%}}
</style></head><body><div class="wrap">
<header><div class="logo"><i></i>BB Drop</div><div class="stats">__STATS__</div></header>
<div id="drop">
<form id="f" method="post" action="/upload__Q__" enctype="multipart/form-data">
<b>Drop files here to send them to the Mac</b><span>or choose them below</span><br>
<label class="btn" for="file">Choose files</label>
<input type="file" name="file" id="file" multiple>
<noscript><br><button class="btn" type="submit">Upload</button></noscript>
</form>
<div id="bar"><div id="fill"></div></div><div id="msg"></div>
</div>
<div class="bar"><div class="pills">__PILLS__</div></div>
<div class="tools"><div class="seg">__SORT__</div><input id="q" type="search" placeholder="Search files"><button type="button" class="sbtn" id="selbtn">Select</button></div>
<div id="selbar"><span class="c" id="cnt">0 selected</span><button type="button" id="all">Select all</button><button type="button" class="del" id="del" disabled>Delete</button><button type="button" id="done">Cancel</button></div>
<div id="results">__ROWS__</div>
</div>
<script>
(function(){
var f=document.getElementById('f'),msg=document.getElementById('msg'),fill=document.getElementById('fill'),
bar=document.getElementById('bar'),pin="__PIN__",inp=document.getElementById('file'),drop=document.getElementById('drop');
function send(files){
if(!files||!files.length||!window.XMLHttpRequest){return}
var i=0;bar.style.display='block';
function next(){
if(i>=files.length){msg.innerHTML='Done';location.reload();return}
var fl=files[i],x=new XMLHttpRequest();
x.open('POST','/raw?name='+encodeURIComponent(fl.name)+pin);
x.upload.onprogress=function(e){if(e.lengthComputable){fill.style.width=Math.round(e.loaded*100/e.total)+'%'}};
x.onload=function(){if(x.status==200){i++;next()}else{msg.innerHTML='Failed: '+x.responseText}};
x.onerror=function(){msg.innerHTML='Network error'};
msg.innerHTML='Uploading '+fl.name+' ('+(i+1)+' of '+files.length+')';
x.send(fl);
}
next();
}
if(window.File&&window.XMLHttpRequest){
inp.onchange=function(){send(inp.files)};
f.onsubmit=function(){send(inp.files);return false};
var over=function(e){e.preventDefault();drop.className='over'};
document.addEventListener('dragover',over);document.addEventListener('dragenter',over);
document.addEventListener('dragleave',function(e){if(!e.relatedTarget){drop.className=''}});
document.addEventListener('drop',function(e){e.preventDefault();drop.className='';if(e.dataTransfer){send(e.dataTransfer.files)}});
}
var selecting=false,cnt=document.getElementById('cnt'),delBtn=document.getElementById('del');
function boxes(){return document.querySelectorAll('.ck')}
function visible(el){while(el&&el.getAttribute){if(el.style&&el.style.display==='none'){return false}el=el.parentNode}return true}
function refresh(){
var n=0,all=boxes();
for(var i=0;i<all.length;i++){var p=all[i].parentNode;
if(all[i].checked){n++}
p.className=p.className.split(' on').join('')+(all[i].checked?' on':'')}
cnt.innerHTML=n+' selected';delBtn.disabled=!n;
}
function setSel(on){selecting=on;document.body.className=on?'selecting':'';
if(!on){var all=boxes();for(var i=0;i<all.length;i++){all[i].checked=false}}refresh()}
document.getElementById('selbtn').onclick=function(){setSel(!selecting)};
document.getElementById('done').onclick=function(){setSel(false)};
document.getElementById('all').onclick=function(){
var all=boxes();for(var i=0;i<all.length;i++){if(visible(all[i].parentNode)){all[i].checked=true}}refresh()};
document.addEventListener('keydown',function(e){if(e.keyCode===27&&selecting){setSel(false)}});
document.addEventListener('click',function(e){
if(!selecting){return}
var el=e.target;
if(el.className==='ck'){refresh();return}
while(el&&el.getAttribute&&el.getAttribute('data-name')===null){el=el.parentNode}
if(!el||!el.getAttribute){return}
e.preventDefault();
var b=el.querySelector('.ck');if(b){b.checked=!b.checked;refresh()}
},true);
delBtn.onclick=function(){
var picked=[],all=boxes();
for(var i=0;i<all.length;i++){if(all[i].checked){picked.push(all[i].parentNode.getAttribute('data-name'))}}
if(!picked.length||!window.confirm('Delete '+picked.length+' file'+(picked.length==1?'':'s')+'? They move to the .trash folder inside the shared folder on the Mac.')){return}
var k=0;delBtn.disabled=true;
function next(){
if(k>=picked.length){location.reload();return}
cnt.innerHTML='Deleting '+(k+1)+' of '+picked.length+'...';
var x=new XMLHttpRequest();x.open('POST','/delete?name='+encodeURIComponent(picked[k])+pin);
x.onload=function(){if(x.status==200||x.status==404){k++;next()}else{cnt.innerHTML='Failed: '+x.responseText}};
x.onerror=function(){cnt.innerHTML='Network error'};
x.send('');
}
next();
};
var q=document.getElementById('q');
q.oninput=function(){
var v=q.value.toLowerCase(),items=document.querySelectorAll('[data-n]');
for(var k=0;k<items.length;k++){items[k].style.display=(items[k].getAttribute('data-n').indexOf(v)<0)?'none':''}
var secs=document.querySelectorAll('.sec');
for(var s=0;s<secs.length;s++){var any=false,it=secs[s].querySelectorAll('[data-n]');
for(var j=0;j<it.length;j++){if(it[j].style.display!=='none'){any=true;break}}
secs[s].style.display=any?'':'none'}
};
})();
</script>
</body></html>"""


DOC_EXT = (".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".rtf", ".epub", ".mobi") + TEXT_EXT
# (key, heading, filter label) in the order they are shown
CATEGORIES = (("photos", "Photos"), ("videos", "Videos"), ("music", "Music"), ("docs", "Documents"), ("other", "Other"))


def category(name):
    lower = name.lower()
    ctype = guess_type(name)
    if lower.endswith(IMAGE_EXT) or ctype.startswith("image/"):
        return "photos"
    if ctype.startswith("video/"):
        return "videos"
    if ctype.startswith("audio/"):
        return "music"
    if lower.endswith(DOC_EXT):
        return "docs"
    return "other"


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

        def send_file(self, full, ctype, extra=()):
            """Serve a file, honouring a single `Range: bytes=a-b` so players can seek."""
            size = os.path.getsize(full)
            start, end, code = 0, size - 1, 200
            m = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", "").strip())
            if m and (m.group(1) or m.group(2)) and size:
                if m.group(1):
                    start = int(m.group(1))
                    end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
                else:  # suffix range: the last N bytes
                    start = max(size - int(m.group(2)), 0)
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", "bytes */%d" % size)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                code = 206
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1 if size else 0))
            if code == 206:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            for k, v in extra:
                self.send_header(k, v)
            self.end_headers()
            if self.command == "HEAD" or not size:
                return
            try:
                with open(full, "rb") as fh:
                    fh.seek(start)
                    left = end - start + 1
                    while left > 0:
                        chunk = fh.read(min(65536, left))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass  # the player closed the connection (seek or stop)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            url = urllib.parse.urlparse(self.path)
            query = urllib.parse.parse_qs(url.query)
            if not self.authed(query):
                return self.send_text(403, "PIN required: add ?pin=<pin> to the URL")
            q = "?pin=" + urllib.parse.quote(pin) if pin else ""
            if url.path == "/api/ping":
                return self.send_text(200, '{"ok":true}', "application/json")
            if url.path == "/api/list":
                items = []
                for name in os.listdir(root):
                    full = os.path.join(root, name)
                    if name.startswith(".") or not os.path.isfile(full):
                        continue
                    st = os.stat(full)
                    ctype = guess_type(name)
                    items.append({"name": name, "size": st.st_size, "mtime": int(st.st_mtime),
                                  "kind": ctype.split("/")[0] if ctype.startswith(INLINE_PREFIXES) else "file"})
                items.sort(key=lambda i: -i["mtime"])
                return self.send_text(200, json.dumps(items), "application/json")
            if url.path == "/":
                want = query.get("type", ["all"])[0]
                order = query.get("sort", ["date"])[0]
                entries = []
                for name in os.listdir(root):
                    full = os.path.join(root, name)
                    if name.startswith(".") or not os.path.isfile(full):
                        continue
                    st = os.stat(full)
                    entries.append((name, full, st.st_size, st.st_mtime, category(name)))
                all_entries = list(entries)
                counts = {}
                for e in entries:
                    counts[e[4]] = counts.get(e[4], 0) + 1
                if want != "all":
                    entries = [e for e in entries if e[4] == want]
                if order == "name":
                    entries.sort(key=lambda e: e[0].lower())
                elif order == "size":
                    entries.sort(key=lambda e: -e[2])
                else:
                    order = "date"
                    entries.sort(key=lambda e: -e[3])

                pin_q = "&pin=" + urllib.parse.quote(pin) if pin else ""

                def href_for(t, o):
                    return "/?type=%s&sort=%s%s" % (t, o, pin_q)

                total = sum(counts.values())
                pills = '<a class="pill%s" href="%s">All<b>%d</b></a>' % (" on" if want == "all" else "", href_for("all", order), total)
                for key, title in CATEGORIES:
                    if counts.get(key):
                        pills += '<a class="pill%s" href="%s">%s<b>%d</b></a>' % (
                            " on" if want == key else "", href_for(key, order), title, counts[key])
                sort = "".join(
                    '<a class="%s" href="%s">%s</a>' % ("on" if order == o else "", href_for(want, o), label)
                    for o, label in (("date", "Newest"), ("name", "Name"), ("size", "Largest")))
                stats = "%d file%s &middot; %s" % (total, "" if total == 1 else "s", human(sum(e[2] for e in all_entries)))

                def row(e):
                    name, full, size, mtime, cat = e
                    enc = urllib.parse.quote(name)
                    href = "/files/" + enc + q
                    view = "/view/" + enc + q
                    esc = html.escape(name)
                    when = time.strftime("%d %b %Y, %H:%M", time.localtime(mtime))
                    meta = "%s &middot; %s" % (human(size), when)
                    viewable = inline_type(name) is not None
                    ext = (os.path.splitext(name)[1][1:] or "file").upper()[:4]
                    key = html.escape(name.lower(), quote=True)
                    raw = html.escape(name, quote=True)
                    if cat == "photos" and name.lower().endswith(IMAGE_EXT):
                        return ('<div class="ph" data-n="%s" data-name="%s"><input type="checkbox" class="ck"><div class="card"><a class="in" href="%s"><img loading="lazy" src="/thumb/%s%s" alt="">'
                                '<div class="cap"><span class="name">%s</span><span class="sz">%s</span></div></a>'
                                '<a class="dl" href="%s">Download</a></div></div>') % (key, raw, view, enc, q, esc, meta, href)
                    extra = ""
                    if cat == "music" and viewable:
                        extra = '<audio controls preload="none" src="%s"></audio>' % view
                    elif name.lower().endswith(TEXT_EXT):
                        extra = "<pre>%s</pre>" % html.escape(text_preview(full))
                    acts = '<a href="%s">Download</a>' % href
                    if viewable and not extra.startswith("<audio"):
                        acts = '<a class="v" href="%s">%s</a>' % (view, "Play" if cat == "videos" else "Open") + acts
                    return ('<div class="item" data-n="%s" data-name="%s"><input type="checkbox" class="ck"><div class="tile t-%s">%s</div><div class="meta">'
                            '<a class="name" href="%s">%s</a><span class="sz">%s</span>%s</div><div class="acts">%s</div></div>'
                            ) % (key, raw, cat, html.escape(ext), view if viewable else href, esc, meta, extra, acts)

                parts = []
                for key, title in CATEGORIES:
                    group = [e for e in entries if e[4] == key]
                    if not group:
                        continue
                    body = "".join(row(e) for e in group)
                    body = '<div class="grid">%s</div>' % body if key == "photos" else '<div class="card list">%s</div>' % body
                    head = "<h2>%s<span>%d</span></h2>" % (title, len(group)) if want == "all" else ""
                    parts.append('<div class="sec">%s%s</div>' % (head, body))
                if not parts:
                    parts.append('<div class="card empty">No files yet. Drop some above, or put them in the shared folder on the Mac.</div>')
                page = (PAGE.replace("__Q__", q).replace("__PIN__", ("&pin=" + urllib.parse.quote(pin)) if pin else "")
                        .replace("__STATS__", stats).replace("__PILLS__", pills).replace("__SORT__", sort)
                        .replace("__ROWS__", "".join(parts)))
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
                return self.send_file(full, ctype, (("X-Content-Type-Options", "nosniff"),))
            if url.path.startswith("/files/"):
                name = safe_name(urllib.parse.unquote(url.path[len("/files/"):]))
                full = os.path.join(root, name)
                if not os.path.isfile(full):
                    return self.send_text(404, "Not found")
                return self.send_file(full, "application/octet-stream", (
                    ("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(name)),))
            self.send_text(404, "Not found")

        def do_POST(self):
            url = urllib.parse.urlparse(self.path)
            query = urllib.parse.parse_qs(url.query)
            if not self.authed(query):
                return self.send_text(403, "PIN required")
            length = int(self.headers.get("Content-Length", "0"))
            if url.path == "/delete":
                # "delete" moves the file into shared/.trash, so a mistake can be undone on the Mac
                name = safe_name(query.get("name", [""])[0])
                full = os.path.join(root, name)
                if name.startswith(".") or not os.path.isfile(full):
                    return self.send_text(404, "Not found")
                trash = os.path.join(root, ".trash")
                os.makedirs(trash, exist_ok=True)
                os.rename(full, unique_path(trash, name))
                print("trashed %s" % name)
                return self.send_text(200, "ok")
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
