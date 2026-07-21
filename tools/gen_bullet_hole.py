#!/usr/bin/env python3
"""Bullet-hole decal for the Guns plugin.

A small impact mark spawned as an ItemDisplay where a bullet hits a wall, gone
after 15 seconds. It's a FLINT item carrying custom_model_data "bullet_hole", so
the pack dispatches it to a flat cracked-hole sprite.

Writes:
  resource-pack/assets/guns/textures/item/bullet_hole.png   (8x8)
  resource-pack/assets/guns/models/item/bullet_hole.json
  adds a "bullet_hole" case to assets/minecraft/items/flint.json (idempotent)

Run from the repo root:  python3 tools/gen_bullet_hole.py
"""
import json, os, struct, zlib

HOLE   = (18, 16, 14, 255)     # dark centre
RING   = (60, 54, 48, 255)     # scorched rim
CRACK  = (40, 36, 32, 220)     # hairline cracks
DUST   = (95, 88, 80, 160)     # faint outer dust

W = H = 8


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


def hole():
    c = Canvas(W, H)
    # scorched rim
    for x, y in [(2, 2), (3, 2), (4, 2), (5, 2), (2, 5), (3, 5), (4, 5), (5, 5),
                 (2, 3), (2, 4), (5, 3), (5, 4)]:
        c.set(x, y, RING)
    # dark centre
    for x, y in [(3, 3), (4, 3), (3, 4), (4, 4)]:
        c.set(x, y, HOLE)
    # a few radiating cracks
    for x, y in [(1, 1), (6, 1), (1, 6), (6, 6), (0, 4), (7, 3), (4, 0), (3, 7)]:
        c.set(x, y, CRACK)
    # faint dust halo
    for x, y in [(1, 3), (1, 4), (6, 3), (6, 4), (3, 1), (4, 1), (3, 6), (4, 6)]:
        c.set(x, y, DUST)
    return c


root = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets")
hole().png(os.path.join(root, "guns", "textures", "item", "bullet_hole.png"))

model_path = os.path.join(root, "guns", "models", "item", "bullet_hole.json")
os.makedirs(os.path.dirname(model_path), exist_ok=True)
with open(model_path, "w") as f:
    json.dump({"parent": "minecraft:item/generated",
               "textures": {"layer0": "guns:item/bullet_hole"}}, f, indent=2)
    f.write("\n")
print(model_path)

# dispatch off FLINT (unused by other packs here); keep any existing structure
dispatch_path = os.path.join(root, "minecraft", "items", "flint.json")
if os.path.exists(dispatch_path):
    with open(dispatch_path) as f:
        dispatch = json.load(f)
    cases = dispatch.setdefault("model", {}).setdefault("cases", [])
    if dispatch["model"].get("type") != "minecraft:select":
        dispatch["model"] = {"type": "minecraft:select", "property": "minecraft:custom_model_data",
                             "cases": [], "fallback": {"type": "minecraft:model", "model": "minecraft:item/flint"}}
        cases = dispatch["model"]["cases"]
else:
    dispatch = {"model": {"type": "minecraft:select", "property": "minecraft:custom_model_data",
                          "cases": [],
                          "fallback": {"type": "minecraft:model", "model": "minecraft:item/flint"}}}
    cases = dispatch["model"]["cases"]
cases[:] = [c for c in cases if c.get("when") != "bullet_hole"]
cases.append({"when": "bullet_hole",
              "model": {"type": "minecraft:model", "model": "guns:item/bullet_hole"}})
os.makedirs(os.path.dirname(dispatch_path), exist_ok=True)
with open(dispatch_path, "w") as f:
    json.dump(dispatch, f, indent=2)
    f.write("\n")
print(dispatch_path)
print("bullet hole done")
