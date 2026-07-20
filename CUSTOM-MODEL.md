# Custom gun & magazine models

Every gun and every magazine picks its look through a `model:` key in
guns.yml - the value is a custom_model_data string the resource pack
dispatches on. Changing looks never touches code.

## Repainting an existing mag

The default textures live in the combined pack sources at
`resource-pack/assets/guns/textures/item/` (mag_pistol.png, mag_rifle.png,
mag_rifle_drum.png, ...  16x16). Repaint the PNG, keep the filename, rebuild
the combined pack (`~/Lab/tools/build-pack.sh` - prints the new
sha1 for server.properties). `tools/gen_mags.py` restores the defaults.

## Giving a mag a brand-new look

1. Add the texture: `resource-pack/assets/guns/textures/item/<name>.png`
2. Add the model: `resource-pack/assets/guns/models/item/<name>.json`:
   `{"parent": "minecraft:item/generated", "textures": {"layer0": "guns:item/<name>"}}`
3. Add a case to `resource-pack/assets/minecraft/items/prismarine_shard.json`:
   `{"when": "<name>", "model": {"type": "minecraft:model", "model": "guns:item/<name>"}}`
4. Point the mag at it in-game: `/guns edit <mag-id> model <name>`
   (or set `model: <name>` in guns.yml + /guns reload)
5. Rebuild the combined pack.

Guns work identically (their dispatches ride crossbow/snowball). New mags
are created in-game with `/guns create <id> mag`, removed with
`/guns remove <id>`.
