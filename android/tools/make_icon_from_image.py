#!/usr/bin/env python3
"""Builds the launcher icons from a supplied picture, using only the standard library.

The picture is a coloured rounded square on a white background (tools/icon-source.png). This crops
the square, turns the white background outside its rounded corners transparent (keeping any white
inside the artwork), and scales it to each Android density.

Run from the android folder:  python3 -I tools/make_icon_from_image.py [source.png]
"""
import os
import struct
import sys
import zlib
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
SIZES = (("drawable-mdpi", 48), ("drawable-hdpi", 72), ("drawable-xhdpi", 96),
         ("drawable-xxhdpi", 144), ("drawable", 96))


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, w = 8, b"", None
    while pos < len(data):
        n, t = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if t == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and ctype in (2, 6) and interlace == 0, "need 8-bit RGB/RGBA, not interlaced"
        elif t == b"IDAT":
            idat += body
        pos += 12 + n
    bpp = 3 if ctype == 2 else 4
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        rows.append(line)
        prev = line
    # normalise to a list of rows of (r, g, b) tuples
    return w, h, [[(r[x * bpp], r[x * bpp + 1], r[x * bpp + 2]) for x in range(w)] for r in rows]


def write_png(path, size, px):
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in px)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def cut_out(w, h, img):
    """Crop to the artwork and return RGBA rows with the outer white made transparent."""
    def nonwhite(p):
        return min(p) < 235
    xs = [x for y in range(h) for x in range(w) if nonwhite(img[y][x])]
    ys = [y for y in range(h) for x in range(w) if nonwhite(img[y][x])]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    side = max(x1 - x0, y1 - y0) + 1
    crop = [[img[y][x] for x in range(x0, x0 + side)] for y in range(y0, y0 + side)]

    # solid artwork colour: a pixel well inside the left edge
    base = crop[side // 2][max(3, side // 14)]
    alpha = [[255] * side for _ in range(side)]
    # flood the white background in from the four corners (white inside the artwork is untouched)
    seen = [[False] * side for _ in range(side)]
    q = deque()
    for cx, cy in ((0, 0), (side - 1, 0), (0, side - 1), (side - 1, side - 1)):
        if min(crop[cy][cx]) >= 240:
            q.append((cx, cy))
            seen[cy][cx] = True
    while q:
        x, y = q.popleft()
        alpha[y][x] = 0
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < side and 0 <= ny < side and not seen[ny][nx] and min(crop[ny][nx]) >= 240:
                seen[ny][nx] = True
                q.append((nx, ny))
    # soft edge: pixels touching the cleared area are a green/white blend; recover how much is green
    out = [[None] * side for _ in range(side)]
    for y in range(side):
        for x in range(side):
            r, g, b = crop[y][x]
            a = alpha[y][x]
            if a:
                near = any(0 <= nx < side and 0 <= ny < side and alpha[ny][nx] == 0
                           for nx in range(x - 2, x + 3) for ny in range(y - 2, y + 3))
                border = x < 3 or y < 3 or x >= side - 3 or y >= side - 3  # straight edges touch the crop edge
                if (near or border) and r > base[0] + 2:
                    a = max(0, min(255, round(255 * (255 - r) / max(1, 255 - base[0]))))
                    r, g, b = base  # drop the white fringe from the colour
            out[y][x] = (r, g, b, a)
    return side, out


def scale(side, rgba, n):
    """Area-average down to n x n, working on premultiplied colour so edges stay clean."""
    pre = [[(r * a / 255.0, g * a / 255.0, b * a / 255.0, float(a)) for (r, g, b, a) in row] for row in rgba]

    def spans(count):
        k = side / count
        res = []
        for i in range(count):
            lo, hi = i * k, (i + 1) * k
            items = []
            j = int(lo)
            while j < hi and j < side:
                items.append((j, min(hi, j + 1) - max(lo, j)))
                j += 1
            res.append((items, hi - lo))
        return res

    sp = spans(n)
    # horizontal pass
    mid = []
    for row in pre:
        newrow = []
        for items, total in sp:
            acc = [0.0, 0.0, 0.0, 0.0]
            for j, wgt in items:
                p = row[j]
                for c in range(4):
                    acc[c] += p[c] * wgt
            newrow.append(tuple(v / total for v in acc))
        mid.append(newrow)
    # vertical pass
    out = []
    for items, total in sp:
        row = []
        for x in range(n):
            acc = [0.0, 0.0, 0.0, 0.0]
            for j, wgt in items:
                p = mid[j][x]
                for c in range(4):
                    acc[c] += p[c] * wgt
            a = acc[3] / total
            if a > 0.5:
                row.append((min(255, round(acc[0] / total * 255 / a)), min(255, round(acc[1] / total * 255 / a)),
                            min(255, round(acc[2] / total * 255 / a)), round(a)))
            else:
                row.append((0, 0, 0, 0))
        out.append(row)
    return out


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "icon-source.png")
    w, h, img = read_png(src)
    side, rgba = cut_out(w, h, img)
    print("source %dx%d, artwork %dx%d" % (w, h, side, side))
    res = os.path.join(HERE, "..", "res")
    for folder, n in SIZES:
        os.makedirs(os.path.join(res, folder), exist_ok=True)
        write_png(os.path.join(res, folder, "ic_launcher.png"), n, scale(side, rgba, n))
        print(folder, n)
    if os.environ.get("PREVIEW"):
        write_png(os.environ["PREVIEW"], 256, scale(side, rgba, 256))
