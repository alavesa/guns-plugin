#!/usr/bin/env python3
"""Aim reticle glyphs for the Guns plugin (guns:reticle font).

A bracket reticle painted around the crosshair while a gun is held:
  U+E040  bracket_left   "["  (opening faces right, toward the cursor)
  U+E041  bracket_right  "]"  (opening faces left, toward the cursor)
  U+E050  wide spacer    (hip-fire gap - brackets far out)
  U+E051  narrow spacer  (aiming gap - brackets tight to the cursor)

The plugin sends "<[><gap><]>" on the action bar in this font. The action bar
centers the whole string, so the gap's midpoint lands on the crosshair and the
brackets sit symmetric around it. The bracket glyphs are drawn at the TOP of a
tall transparent canvas and given a big ascent (LIFT), which raises them off the
action-bar line up to cursor level.

  LIFT   - how far up the brackets are raised. Bump this if the reticle sits too
           low/high on your screen (a one-value tune; needs an in-game look).
  WIDE   - hip-fire gap in px.   NARROW - aimed gap in px.

Pure stdlib, no PIL. Writes:
  resource-pack/assets/guns/textures/font/bracket_left.png
  resource-pack/assets/guns/textures/font/bracket_right.png
  resource-pack/assets/guns/font/reticle.json

Run from the repo root:  python3 tools/gen_reticle.py
"""
import json, os, struct, zlib

LIFT = 170         # ascent: how high above the action-bar line the brackets sit
CANVAS_H = 176     # tall transparent canvas so the brackets can be lifted
WIDE = 26          # hip-fire gap (px)
NARROW = 8         # aimed gap (px)

W = 12             # bracket cell width
WHITE = (236, 236, 236, 255)
SHADOW = (0, 0, 0, 160)


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

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


def bracket(face_right):
    """A 12x12 bracket drawn in the TOP of a tall transparent canvas.
    face_right=True -> "[" (vertical bar on the left)."""
    c = Canvas(W, CANVAS_H)
    top, bot = 0, 11              # bracket occupies rows 0..11
    if face_right:
        vx = (2, 3)              # vertical bar near the left edge
        arm = range(2, 9)        # serif reaches toward the cursor (right)
    else:
        vx = (8, 9)              # vertical bar near the right edge
        arm = range(3, 10)
    # 1px drop shadow first, then the white bracket over it
    for dx, dy, col in ((1, 1, SHADOW), (0, 0, WHITE)):
        for y in range(top, bot + 1):
            for x in vx:
                c.set(x + dx, y + dy, col)
        for x in arm:
            c.set(x + dx, top + dy, col)
            c.set(x + dx, bot + dy, col)
    return c


root = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "guns")
bracket(True).png(os.path.join(root, "textures", "font", "bracket_left.png"))
bracket(False).png(os.path.join(root, "textures", "font", "bracket_right.png"))

BRACKET_LEFT = chr(0xE040)
BRACKET_RIGHT = chr(0xE041)
GAP_WIDE = chr(0xE050)
GAP_NARROW = chr(0xE051)

font = {
    "providers": [
        {"type": "bitmap", "file": "guns:font/bracket_left.png",
         "ascent": LIFT, "height": CANVAS_H, "chars": [BRACKET_LEFT]},
        {"type": "bitmap", "file": "guns:font/bracket_right.png",
         "ascent": LIFT, "height": CANVAS_H, "chars": [BRACKET_RIGHT]},
        {"type": "space", "advances": {GAP_WIDE: WIDE, GAP_NARROW: NARROW}},
    ]
}
font_path = os.path.join(root, "font", "reticle.json")
os.makedirs(os.path.dirname(font_path), exist_ok=True)
with open(font_path, "w") as f:
    json.dump(font, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(font_path)
print("reticle font done")
