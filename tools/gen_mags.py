#!/usr/bin/env python3
"""Magazine textures + models for the Guns resource pack.

Two 16x16 dark-metal magazine silhouettes: a short straight pistol mag and a
taller curved rifle mag, each with a brass round peeking out of the feed lips.
Mag items ride minecraft:prismarine_shard - the dispatch written here selects
by custom_model_data string with a vanilla fallback, so plain prismarine
shards still look vanilla.

Run from the repo root:  python3 tools/gen_mags.py
"""
import json, os, struct, zlib

def png(path, px):
    h, w = len(px), len(px[0])
    rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in px)
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    print(path)

root = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets")

CLEAR = (0, 0, 0, 0)
METAL = (58, 61, 68, 255)    # dark gunmetal body
EDGE  = (88, 92, 100, 255)   # worn highlight on the left/top edge
DARK  = (36, 38, 43, 255)    # seams, right-side shadow, baseplate
BRASS = (196, 164, 84, 255)  # top round peeking out of the feed lips

def blank():
    return [[CLEAR] * 16 for _ in range(16)]

def box(px, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[y][x] = color

# --- pistol mag: short straight single-stack box ---------------------------
px = blank()
box(px, 5, 4, 10, 13, METAL)          # body
box(px, 5, 4, 5, 13, EDGE)            # left highlight
box(px, 10, 4, 10, 13, DARK)          # right shadow
box(px, 4, 13, 11, 14, DARK)          # baseplate, a touch wider
box(px, 6, 7, 9, 7, DARK)             # witness-hole seam
box(px, 6, 10, 9, 10, DARK)
box(px, 6, 3, 8, 3, BRASS)            # chambered round at the feed lips
png(os.path.join(root, "guns", "textures", "item", "mag_pistol.png"), px)

# --- rifle mag: taller curved (banana) box ----------------------------------
px = blank()
for y in range(2, 14):
    shift = (y - 2) // 4              # body drifts right as it drops = the curve
    x0 = 4 + shift
    box(px, x0, y, x0 + 4, y, METAL)
    px[y][x0] = EDGE                  # leading-edge highlight follows the curve
    px[y][x0 + 4] = DARK              # trailing-edge shadow too
box(px, 6, 5, 8, 5, DARK)             # rib seams across the body
box(px, 7, 9, 9, 9, DARK)
box(px, 6, 13, 11, 14, DARK)          # baseplate at the curved end
box(px, 5, 1, 7, 1, BRASS)            # chambered round
png(os.path.join(root, "guns", "textures", "item", "mag_rifle.png"), px)

# --- flat item models + the prismarine_shard dispatch -----------------------
models = os.path.join(root, "guns", "models", "item")
os.makedirs(models, exist_ok=True)
cases = []
for mag in ("mag_pistol", "mag_rifle"):
    with open(os.path.join(models, mag + ".json"), "w") as f:
        json.dump({"parent": "minecraft:item/generated",
                   "textures": {"layer0": f"guns:item/{mag}"}}, f, indent=2)
    print(os.path.join(models, mag + ".json"))
    cases.append({"when": mag,
                  "model": {"type": "minecraft:model", "model": f"guns:item/{mag}"}})

dispatch_dir = os.path.join(root, "minecraft", "items")
os.makedirs(dispatch_dir, exist_ok=True)
with open(os.path.join(dispatch_dir, "prismarine_shard.json"), "w") as f:
    json.dump({"model": {
        "type": "minecraft:select",
        "property": "minecraft:custom_model_data",
        "cases": cases,
        "fallback": {"type": "minecraft:model", "model": "minecraft:item/prismarine_shard"}}},
        f, indent=2)
print(os.path.join(dispatch_dir, "prismarine_shard.json"))
print("mag textures + models + dispatch done")
