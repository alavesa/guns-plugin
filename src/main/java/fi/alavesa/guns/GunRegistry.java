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

/** Loads, saves and edits guns.yml (guns AND grenades), and builds the actual items. */
public final class GunRegistry {

    public static final Set<String> GUN_EDITABLE = Set.of(
        "name", "model", "damage", "firerate", "range", "magazine", "reloadticks",
        "sound", "soundpitch", "backstab", "effect", "effectticks", "effectlevel", "ricochet");

    public static final Set<String> GRENADE_EDITABLE = Set.of(
        "name", "model", "power", "fuseticks", "velocity", "breakblocks");

    private final Plugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey grenadeKey;
    private final NamespacedKey ammoKey;
    private final Map<String, Gun> guns = new LinkedHashMap<>();
    private final Map<String, Grenade> grenades = new LinkedHashMap<>();
    private File file;
    private YamlConfiguration yaml;

    public GunRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "id");
        this.grenadeKey = new NamespacedKey(plugin, "grenade");
        this.ammoKey = new NamespacedKey(plugin, "ammo");
    }

    public NamespacedKey grenadeKey() { return grenadeKey; }

    public void load() {
        file = new File(plugin.getDataFolder(), "guns.yml");
        if (!file.exists()) plugin.saveResource("guns.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
        guns.clear();
        grenades.clear();
        ConfigurationSection root = yaml.getConfigurationSection("guns");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
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
                    clamp(id, "backstab", s.getDouble("backstab", 1.0), 1, 10),
                    s.getString("effect", "none"),
                    (int) clamp(id, "effect-ticks", s.getInt("effect-ticks", 60), 0, 1200),
                    (int) clamp(id, "effect-level", s.getInt("effect-level", 1), 1, 10),
                    (int) clamp(id, "ricochet", s.getInt("ricochet", 0), 0, 8)
                ));
            }
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

    public Gun get(String id) { return id == null ? null : guns.get(id.toLowerCase()); }
    public Grenade getGrenade(String id) { return id == null ? null : grenades.get(id.toLowerCase()); }
    public Set<String> ids() { return guns.keySet(); }
    public Set<String> grenadeIds() { return grenades.keySet(); }

    /** Create a gun or grenade with defaults. Returns false if the id exists in either list. */
    public boolean create(String id, boolean grenade) throws IOException {
        String key = id.toLowerCase();
        if (guns.containsKey(key) || grenades.containsKey(key)) return false;
        if (grenade) {
            yaml.set("grenades." + key + ".name", "&f" + id);
            yaml.set("grenades." + key + ".model", "grenade_" + key);
            yaml.set("grenades." + key + ".power", 2.5);
            yaml.set("grenades." + key + ".fuse-ticks", 25);
            yaml.set("grenades." + key + ".velocity", 1.5);
            yaml.set("grenades." + key + ".break-blocks", false);
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
            yaml.set("guns." + key + ".backstab", 1.0);
            yaml.set("guns." + key + ".effect", "none");
            yaml.set("guns." + key + ".effect-ticks", 60);
            yaml.set("guns." + key + ".effect-level", 1);
            yaml.set("guns." + key + ".ricochet", 0);
        }
        yaml.save(file);
        load();
        return true;
    }

    /** Edit one stat of a gun or grenade. Returns an error message, or null on success. */
    public String edit(String id, String stat, String value) throws IOException {
        String key = id.toLowerCase();
        String statKey = stat.toLowerCase();
        if (guns.containsKey(key)) {
            if (!GUN_EDITABLE.contains(statKey)) {
                return "Unknown gun stat '" + stat + "'. Stats: " + String.join(", ", GUN_EDITABLE);
            }
            String path = "guns." + key + ".";
            switch (statKey) {
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
        } else {
            return "Unknown gun/grenade: " + id;
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
        if (item == null || item.getType() != Material.CROSSBOW || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return get(id);
    }

    /** The grenade this item is, or null. */
    public Grenade grenadeOf(ItemStack item) {
        if (item == null || item.getType() != Material.SNOWBALL || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(grenadeKey, PersistentDataType.STRING);
        return getGrenade(id);
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
