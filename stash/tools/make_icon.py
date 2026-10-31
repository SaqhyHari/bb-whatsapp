#!/usr/bin/env python3
"""Draws Stash's launcher icon (flat slate rounded square with a folder). Standard library only."""
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SIZES = (("drawable-mdpi", 48), ("drawable-hdpi", 72), ("drawable-xhdpi", 96),
         ("drawable-xxhdpi", 144), ("drawable", 96))
CLEAR = (0, 0, 0, 0)
STOPS = ((0.00, (0, 200, 190)), (0.35, (30, 136, 229)), (0.65, (124, 77, 255)), (1.00, (236, 64, 160)))
SS = 3  # samples per axis per pixel, for smooth edges


def inside_round_rect(x, y, n, r):
    cx = min(max(x, r), n - 1 - r)
    cy = min(max(y, r), n - 1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def wave(u, v):
    """Colour wave: diagonal gradient bent by a sine, so the bands flow."""
    t = u * 0.55 + v * 0.45 + 0.09 * math.sin(2 * math.pi * (v * 1.3 + u * 0.4))
    t = min(max(t, 0.0), 1.0)
    for (a, ca), (b, cb) in zip(STOPS, STOPS[1:]):
        if t <= b:
            f = (t - a) / (b - a)
            return tuple(ca[k] + (cb[k] - ca[k]) * f for k in range(3))
    return STOPS[-1][1]


def in_box(u, v, x0, y0, x1, y1, r):
    cx = min(max(u, x0 + r), x1 - r)
    cy = min(max(v, y0 + r), y1 - r)
    return x0 <= u <= x1 and y0 <= v <= y1 and (u - cx) ** 2 + (v - cy) ** 2 <= r * r


def sample(u, v):
    """Colour at a point in 0..1 space, or None outside the icon."""
    if not in_box(u, v, 0, 0, 1, 1, 0.22):
        return None
    col = (38, 50, 59)  # flat slate, matches the app's tiles
    # folder: back tab, back panel, then a brighter front panel
    if in_box(u, v, 0.16, 0.24, 0.46, 0.40, 0.04):
        return (144, 164, 174)
    if in_box(u, v, 0.16, 0.32, 0.84, 0.76, 0.06):
        if in_box(u, v, 0.16, 0.45, 0.84, 0.76, 0.06):
            return (236, 239, 241)
        return (144, 164, 174)
    return col


def pixel(x, y, n):
    acc = [0.0, 0.0, 0.0]
    cover = 0
    for i in range(SS):
        for j in range(SS):
            c = sample((x + (i + 0.5) / SS) / n, (y + (j + 0.5) / SS) / n)
            if c is not None:
                cover += 1
                for k in range(3):
                    acc[k] += c[k]
    if cover == 0:
        return CLEAR
    return tuple(int(acc[k] / cover) for k in range(3)) + (int(255 * cover / (SS * SS)),)


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
    for folder, n in SIZES:
        write_png(os.path.join(HERE, "..", "res", folder, "ic_launcher.png"), n)
    print("wrote icons")
