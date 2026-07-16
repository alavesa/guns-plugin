#!/usr/bin/env python3
"""Bullet look for gun projectiles.

Guns now fire real ARROW entities, so the arrow's projectile texture is
repainted into a short bright tracer/bullet streak. NOTE: this retextures
EVERY arrow on the server, not just gun bullets - acceptable on this
gun-focused SCP server. Run from the repo root:  python3 tools/gen_bullet.py
"""
import os, struct, zlib

def png(path, px):
    h, w = len(px), len(px[0])
    rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in px)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)
    print(path)

# arrow.png is a 16x32 layout (the vanilla arrow uv sheet). A hot-metal
# streak: white-hot core fading to orange, transparent elsewhere.
W, H = 16, 32
px = [[(0, 0, 0, 0)] * W for _ in range(H)]
for y in range(H):
    # a tapering streak down the middle
    t = y / H
    for x in range(W):
        d = abs(x - 7.5)
        if d < 1.5 and 2 < y < 30:
            core = (255, 250, 210, 255) if t < 0.5 else (255, 200, 90, 255)
            px[y][x] = core
        elif d < 2.6 and 4 < y < 28:
            px[y][x] = (255, 160, 40, 220)
        elif d < 3.4 and 6 < y < 24:
            px[y][x] = (230, 90, 20, 120)

RP = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "minecraft",
                  "textures", "entity", "projectiles")
png(os.path.join(RP, "arrow.png"), px)
png(os.path.join(RP, "tipped_arrow.png"), [row[:] for row in px])
# older packs used entity/arrow.png - cover that path too
RP2 = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "minecraft",
                   "textures", "entity")
png(os.path.join(RP2, "arrow.png"), [row[:] for row in px])
print("bullet tracer textures written")
