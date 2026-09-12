# First-Person Gun Animations — How They Work & How to Author Your Own

*Guide for resource-pack / Blockbench authors (hi Ninjaundski!). Applies to the `fi.alavesa` Guns plugin (Paper 26.2) in this repo.*

Everything below is based on the actual plugin code (`src/main/java/fi/alavesa/guns/ShootListener.java`, `GunsPlugin.java`) and the shipped resource pack (`resource-pack/`). No client mods are involved — this all runs on vanilla clients with the server resource pack.

---

## 1. The big picture

Every gun in this plugin is a **crossbow item** whose look is chosen by a **CustomModelData STRING** (e.g. `"gun_vector"`). The resource pack file

```
resource-pack/assets/minecraft/items/crossbow.json
```

maps each CustomModelData string to a model with a `minecraft:select` on `minecraft:custom_model_data`. Inside each gun's case there is a **second** select on `minecraft:display_context`:

- `firstperson_righthand` / `firstperson_lefthand` → a special `<model>_fp` model — the gun **plus the player's arms** baked into the model's `firstperson_righthand` display.
- everything else (GUI icon, third person, ground, item frames) → the clean `<model>` (gun only).

That is why the holder sees arms wrapped around the gun, while other players and the inventory icon only ever see the clean weapon.

Compare the two Vector models to see the difference:

- `resource-pack/assets/guns/models/item/gun_vector.json` — clean gun, no arms (third person / GUI).
- `resource-pack/assets/guns/models/item/gun_vector_fp.json` — same gun **plus** `right_forearm` / `left_forearm` elements UV-mapped to a 64x64 player-skin layout (texture `bettermodel:-steve_template`).

## 2. How the plugin animates: CustomModelData keyframe swaps

**Vanilla/Java item models are static.** A single item model JSON cannot move by itself — Minecraft has no built-in per-vertex item animation (short of custom core shaders). So the plugin animates the only way vanilla items can: it **swaps the whole model, frame by frame**.

When a clip plays, the plugin rewrites the held gun's CustomModelData string through numbered frame models on a tick schedule:

```
gun_vector_reload1  →  gun_vector_reload2  →  ...  →  gun_vector_reloadN  →  gun_vector (back to rest)
```

Each frame is an **ordinary item model JSON** — exactly like `gun_vector_fp.json`, just posed differently (arm lower, mag out, etc.). The client sees the item's model data change and re-renders instantly, so stepping through 3–8 posed models reads as motion.

The three clips (fixed names, in `ShootListener.java`):

| Clip | Suffix | Plays when |
|---|---|---|
| Fire | `_fire` | the gun shoots (`playFirstPersonClip(player, gun, "_fire")`) |
| Reload | `_reload` | a reload starts (right-click) |
| Equip | `_equip` | you draw the gun (hotbar switch, `onDrawAnim`) |

So the frame model names for a gun whose base model is `gun_vector` are:

```
gun_vector_fire1 ... gun_vector_fireN
gun_vector_reload1 ... gun_vector_reloadN
gun_vector_equip1 ... gun_vector_equipN
```

Details worth knowing (all from `playModelClip` / `onDrawAnim`):

- Only **frame counts and timing** live on the server; the frames themselves are pure resource-pack models. Nothing here needs a plugin rebuild.
- Attachment overlays (scope/grip/heavy barrel, CustomModelData indices 1–3) **ride along** on every frame — only string index 0 (the base model) is swapped.
- A clip **aborts cleanly** if you switch slots, the gun changes, or you start aiming mid-clip; at the end the model settles back to the base model (or `_aim` if aiming, or `_emptymag` if the mag is empty).
- Equip always plays a small built-in hand-dip + sound even with no frames authored; authored `_equip` frames play on top of that (and only when the gun has ammo).

## 3. Frame counts & timing: config and the `/guns anim` command

The pack supplies the *pictures*; the server decides *how many frames* a gun has and *how long each shows*. Two levels:

**Per gun (recommended)** — the admin command:

```
/guns anim <gun> <fire|reload|equip> <frames> [frame-ticks]
```

- Example: `/guns anim vector reload 4 2` → the Vector's reload steps through `gun_vector_reload1..4`, each shown for 2 ticks.
- Saved to `guns.yml` under `guns.<gun>.anim.<clip>.{frames, frame-ticks}`.
- `frames 0` **removes** the clip for that gun.
- `frame-ticks` defaults to 2 if omitted.
- Run `/guns anim` with no arguments to list every gun's configured clips.

**Global fallback** — `config.yml`, used by any gun with no per-gun entry:

```yaml
fp-anim:
  enabled: true
  fire:   { frames: 0,  frame-ticks: 1 }
  reload: { frames: 0,  frame-ticks: 2 }
  equip:  { frames: 0,  frame-ticks: 2 }
```

Rules:

- `fp-anim.enabled: true` is the master switch — nothing animates without it (it ships enabled).
- `frames: 0` means "no clip authored" — the plugin does nothing for that clip. This is the shipped default, so **a gun only animates once you author frames and set a count**.
- `frame-ticks` = game ticks each frame is displayed. 20 ticks = 1 second, so 2 ticks/frame = 10 fps stepping.

Total clip length = `frames x frame-ticks` ticks. A 4-frame reload at 2 ticks each = 8 ticks = 0.4 s of animation.

## 4. Context: how other servers/packs do first-person gun animation

Three known approaches to this problem on vanilla clients:

- **(A) OBJMC / custom core-shader vertex animation.** One base mesh plus a texture that encodes per-vertex offsets; a custom vanilla *core shader* shipped in the pack reads item data and moves the vertices. Lowest memory, smoothest result — but you must ship and maintain custom core shaders, which are fragile across Minecraft versions.
- **(B) Multi-bone display entities (`item_display`).** The server spawns several invisible display entities as bones (body, magazine, slide, arms) and streams rotation/translation transforms that the client **interpolates smoothly** using the game's entity transform engine. Smooth and flexible, but heavier server-side and a very different architecture.
- **(C) CustomModelData keyframe swaps — THIS PLUGIN.** 3–8 posed model files per clip, stepped per tick by rewriting the item's model data. Simplest to author (every frame is just a Blockbench model), low memory, no shaders, no extra entities — the trade-off is stepped rather than interpolated motion.

**Guns uses (C)**, wired up through `/guns anim`. If you have seen the three-method comparison image: this is method #3.

## 5. Walkthrough: make your own reload animation for the Kriss Vector

Gun id: `vector`. Base model: `gun_vector`. We'll author a 4-frame reload (grip mag → mag out → mag in → charge).

### Step 1 — Pose the frames in Blockbench

1. Open `resource-pack/assets/guns/models/item/gun_vector_fp.json` in Blockbench (it is a plain Java item model — gun elements plus two arm cuboids).
2. Duplicate it once per frame. In each copy, pose the reload motion: move/rotate the `magazine` element and the `left_forearm` (support arm) to follow it, keep `right_forearm` on the grip. Frame ideas:
   - frame 1: support hand grips the magazine
   - frame 2: magazine pulled down and out (move it down/forward, arm follows)
   - frame 3: fresh magazine seated back in
   - frame 4: support hand racks the charging handle
3. **Keep the `display` block identical to `gun_vector_fp.json` in every frame** (especially `firstperson_righthand`). If the display transform drifts between frames, the gun will jump around in view instead of animating. Only the *elements* should change between frames.
4. The arms stay baked into the model, exactly like in `gun_vector_fp.json` — same `"arm": "bettermodel:-steve_template"` texture and player-skin UVs.

### Step 2 — Export the frame models into the pack

Save/export each frame as a Java item model JSON into:

```
resource-pack/assets/guns/models/item/gun_vector_reload1.json
resource-pack/assets/guns/models/item/gun_vector_reload2.json
resource-pack/assets/guns/models/item/gun_vector_reload3.json
resource-pack/assets/guns/models/item/gun_vector_reload4.json
```

Minimal shape of one frame model (this matches the real files' style — trimmed to two elements here; your real file will carry all gun elements plus both arms):

```json
{
  "credit": "Kriss Vector reload frame 2: magazine out, support hand low.",
  "textures": {
    "0": "block/polished_blackstone",
    "1": "block/iron_block",
    "2": "block/deepslate",
    "arm": "bettermodel:-steve_template",
    "particle": "block/polished_blackstone"
  },
  "elements": [
    {
      "name": "magazine",
      "from": [7.1, -5, 8], "to": [8.9, 1, 10.6],
      "faces": {
        "north": { "texture": "#2" }, "south": { "texture": "#2" },
        "east": { "texture": "#2" }, "west": { "texture": "#2" },
        "up": { "texture": "#2" }, "down": { "texture": "#2" }
      }
    },
    {
      "name": "left_forearm",
      "from": [6, -3, 5], "to": [10, 1, 14],
      "rotation": { "origin": [8, -1, 5], "axis": "x", "angle": 22.5 },
      "faces": {
        "north": { "texture": "#arm", "uv": [11, 4, 12, 5] },
        "south": { "texture": "#arm", "uv": [12, 4, 13, 5] },
        "up":    { "texture": "#arm", "uv": [11, 5, 12, 7.5] },
        "down":  { "texture": "#arm", "uv": [13, 5, 14, 7.5] },
        "east":  { "texture": "#arm", "uv": [12, 5, 13, 7.5] },
        "west":  { "texture": "#arm", "uv": [10, 5, 11, 7.5] }
      }
    }
  ],
  "display": {
    "thirdperson_righthand": { "rotation": [0, 90, 0], "translation": [0, 2, -1], "scale": [0.6, 0.6, 0.6] },
    "thirdperson_lefthand": { "rotation": [0, -90, 0], "translation": [0, 2, -1], "scale": [0.6, 0.6, 0.6] },
    "firstperson_righthand": { "rotation": [0, 90, 0], "translation": [1, 1, 1], "scale": [0.6, 0.6, 0.6] },
    "firstperson_lefthand": { "rotation": [0, -90, 0], "translation": [1, 1, 1], "scale": [0.6, 0.6, 0.6] },
    "gui": { "rotation": [22.5, -135, 0], "translation": [0, -1.5, 0], "scale": [0.5, 0.5, 0.5] },
    "ground": { "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45] },
    "fixed": { "rotation": [0, 90, 0], "scale": [0.7, 0.7, 0.7] }
  }
}
```

### Step 3 — Register the frame names in `crossbow.json`

The plugin will set the item's CustomModelData to `gun_vector_reload1` … `gun_vector_reload4` during the clip, so `assets/minecraft/items/crossbow.json` needs a `when` case for each. Add one case per frame to the first `custom_model_data` select's `cases` array (next to the existing `gun_vector` cases), mirroring the existing shape — first-person shows the posed frame, and the **fallback stays on the clean `gun_vector`** so other players and the GUI never see your arms-baked frame:

```json
{
  "when": "gun_vector_reload1",
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:display_context",
    "cases": [
      { "when": "firstperson_righthand", "model": { "type": "minecraft:model", "model": "guns:item/gun_vector_reload1" } },
      { "when": "firstperson_lefthand",  "model": { "type": "minecraft:model", "model": "guns:item/gun_vector_reload1" } }
    ],
    "fallback": { "type": "minecraft:model", "model": "guns:item/gun_vector" }
  }
}
```

Repeat for `gun_vector_reload2` … `gun_vector_reload4` (only the two model paths and the `when` string change). Any frame name the plugin sets that has **no** case in `crossbow.json` falls through to the vanilla crossbow model — if your gun briefly turns into a crossbow mid-reload, a `when` case is missing or misspelled.

### Step 4 — Wire up the timing on the server

Repack/reload the resource pack on the client, then as an admin:

```
/guns anim vector reload 4 2
```

4 frames, 2 ticks each (0.4 s clip). This is saved into `guns.yml` as:

```yaml
guns:
  vector:
    anim:
      reload:
        frames: 4
        frame-ticks: 2
```

### Step 5 — Test in game

Hold the Vector, fire a few rounds, then **right-click to reload**. You should see the four frames step through, then the gun settle back to `gun_vector_fp`. Tweaks:

- Too fast/slow? Re-run with different `frame-ticks` (`/guns anim vector reload 4 3`).
- Want it gone? `/guns anim vector reload 0`.
- Nothing plays? Check, in order: `fp-anim.enabled: true` in `config.yml`; the frame count is set (>0); the pack actually loaded (does `gun_vector` itself render?); the `when` names in `crossbow.json` exactly match `<model>_<clip><n>` with no `.json` and no typos.

## 6. Cheat sheet

| Thing | Where |
|---|---|
| CustomModelData → model routing (add frame cases here) | `resource-pack/assets/minecraft/items/crossbow.json` |
| Gun models (clean, `_fp`, and your frames) | `resource-pack/assets/guns/models/item/` |
| Frame naming | `<base-model>_<clip><n>` e.g. `gun_vector_reload3` |
| Clips | `_fire`, `_reload`, `_equip` (fixed — no custom clip names) |
| Per-gun timing | `/guns anim <gun> <clip> <frames> [frame-ticks]` → `guns.yml` |
| Global fallback + master switch | `config.yml` → `fp-anim` |
| Animation code | `src/main/java/fi/alavesa/guns/ShootListener.java` (`playModelClip`, `playFirstPersonClip`, `clipFrames`, `onDrawAnim`) |

Rule of thumb for authoring: 3–4 frames for fire/recoil at 1–2 ticks, 4–8 frames for reload at 2–3 ticks, 2–4 frames for equip at 2 ticks. Same `display` block on every frame; pose with the elements, not the display transforms.
