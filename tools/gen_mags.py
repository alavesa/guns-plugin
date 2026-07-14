#!/usr/bin/env python3
"""Magazine textures + models for the Guns resource pack.

The full premade ammo family, each a distinct 16x16 silhouette:
  mag_pistol      short straight single-stack box
  mag_pistol_ext  longer straight box (extended)
  mag_rifle       tall curved (banana) mag
  mag_rifle_drum  round drum with a feed tower
  mag_smg         long thin stick mag
  mag_sniper      short boxy wide mag
  shells_shotgun  bundle of red shotgun shells, brass heads

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

CLEAR  = (0, 0, 0, 0)
METAL  = (58, 61, 68, 255)    # dark gunmetal body
EDGE   = (88, 92, 100, 255)   # worn highlight on the left/top edge
DARK   = (36, 38, 43, 255)    # seams, right-side shadow, baseplate
BRASS  = (196, 164, 84, 255)  # brass: chambered round / shell head
RED    = (172, 46, 46, 255)   # shotgun shell hull
RED_HI = (204, 74, 66, 255)   # shell hull highlight

def blank():
    return [[CLEAR] * 16 for _ in range(16)]

def box(px, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[y][x] = color

def straight_mag(px, y_top, y_bottom, seams):
    """Shared straight-box body: left highlight, right shadow, wider baseplate."""
    box(px, 5, y_top, 10, y_bottom, METAL)
    box(px, 5, y_top, 5, y_bottom, EDGE)
    box(px, 10, y_top, 10, y_bottom, DARK)
    box(px, 4, y_bottom, 11, min(15, y_bottom + 1), DARK)
    for y in seams:
        box(px, 6, y, 9, y, DARK)
    box(px, 6, y_top - 1, 8, y_top - 1, BRASS)  # chambered round at the feed lips

def draw_mag_pistol(px):
    straight_mag(px, 4, 13, (7, 10))

def draw_mag_pistol_ext(px):
    straight_mag(px, 2, 13, (5, 8, 11))

def draw_mag_rifle(px):
    for y in range(2, 14):
        shift = (y - 2) // 4            # body drifts right as it drops = the curve
        x0 = 4 + shift
        box(px, x0, y, x0 + 4, y, METAL)
        px[y][x0] = EDGE                # leading-edge highlight follows the curve
        px[y][x0 + 4] = DARK            # trailing-edge shadow too
    box(px, 6, 5, 8, 5, DARK)           # rib seams across the body
    box(px, 7, 9, 9, 9, DARK)
    box(px, 6, 13, 11, 14, DARK)        # baseplate at the curved end
    box(px, 5, 1, 7, 1, BRASS)          # chambered round

def draw_mag_rifle_drum(px):
    for y in range(16):                 # the drum itself
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 9.0) ** 2) ** 0.5
            if d <= 5.4:
                px[y][x] = METAL
            if 4.6 <= d <= 5.4 and x < 8 and y < 9:
                px[y][x] = EDGE         # top-left rim catches the light
            if 4.6 <= d <= 5.4 and x >= 8 and y >= 9:
                px[y][x] = DARK         # bottom-right rim in shadow
            if 2.4 <= d <= 3.1:
                px[y][x] = DARK         # winding groove ring
    box(px, 7, 8, 8, 9, DARK)           # hub
    box(px, 6, 2, 9, 3, METAL)          # feed tower up to the gun
    box(px, 6, 2, 6, 3, EDGE)
    box(px, 9, 2, 9, 3, DARK)
    box(px, 7, 1, 8, 1, BRASS)          # chambered round

def draw_mag_smg(px):
    box(px, 6, 1, 9, 13, METAL)         # long thin stick
    box(px, 6, 1, 6, 13, EDGE)
    box(px, 9, 1, 9, 13, DARK)
    for y in (4, 8, 12):
        box(px, 7, y, 8, y, DARK)       # witness-hole seams
    box(px, 5, 14, 10, 15, DARK)        # baseplate
    box(px, 7, 0, 8, 0, BRASS)          # chambered round

def draw_mag_sniper(px):
    box(px, 4, 7, 11, 12, METAL)        # short boxy double-stack
    box(px, 4, 7, 4, 12, EDGE)
    box(px, 11, 7, 11, 12, DARK)
    box(px, 5, 10, 10, 10, DARK)        # single seam
    box(px, 3, 13, 12, 14, DARK)        # baseplate
    box(px, 6, 6, 9, 6, BRASS)          # big chambered round

def draw_shells_shotgun(px):
    for x0, y0 in ((3, 3), (7, 2), (11, 3)):     # three shells, middle one proud
        y1 = y0 + 10
        box(px, x0, y0, x0 + 2, y1, RED)         # hull
        box(px, x0, y0, x0, y1, RED_HI)          # hull highlight
        box(px, x0, y0, x0 + 2, y0, DARK)        # crimped top
        box(px, x0, y1 + 1, x0 + 2, y1 + 2, BRASS)  # brass head
        px[y1 + 2][x0 + 2] = DARK                # head shadow corner

MAGS = {
    "mag_pistol": draw_mag_pistol,
    "mag_pistol_ext": draw_mag_pistol_ext,
    "mag_rifle": draw_mag_rifle,
    "mag_rifle_drum": draw_mag_rifle_drum,
    "mag_smg": draw_mag_smg,
    "mag_sniper": draw_mag_sniper,
    "shells_shotgun": draw_shells_shotgun,
}

models = os.path.join(root, "guns", "models", "item")
os.makedirs(models, exist_ok=True)
cases = []
for mag, draw in MAGS.items():
    px = blank()
    draw(px)
    png(os.path.join(root, "guns", "textures", "item", mag + ".png"), px)
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
