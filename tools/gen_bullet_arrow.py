#!/usr/bin/env python3
"""Custom BULLET texture for the arrow entities guns fire.

Gun bullets are no-gravity arrow entities; this retextures the vanilla arrow
(assets/minecraft/textures/entity/projectiles/arrow.png, 16x32) into a small
brass round with a copper tip, so a fired bullet reads as a bullet in flight
rather than a fletched arrow.

NOTE: this replaces the arrow texture globally (all arrows on the server look
like bullets). If you ever want gun-only bullets, switch the projectile to a
SpectralArrow and retexture spectral_arrow.png instead.

Run from the repo root:  python3 tools/gen_bullet_arrow.py
"""
import os, struct, zlib

W, H = 16, 32
BRASS   = (196, 158, 66, 255)
BRASS_L = (232, 205, 120, 255)   # highlight
BRASS_D = (140, 108, 40, 255)    # shadow
COPPER  = (200, 112, 58, 255)    # tip
COPPER_D = (150, 74, 36, 255)


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, c)

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


c = Canvas(W, H)
# The arrow UV puts the long shaft down the texture. Paint a rounded brass casing
# with a bright highlight column and a copper nose, so however the model samples
# it, the round reads as brass metal + copper tip.
# casing body (most of the texture)
c.rect(0, 0, W, H, BRASS)
# vertical shading: darker edges, bright highlight two columns in
for y in range(H):
    c.set(0, y, BRASS_D); c.set(1, y, BRASS_D)
    c.set(W - 1, y, BRASS_D); c.set(W - 2, y, BRASS_D)
    c.set(4, y, BRASS_L); c.set(5, y, BRASS_L)
# copper tip band (top and bottom sixths, wherever the nose maps)
c.rect(0, 0, W, 5, COPPER)
c.rect(0, H - 5, W, H, COPPER)
for x in range(W):
    c.set(x, 0, COPPER_D); c.set(x, H - 1, COPPER_D)

root = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets",
                    "minecraft", "textures", "entity", "projectiles")
c.png(os.path.join(root, "arrow.png"))
c.png(os.path.join(root, "tipped_arrow.png"))   # keep them consistent
print("bullet arrow texture done")
