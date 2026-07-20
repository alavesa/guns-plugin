#!/usr/bin/env python3
"""Ammo-bar bullet glyphs for the Guns plugin.

Two tiny bitmap glyphs in the guns:ammo font, drawn into the ammo boss bar's
title next to the round count:
  U+E000  bullet_full   - a loaded round (brass case + copper tip)
  U+E001  bullet_empty  - a spent casing (hollow outline)

Pure stdlib, no PIL. Writes:
  resource-pack/assets/guns/textures/font/bullet_full.png   (8x8)
  resource-pack/assets/guns/textures/font/bullet_empty.png  (8x8)
  resource-pack/assets/guns/font/ammo.json

Run from the repo root:  python3 tools/gen_ammo.py
"""
import json, os, struct, zlib

BRASS   = (214, 176, 78, 255)   # casing body
BRASS_D = (150, 118, 40, 255)   # casing shadow
COPPER  = (198, 108, 58, 255)   # bullet tip
COPPER_D = (140, 70, 34, 255)   # tip shadow
OUTLINE = (90, 78, 40, 255)     # spent-casing outline
SPENT   = (70, 62, 40, 180)     # spent-casing faint fill

W, H = 8, 8


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def fill(self, x, y, w, h, c):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, c)

    def png(self, path):
        rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in self.px)
        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
        data = (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        print(f"{path} ({self.w}x{self.h})")


# A small upright cartridge in the middle 4 columns (x = 2..5).
def full():
    c = Canvas(W, H)
    # copper tip (rows 0-2), tapering
    c.fill(3, 0, 2, 1, COPPER)
    c.fill(2, 1, 4, 2, COPPER)
    c.set(2, 1, COPPER_D); c.set(2, 2, COPPER_D)   # left shadow
    # brass casing (rows 3-7)
    c.fill(2, 3, 4, 5, BRASS)
    for y in range(3, 8):
        c.set(2, y, BRASS_D)                        # left edge shadow
    c.fill(2, 7, 4, 1, BRASS_D)                     # rim
    return c


def empty():
    c = Canvas(W, H)
    # hollow casing outline only (spent)
    c.fill(2, 1, 4, 1, OUTLINE)   # top
    c.fill(2, 7, 4, 1, OUTLINE)   # bottom rim
    for y in range(1, 8):
        c.set(2, y, OUTLINE)
        c.set(5, y, OUTLINE)
    c.fill(3, 2, 2, 5, SPENT)     # faint interior
    return c


base = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "guns")
tex = os.path.join(base, "textures", "font")
full().png(os.path.join(tex, "bullet_full.png"))
empty().png(os.path.join(tex, "bullet_empty.png"))

font = {
    "providers": [
        {"type": "bitmap", "file": "guns:font/bullet_full.png",
         "ascent": 7, "height": 8, "chars": [chr(0xE000)]},
        {"type": "bitmap", "file": "guns:font/bullet_empty.png",
         "ascent": 7, "height": 8, "chars": [chr(0xE001)]},
    ]
}
os.makedirs(os.path.join(base, "font"), exist_ok=True)
with open(os.path.join(base, "font", "ammo.json"), "w") as f:
    json.dump(font, f, indent=2, ensure_ascii=False)
print(os.path.join(base, "font", "ammo.json"))
