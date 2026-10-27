#!/usr/bin/env python3
"""Draws a plain placeholder launcher icon (green rounded square, white speech bubble).

The build script runs this automatically when res/drawable*/ic_launcher.png are missing, so a fresh
clone builds without any third-party artwork. To use your own icon, put a PNG at
tools/icon-source.png and run  python3 -I tools/make_icon_from_image.py  (standard library only).
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SIZES = (("drawable-mdpi", 48), ("drawable-hdpi", 72), ("drawable-xhdpi", 96),
         ("drawable-xxhdpi", 144), ("drawable", 96))
GREEN = (37, 168, 90, 255)
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)


def inside_round_rect(x, y, n, r):
    cx = min(max(x, r), n - 1 - r)
    cy = min(max(y, r), n - 1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def pixel(x, y, n):
    if not inside_round_rect(x, y, n, n * 0.22):
        return CLEAR
    u, v = x / n, y / n
    bubble = (u - 0.5) ** 2 + (v - 0.46) ** 2 <= 0.26 ** 2
    tail = 0.28 <= u <= 0.42 and 0.62 <= v <= 0.80 and (v - 0.62) >= (0.42 - u) * 1.2
    return WHITE if bubble or tail else GREEN


def write_png(path, n):
    rows = b"".join(b"\x00" + b"".join(bytes(pixel(x, y, n)) for x in range(n)) for y in range(n))

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    res = os.path.join(HERE, "..", "res")
    for folder, n in SIZES:
        write_png(os.path.join(res, folder, "ic_launcher.png"), n)
    print("wrote placeholder icons")
