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
        "sound", "soundpitch", "effect", "effectticks", "effectlevel", "ricochet", "mag");

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
                    clamp(id, "drop", s.getDouble("drop", 0.03), 0, 1)
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
                case "name", "model", "sound", "effect" -> yaml.set(path + yamlKey(statKey), value);
                case "magazine", "reloadticks", "effectticks", "effectlevel", "ricochet" -> {
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
            item.setItemMeta(meta);
            return item;
        }
        ItemStack item = new ItemStack(Material.CROSSBOW);
        CrossbowMeta meta = (CrossbowMeta) item.getItemMeta();
        meta.addChargedProjectile(new ItemStack(Material.ARROW));
        applyCosmetics(meta, gun.name(), gun.model());
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, gun.id());
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, gun.magazine());
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

    public int ammoOf(ItemStack item) {
        Integer ammo = item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.INTEGER);
        return ammo == null ? 0 : ammo;
    }

    public void setAmmo(ItemStack item, int ammo) {
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, ammo);
        item.setItemMeta(meta);
    }
}
