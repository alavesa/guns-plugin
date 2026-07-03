# Guns — config-driven custom guns for Paper

Custom guns **and grenades** for Paper servers: define them in `guns.yml` or create and edit
them **in-game**, each with its own name, damage, fire rate, range, magazine, reload time,
sound and resource pack model — plus **backstab bonus damage**, **bleed/poison hit effects**
and **ricocheting bullets**. Guns are held in the **crossbow aiming pose** (they're
pre-charged crossbows under the hood — vanilla firing is fully cancelled and replaced with
instant raytrace shots).

## Install

Drop `Guns-x.y.z.jar` into the server's `plugins/` folder and restart. Requires Paper (or
Spigot) 1.21.4+ and Java 21. No datapack needed.

## Use

- **Shoot**: right-click. Instant hit up to `range` blocks, tracer particles, ammo counter in
  the actionbar.
- **Reload**: press **F** (swap-hands). Takes `reload-ticks`.
- Out of ammo → click sound + "press F to reload".

## Commands (`/guns`)

| Command | What it does | Permission |
|---|---|---|
| `/guns list` | list gun + grenade ids | guns.use |
| `/guns give <id> [player]` | get a gun or grenade | guns.give |
| `/guns create <id> [gun\|grenade]` | create with default stats | guns.admin (op) |
| `/guns edit <id> <stat> <value>` | edit a stat | guns.admin (op) |
| `/guns reload` | reload guns.yml | guns.admin (op) |

Gun stats: `name`, `model`, `damage`, `firerate`, `range`, `magazine`, `reloadticks`,
`sound`, `soundpitch`, `backstab`, `effect`, `effectticks`, `effectlevel`, `ricochet`.
Grenade stats: `name`, `model`, `power`, `fuseticks`, `velocity`, `breakblocks`.
Stat edits apply **immediately to already-given items** — only `name` and `model` are baked
into the item, so re-run `/guns give` after changing those.

Example:

```
/guns create shotgun
/guns edit shotgun damage 9
/guns edit shotgun firerate 0.8
/guns edit shotgun name &6Shotgun
/guns give shotgun

/guns create sticky grenade
/guns edit sticky power 4
/guns edit sticky fuseticks 40
/guns give sticky
```

## Combat features

- **Backstab** (`backstab`, multiplier): shots that hit a target from behind deal
  `damage × backstab`, with a "Backstab!" actionbar + crit sound. `1.0` disables it.
- **Hit effects** (`effect` + `effectticks` + `effectlevel`): `bleed` deals `effectlevel`
  raw damage per second with blood particles for the duration; any potion effect name
  (`poison`, `wither`, `slowness`, `glowing`, …) applies that effect at `effectlevel`.
- **Ricochet** (`ricochet`, bounce count): bullets reflect off blocks with a metallic ping
  and keep going until they run out of `range` (total across bounces). After the first
  bounce your own bullet can hit YOU — mind the angles.
- **Grenades**: vanilla throw arc (snowball); `fuseticks 0` explodes on impact, otherwise
  the grenade lands, cooks visibly on the ground, then explodes (`power`; TNT is 4.0).
  Explosions knock back and damage like real ones; `breakblocks` is off by default.

## Resource pack (models) — ready-made in [`resource-pack/`](resource-pack/)

The repo ships a working resource pack with **placeholder 3D models for every example gun and
grenade** (`gun_pistol`, `gun_rifle`, `gun_venom`, `grenade_frag`) — grab
`GunsResourcePack.zip` from the release, drop it in your `resourcepacks` folder (or serve it
via `server.properties` → `resource-pack=<url>`), and the guns show as 3D guns out of the box.
Replace the placeholders with your own Blockbench models when ready.

How it's wired: each gun's `model` stat is a `custom_model_data` **string** id on a
`minecraft:crossbow` item (grenades: `minecraft:snowball`), selected in
`assets/minecraft/items/crossbow.json` / `snowball.json` (1.21.4+ item model system), pointing
at models under `assets/guns/models/item/`. Without any pack the guns still work and show as
loaded crossbows (the aiming pose is a player animation, independent of the model).

**Full step-by-step instructions — in Finnish — in
[`resource-pack/OHJEET.md`](resource-pack/OHJEET.md)**: plugin install, pack install (personal
and server-wide), making models in Blockbench, adding them to the pack, and a
troubleshooting checklist.

Gun sounds accept any namespaced key, so `sound` can point at your resource pack's custom
sounds (e.g. `guns:shot.pistol` from your pack's `sounds.json`).

## Building

```
mvn package    # requires JDK 21; jar lands in target/
```

## Honest notes

- Shots are instant raytraces (hitscan), not projectiles — no travel time or drop.
- Fire rate is a cap on right-click shots (semi-auto). True hold-to-fire full-auto isn't
  reliably detectable server-side; if you want it, a "burst" stat is the practical route.
- Damage respects armor/enchants (it goes through the normal damage pipeline, credited to the
  shooter).
