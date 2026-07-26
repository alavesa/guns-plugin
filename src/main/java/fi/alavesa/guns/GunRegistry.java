package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads, saves and edits guns.yml (guns, grenades AND mags), and builds the actual items. */
public final class GunRegistry {

    public static final Set<String> GUN_EDITABLE = Set.of(
        "name", "model", "damage", "firerate", "range", "magazine", "reloadticks",
        "sound", "soundpitch", "effect", "effectticks", "effectlevel", "ricochet", "mag",
        "speed", "curve", "spread", "aimspread", "firemodes", "recoil", "pierce");

    public static final Set<String> GRENADE_EDITABLE = Set.of(
        "name", "model", "power", "fuseticks", "velocity", "breakblocks");

    public static final Set<String> MAG_EDITABLE = Set.of("name", "model", "capacity");

    /** The premade mag family. Also written into guns.yml files that predate the
     *  mags feature, so existing servers pick them up without touching the config. */
    private record MagDefault(String id, String name, int capacity) {}
    private static final List<MagDefault> DEFAULT_MAGS = List.of(
        new MagDefault("mag_pistol", "&7Pistol Magazine", 12),
        new MagDefault("mag_pistol_ext", "&7Extended Pistol Magazine", 20),
        new MagDefault("mag_rifle", "&8Rifle Magazine", 5),
        new MagDefault("mag_rifle_drum", "&8Rifle Drum", 10),
        new MagDefault("mag_smg", "&2SMG Magazine", 24),
        new MagDefault("mag_sniper", "&8Sniper Magazine", 3),
        new MagDefault("shells_shotgun", "&cShotgun Shells", 6));

    private final Plugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey grenadeKey;
    private final NamespacedKey ammoKey;
    private final NamespacedKey magKey;
    private final NamespacedKey magCapacityKey;
    private final NamespacedKey fireModeKey;
    private final NamespacedKey instanceKey;
    private final NamespacedKey roundKey;
    private final NamespacedKey vestTierKey;
    private final NamespacedKey vestProtKey;
    /** Live ammo per gun INSTANCE, held in RAM keyed by the item's instance id. Ammo lives
     *  here, NOT in the item, so firing never rewrites the held item - which is what made the
     *  gun re-equip/bob on the screen every shot on 1.21.2+/26.x. Seeded from the item's
     *  stamped magazine on first read; not persisted across restart (guns come back full). */
    private final Map<String, Integer> liveAmmo = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Gun> guns = new LinkedHashMap<>();
    private final Map<String, Grenade> grenades = new LinkedHashMap<>();
    private final Map<String, Mag> mags = new LinkedHashMap<>();
    private File file;
    private YamlConfiguration yaml;

    public GunRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "id");
        this.grenadeKey = new NamespacedKey(plugin, "grenade");
        this.ammoKey = new NamespacedKey(plugin, "ammo");
        this.magKey = new NamespacedKey(plugin, "mag");
        this.magCapacityKey = new NamespacedKey(plugin, "mag_capacity");
        this.fireModeKey = new NamespacedKey(plugin, "fire_mode");
        this.instanceKey = new NamespacedKey(plugin, "gun_uid");
        this.roundKey = new NamespacedKey(plugin, "round");
        this.vestTierKey = new NamespacedKey(plugin, "vest_tier");
        this.vestProtKey = new NamespacedKey(plugin, "vest_prot");
    }

    /** Build a ballistic vest (a dyed, chest-slot leather chestplate carrying its tier + a
     *  protection stat that degrades as it's shot). */
    public ItemStack buildVest(Armor a) {
        ItemStack item = new ItemStack(Material.LEATHER_CHESTPLATE);
        org.bukkit.inventory.meta.LeatherArmorMeta meta =
            (org.bukkit.inventory.meta.LeatherArmorMeta) item.getItemMeta();
        meta.setColor(a.dye);
        meta.itemName(Component.text(a.display, a.color).decoration(TextDecoration.ITALIC, false));
        java.util.List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("Stops rounds rated tier " + a.tier + " and below.",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        if (a.slowness >= 0) lore.add(Component.text("Heavy - slows the wearer.",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(a.model()));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(vestTierKey, PersistentDataType.INTEGER, a.tier);
        meta.getPersistentDataContainer().set(vestProtKey, PersistentDataType.INTEGER, 100);
        item.setItemMeta(meta);
        return item;
    }

    /** The vest tier of an item (1-5), or 0 if it isn't one of our vests. */
    public int vestTier(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(vestTierKey, PersistentDataType.INTEGER, 0);
    }

    /** Remaining protection (0-100) of a vest. */
    public int vestProt(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(vestProtKey, PersistentDataType.INTEGER, 100);
    }

    /** Write a vest's remaining protection back onto the item (caller re-equips it). */
    public void setVestProt(ItemStack item, int prot) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(vestProtKey, PersistentDataType.INTEGER, Math.max(0, prot));
        item.setItemMeta(meta);
    }

    /** The chambering ROUND: a crossbow still needs a real arrow-type item to play its reload
     *  pull, but instead of dropping a bare vanilla arrow in the player's bag we hand them this -
     *  a custom-NBT (roundKey), custom-textured (custom_model_data "gun_round") arrow that reads
     *  as a gun round and is reclaimed the instant the reload finishes. */
    public ItemStack buildRound() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Round")
            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("gun_round"));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(roundKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** True if this is one of our chambering rounds (by NBT tag), not a player's real arrow. */
    public boolean isRound(ItemStack item) {
        return item != null && item.getType() == Material.ARROW && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(roundKey, PersistentDataType.BYTE);
    }

    /** The gun item's selected fire mode, defaulting to the gun's first offered
     *  mode if none is stamped or the stamped one is no longer offered. */
    public String fireModeOf(ItemStack item, Gun gun) {
        if (item == null || !item.hasItemMeta()) return gun.defaultMode();
        String m = item.getItemMeta().getPersistentDataContainer().get(fireModeKey, PersistentDataType.STRING);
        return (m != null && gun.hasMode(m)) ? m : gun.defaultMode();
    }

    public void setFireMode(ItemStack item, String mode) {
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(fireModeKey, PersistentDataType.STRING, mode);
        item.setItemMeta(meta);
    }

    public NamespacedKey grenadeKey() { return grenadeKey; }

    public void load() {
        file = new File(plugin.getDataFolder(), "guns.yml");
        if (!file.exists()) plugin.saveResource("guns.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
        migrate();
        guns.clear();
        grenades.clear();
        mags.clear();
        // Mags load first so guns can validate their mag reference below.
        ConfigurationSection mroot = yaml.getConfigurationSection("mags");
        if (mroot != null) {
            for (String id : mroot.getKeys(false)) {
                ConfigurationSection s = mroot.getConfigurationSection(id);
                if (s == null) continue;
                mags.put(id.toLowerCase(), new Mag(
                    id.toLowerCase(),
                    s.getString("name", id),
                    s.getString("model", "mag_" + id),
                    (int) clamp(id, "capacity", s.getInt("capacity", 10), 1, 1000)
                ));
            }
        }
        ConfigurationSection root = yaml.getConfigurationSection("guns");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                // "none"/missing mag = old loose-rounds reload; an unknown mag id would make
                // the gun impossible to reload (the item can't exist), so warn and go loose.
                String magRef = s.getString("mag", "none");
                String magId = magRef == null || magRef.isBlank() || magRef.equalsIgnoreCase("none")
                    ? "" : magRef.toLowerCase();
                if (!magId.isEmpty() && !mags.containsKey(magId)) {
                    plugin.getLogger().warning("Gun '" + id + "' wants unknown mag '" + magRef
                        + "' - reloading loose rounds until that mag exists (/guns create "
                        + magRef + " mag).");
                    magId = "";
                }
                // Stats are clamped to sane ranges: a runaway value (range 99999...) makes
                // every shot scan a huge area and can stall the whole server.
                guns.put(id.toLowerCase(), new Gun(
                    id.toLowerCase(),
                    s.getString("name", id),
                    s.getString("model", "gun_" + id),
                    clamp(id, "damage", s.getDouble("damage", 4.0), 0, 100),
                    clamp(id, "fire-rate", s.getDouble("fire-rate", 2.0), 0.1, 20),
                    clamp(id, "range", s.getDouble("range", 50), 1, 128),
                    (int) clamp(id, "magazine", s.getInt("magazine", 10), 1, 1000),
                    (int) clamp(id, "reload-ticks", s.getInt("reload-ticks", 30), 0, 200),
                    s.getString("sound", "minecraft:entity.firework_rocket.blast"),
                    (float) s.getDouble("sound-pitch", 1.5),
                    // (a leftover "backstab" key from pre-0.6.0 configs is simply ignored)
                    s.getString("effect", "none"),
                    (int) clamp(id, "effect-ticks", s.getInt("effect-ticks", 60), 0, 1200),
                    (int) clamp(id, "effect-level", s.getInt("effect-level", 1), 1, 10),
                    (int) clamp(id, "ricochet", s.getInt("ricochet", 0), 0, 8),
                    magId,
                    s.getString("base", "crossbow"),
                    clamp(id, "spread", s.getDouble("spread", 2.0), 0, 30),
                    clamp(id, "drop", s.getDouble("drop", 0.03), 0, 1),
                    clamp(id, "speed", s.getDouble("speed", 3.0), 0.5, 6),
                    clamp(id, "curve", s.getDouble("curve", 0.05), 0, 1),
                    // aim-spread defaults to a tighter 30% of the hip-fire spread when unset
                    clamp(id, "aim-spread",
                        s.getDouble("aim-spread", clamp(id, "spread", s.getDouble("spread", 2.0), 0, 30) * 0.3),
                        0, 30),
                    s.getString("fire-modes", "semi"),
                    clamp(id, "recoil", s.getDouble("recoil", 1.0), 0, 30),
                    (int) clamp(id, "pierce", s.getInt("pierce", 2), 0, 5)
                ));
            }
        }
        // v0.8.0: every armory gets the marksman option once
        if (yaml.getConfigurationSection("guns") != null
            && yaml.getConfigurationSection("guns.sniper") == null
            && !yaml.getBoolean("sniper-offered", false)) {
            yaml.set("guns.sniper.name", "&fFoundation Marksman Rifle");
            yaml.set("guns.sniper.model", "gun_sniper");
            yaml.set("guns.sniper.base", "spyglass");
            yaml.set("guns.sniper.damage", 16.0);
            yaml.set("guns.sniper.fire-rate", 0.6);
            yaml.set("guns.sniper.range", 120);
            yaml.set("guns.sniper.magazine", 3);
            yaml.set("guns.sniper.reload-ticks", 50);
            yaml.set("guns.sniper.sound", "minecraft:entity.blaze.hurt");
            yaml.set("guns.sniper.sound-pitch", 0.5);
            yaml.set("guns.sniper.mag", "mag_sniper");
            yaml.set("sniper-offered", true);
            try {
                yaml.save(file);
            } catch (java.io.IOException e) {
                plugin.getLogger().severe("Could not save guns.yml: " + e.getMessage());
            }
            plugin.getLogger().info("Added the default spyglass sniper to guns.yml (delete it or set sniper-offered if unwanted).");
            load();
            return;
        }
        ConfigurationSection groot = yaml.getConfigurationSection("grenades");
        if (groot != null) {
            for (String id : groot.getKeys(false)) {
                ConfigurationSection s = groot.getConfigurationSection(id);
                if (s == null) continue;
                grenades.put(id.toLowerCase(), new Grenade(
                    id.toLowerCase(),
                    s.getString("name", id),
                    s.getString("model", "grenade_" + id),
                    clamp(id, "power", s.getDouble("power", 2.5), 0, 8),
                    (int) clamp(id, "fuse-ticks", s.getInt("fuse-ticks", 25), 0, 200),
                    clamp(id, "velocity", s.getDouble("velocity", 1.5), 0.1, 4),
                    s.getBoolean("break-blocks", false)
                ));
            }
        }
    }

    /** One-time upgrade for guns.yml files that predate the mags feature: write the premade
     *  mag family into the file, and point every gun WITHOUT a mag key at one (by name
     *  heuristic), writing the assignment back so ops can see and edit it. After a jar
     *  update every existing gun requires a magazine with no config surgery. A gun
     *  explicitly set to "mag: none" is respected and stays magless. */
    private void migrate() {
        boolean changed = false;
        if (!yaml.isConfigurationSection("mags")) {
            for (MagDefault d : DEFAULT_MAGS) {
                yaml.set("mags." + d.id() + ".name", d.name());
                yaml.set("mags." + d.id() + ".model", d.id());
                yaml.set("mags." + d.id() + ".capacity", d.capacity());
            }
            plugin.getLogger().info("guns.yml predates magazines - added the "
                + DEFAULT_MAGS.size() + " premade mag types to it.");
            changed = true;
        }
        ConfigurationSection root = yaml.getConfigurationSection("guns");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null || s.contains("mag")) continue; // explicit value (incl. "none") wins
                String mag = heuristicMag(id, s.getString("name", id));
                yaml.set("guns." + id + ".mag", mag);
                plugin.getLogger().info("Gun '" + id + "' had no mag entry - it now reloads from '"
                    + mag + "' (change with /guns edit " + id + " mag <mag-id|none>).");
                changed = true;
            }
        }
        if (changed) {
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save migrated guns.yml: " + e.getMessage());
            }
        }
    }

    /** Which premade mag suits this gun, judged by its id and display name. */
    private static String heuristicMag(String id, String name) {
        String hay = (id + " " + (name == null ? "" : name)).toLowerCase();
        if (hay.contains("pistol")) return "mag_pistol";
        if (hay.contains("rifle")) return "mag_rifle";
        if (hay.contains("smg")) return "mag_smg";
        if (hay.contains("shotgun")) return "shells_shotgun";
        if (hay.contains("sniper")) return "mag_sniper";
        return "mag_pistol";
    }

    public Gun get(String id) { return id == null ? null : guns.get(id.toLowerCase()); }
    public Grenade getGrenade(String id) { return id == null ? null : grenades.get(id.toLowerCase()); }
    public Mag getMag(String id) { return id == null ? null : mags.get(id.toLowerCase()); }
    public Set<String> ids() { return guns.keySet(); }
    public Set<String> grenadeIds() { return grenades.keySet(); }
    public Set<String> magIds() { return mags.keySet(); }

    /** Create a gun, grenade or mag with defaults. Returns false if the id exists in any list. */
    public boolean create(String id, String type) throws IOException {
        String key = id.toLowerCase();
        if (guns.containsKey(key) || grenades.containsKey(key) || mags.containsKey(key)) return false;
        if (type.equals("grenade")) {
            yaml.set("grenades." + key + ".name", "&f" + id);
            yaml.set("grenades." + key + ".model", "grenade_" + key);
            yaml.set("grenades." + key + ".power", 2.5);
            yaml.set("grenades." + key + ".fuse-ticks", 25);
            yaml.set("grenades." + key + ".velocity", 1.5);
            yaml.set("grenades." + key + ".break-blocks", false);
        } else if (type.equals("mag")) {
            yaml.set("mags." + key + ".name", "&f" + id);
            yaml.set("mags." + key + ".model", "mag_" + key);
            yaml.set("mags." + key + ".capacity", 10);
        } else {
            yaml.set("guns." + key + ".name", "&f" + id);
            yaml.set("guns." + key + ".model", "gun_" + key);
            yaml.set("guns." + key + ".damage", 4.0);
            yaml.set("guns." + key + ".fire-rate", 2.0);
            yaml.set("guns." + key + ".range", 50);
            yaml.set("guns." + key + ".magazine", 10);
            yaml.set("guns." + key + ".reload-ticks", 30);
            yaml.set("guns." + key + ".sound", "minecraft:entity.firework_rocket.blast");
            yaml.set("guns." + key + ".sound-pitch", 1.5);
            yaml.set("guns." + key + ".effect", "none");
            yaml.set("guns." + key + ".effect-ticks", 60);
            yaml.set("guns." + key + ".effect-level", 1);
            yaml.set("guns." + key + ".ricochet", 0);
            yaml.set("guns." + key + ".spread", 2.0);
            yaml.set("guns." + key + ".aim-spread", 0.6);
            yaml.set("guns." + key + ".speed", 3.0);
            yaml.set("guns." + key + ".curve", 0.05);
            yaml.set("guns." + key + ".fire-modes", "semi");
            yaml.set("guns." + key + ".recoil", 1.0);
            yaml.set("guns." + key + ".mag", "none");
        }
        yaml.save(file);
        load();
        return true;
    }

    /** Delete a gun or mag from guns.yml and the live registry. Returns an error
     *  message, or null on success. A mag some gun still reloads from is protected -
     *  removing it would leave that gun impossible to reload. */
    public String remove(String id) throws IOException {
        String key = id.toLowerCase();
        if (guns.containsKey(key)) {
            yaml.set("guns." + key, null);
            plugin.getLogger().info("Removed gun '" + key + "' from guns.yml.");
        } else if (mags.containsKey(key)) {
            List<String> users = guns.values().stream()
                .filter(g -> key.equals(g.magId())).map(Gun::id).toList();
            if (!users.isEmpty()) {
                return "Mag '" + key + "' is still used by: " + String.join(", ", users)
                    + ". Repoint them first (/guns edit <gun> mag <mag-id|none>).";
            }
            yaml.set("mags." + key, null);
            plugin.getLogger().info("Removed mag '" + key + "' from guns.yml.");
        } else if (grenades.containsKey(key)) {
            return "'" + key + "' is a grenade - only guns and mags can be removed.";
        } else {
            return "Unknown gun/mag: " + id;
        }
        yaml.save(file);
        load();
        return null;
    }

    /** Edit one stat of a gun, grenade or mag. Returns an error message, or null on success. */
    public String edit(String id, String stat, String value) throws IOException {
        String key = id.toLowerCase();
        String statKey = stat.toLowerCase();
        if (guns.containsKey(key)) {
            if (!GUN_EDITABLE.contains(statKey)) {
                return "Unknown gun stat '" + stat + "'. Stats: " + String.join(", ", GUN_EDITABLE);
            }
            String path = "guns." + key + ".";
            switch (statKey) {
                case "mag" -> {
                    String magId = value.toLowerCase();
                    if (!magId.equals("none") && !mags.containsKey(magId)) {
                        return "Unknown mag '" + value + "'. Mags: "
                            + (mags.isEmpty() ? "(none yet - /guns create <id> mag)" : String.join(", ", mags.keySet()))
                            + ", or 'none'.";
                    }
                    yaml.set(path + "mag", magId);
                }
                case "firemodes" -> {
                    // normalise to the offered set; anything invalid -> "semi"
                    java.util.List<String> ok = new java.util.ArrayList<>();
                    for (String m : value.toLowerCase().split(",")) {
                        String t = m.trim();
                        if ((t.equals("semi") || t.equals("auto")) && !ok.contains(t)) ok.add(t);
                    }
                    yaml.set(path + "fire-modes", ok.isEmpty() ? "semi" : String.join(",", ok));
                }
                case "name", "model", "sound", "effect" -> yaml.set(path + yamlKey(statKey), value);
                case "magazine", "reloadticks", "effectticks", "effectlevel", "ricochet", "pierce" -> {
                    Integer n = parseInt(value);
                    if (n == null) return "Not a whole number: " + value;
                    yaml.set(path + yamlKey(statKey), n);
                }
                default -> {
                    Double d = parseDouble(value);
                    if (d == null) return "Not a number: " + value;
                    yaml.set(path + yamlKey(statKey), d);
                }
            }
        } else if (grenades.containsKey(key)) {
            if (!GRENADE_EDITABLE.contains(statKey)) {
                return "Unknown grenade stat '" + stat + "'. Stats: " + String.join(", ", GRENADE_EDITABLE);
            }
            String path = "grenades." + key + ".";
            switch (statKey) {
                case "name", "model" -> yaml.set(path + yamlKey(statKey), value);
                case "fuseticks" -> {
                    Integer n = parseInt(value);
                    if (n == null) return "Not a whole number: " + value;
                    yaml.set(path + "fuse-ticks", n);
                }
                case "breakblocks" -> yaml.set(path + "break-blocks", Boolean.parseBoolean(value));
                default -> {
                    Double d = parseDouble(value);
                    if (d == null) return "Not a number: " + value;
                    yaml.set(path + yamlKey(statKey), d);
                }
            }
        } else if (mags.containsKey(key)) {
            if (!MAG_EDITABLE.contains(statKey)) {
                return "Unknown mag stat '" + stat + "'. Stats: " + String.join(", ", MAG_EDITABLE);
            }
            String path = "mags." + key + ".";
            switch (statKey) {
                case "name", "model" -> yaml.set(path + statKey, value);
                default -> { // capacity
                    Integer n = parseInt(value);
                    if (n == null) return "Not a whole number: " + value;
                    yaml.set(path + "capacity", n);
                }
            }
        } else {
            return "Unknown gun/grenade/mag: " + id;
        }
        yaml.save(file);
        load();
        return null;
    }

    private String yamlKey(String stat) {
        return switch (stat) {
            case "firerate" -> "fire-rate";
            case "reloadticks" -> "reload-ticks";
            case "soundpitch" -> "sound-pitch";
            case "effectticks" -> "effect-ticks";
            case "effectlevel" -> "effect-level";
            case "aimspread" -> "aim-spread";
            case "firemodes" -> "fire-modes";
            case "fuseticks" -> "fuse-ticks";
            case "breakblocks" -> "break-blocks";
            default -> stat;
        };
    }

    private double clamp(String id, String stat, double value, double min, double max) {
        double clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            plugin.getLogger().warning("'" + id + "' " + stat + "=" + value
                + " is out of the safe range " + min + ".." + max + " - using " + clamped
                + " (huge values can stall the server).");
        }
        return clamped;
    }

    private Integer parseInt(String v) {
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
    }

    private Double parseDouble(String v) {
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
    }

    /** Gun item: a crossbow pre-loaded with an arrow -> held in the crossbow AIMING POSE.
     *  Vanilla firing is cancelled by ShootListener; the charged arrow is only for the pose. */
    public ItemStack buildItem(Gun gun) {
        if (gun.isSpyglass()) {
            // a sniper IS a spyglass: right-click scopes with vanilla zoom
            // and the pack's custom sight overlay
            ItemStack item = new ItemStack(Material.SPYGLASS);
            var meta = item.getItemMeta();
            applyCosmetics(meta, gun.name(), gun.model());
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, gun.id());
            meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, gun.magazine());
            meta.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
            item.setItemMeta(meta);
            return item;
        }
        ItemStack item = new ItemStack(Material.CROSSBOW);
        CrossbowMeta meta = (CrossbowMeta) item.getItemMeta();
        meta.addChargedProjectile(new ItemStack(Material.ARROW));   // charged = the aiming pose
        applyCosmetics(meta, gun.name(), gun.model());
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, gun.id());
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, gun.magazine());
        meta.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
        item.setItemMeta(meta);
        return item;
    }

    /** Grenade item: a snowball (throwable by vanilla; the throw is tagged by GrenadeListener). */
    public ItemStack buildGrenadeItem(Grenade grenade) {
        ItemStack item = new ItemStack(Material.SNOWBALL, 4);
        ItemMeta meta = item.getItemMeta();
        applyCosmetics(meta, grenade.name(), grenade.model());
        meta.getPersistentDataContainer().set(grenadeKey, PersistentDataType.STRING, grenade.id());
        item.setItemMeta(meta);
        return item;
    }

    /** Mag item: a prismarine shard reskinned by the resource pack, stacking to 16.
     *  Identity rides the item (PDC); capacity is stamped too but is currently cosmetic -
     *  since 0.6.0 one mag always fills the gun to its own magazine size. Keep the PDC
     *  to id + capacity ONLY: both are identical for every mag of a type, so identical
     *  mags keep stacking with each other (per-item data like UUIDs would break that). */
    public ItemStack buildMagItem(Mag mag) {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = item.getItemMeta();
        applyCosmetics(meta, mag.name(), mag.model());
        meta.setMaxStackSize(16);
        meta.getPersistentDataContainer().set(magKey, PersistentDataType.STRING, mag.id());
        meta.getPersistentDataContainer().set(magCapacityKey, PersistentDataType.INTEGER, mag.capacity());
        item.setItemMeta(meta);
        return item;
    }

    private void applyCosmetics(ItemMeta meta, String name, String model) {
        Component display = LegacyComponentSerializer.legacyAmpersand().deserialize(name)
            .decoration(TextDecoration.ITALIC, false);
        meta.itemName(display);
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
    }

    /** The gun this item is, or null. */
    public Gun gunOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()
            || (item.getType() != Material.CROSSBOW && item.getType() != Material.SPYGLASS)) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return get(id);
    }

    /** The grenade this item is, or null. */
    public Grenade grenadeOf(ItemStack item) {
        if (item == null || item.getType() != Material.SNOWBALL || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(grenadeKey, PersistentDataType.STRING);
        return getGrenade(id);
    }

    /** The mag id stamped on this item, or null if it is not a mag. */
    public String magIdOf(ItemStack item) {
        if (item == null || item.getType() != Material.PRISMARINE_SHARD || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(magKey, PersistentDataType.STRING);
    }

    /** Stable per-instance id for a gun item. New guns get it at build time (no item change
     *  when firing); a legacy gun without one is stamped ONCE here (not during any per-shot
     *  path in practice, since it's read on draw first). */
    private String instanceId(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String uid = meta.getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING);
        if (uid == null) {
            uid = java.util.UUID.randomUUID().toString();
            meta.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, uid);
            item.setItemMeta(meta);   // one-time only; brand-new guns already carry the id
        }
        return uid;
    }

    public int ammoOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        String uid = instanceId(item);
        Integer live = liveAmmo.get(uid);
        if (live != null) return live;
        // first time we've seen this instance: seed from the stamped magazine value
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.INTEGER);
        int v = stored == null ? 0 : stored;
        liveAmmo.put(uid, v);
        return v;
    }

    /** Set ammo in RAM ONLY - the held item is never rewritten, so a shot never re-equips
     *  the gun on screen (the up/down bob). */
    public void setAmmo(ItemStack item, int ammo) {
        if (item == null || !item.hasItemMeta()) return;
        liveAmmo.put(instanceId(item), Math.max(0, ammo));
    }
}
