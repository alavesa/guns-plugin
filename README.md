# Guns — config-driven custom guns for Paper

Custom guns for Paper servers: define guns in `guns.yml` or create and edit them **in-game**,
each with its own name, damage, fire rate, range, magazine, reload time, sound and resource
pack model. Guns are held in the **crossbow aiming pose** (they're pre-charged crossbows under
the hood — vanilla firing is fully cancelled and replaced with instant raytrace shots).

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
| `/guns list` | list gun ids | guns.use |
| `/guns give <id> [player]` | get a gun | guns.give |
| `/guns create <id>` | create a new gun with default stats | guns.admin (op) |
| `/guns edit <id> <stat> <value>` | edit a stat | guns.admin (op) |
| `/guns reload` | reload guns.yml | guns.admin (op) |

Editable stats: `name`, `model`, `damage`, `firerate`, `range`, `magazine`, `reloadticks`,
`sound`, `soundpitch`. Stat edits apply **immediately to already-given guns** — only `name`
and `model` are baked into the item, so re-run `/guns give` after changing those.

Example:

```
/guns create shotgun
/guns edit shotgun damage 9
/guns edit shotgun firerate 0.8
/guns edit shotgun name &6Shotgun
/guns give shotgun
```

## Resource pack (models)

Each gun's `model` stat is a `custom_model_data` **string** id on a `minecraft:crossbow` item
(1.21.4+ model system) — e.g. `gun_pistol`, `gun_rifle`. Point your resource pack's item-model
definition for the crossbow at those ids. Because the gun is a *charged* crossbow, your model
replaces the charged-crossbow appearance; without a resource pack the gun shows as a loaded
crossbow (the aiming pose works regardless).

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
