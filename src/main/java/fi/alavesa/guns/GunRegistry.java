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
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads, saves and edits guns.yml, and builds the actual gun items. */
public final class GunRegistry {

    /** Stats editable via /guns edit, with their guns.yml paths. */
    public static final Set<String> EDITABLE =
        Set.of("name", "model", "damage", "firerate", "range", "magazine", "reloadticks", "sound", "soundpitch");

    private final Plugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey ammoKey;
    private final Map<String, Gun> guns = new LinkedHashMap<>();
    private File file;
    private YamlConfiguration yaml;

    public GunRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "id");
        this.ammoKey = new NamespacedKey(plugin, "ammo");
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "guns.yml");
        if (!file.exists()) plugin.saveResource("guns.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
        guns.clear();
        ConfigurationSection root = yaml.getConfigurationSection("guns");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            guns.put(id.toLowerCase(), new Gun(
                id.toLowerCase(),
                s.getString("name", id),
                s.getString("model", "gun_" + id),
                s.getDouble("damage", 4.0),
                s.getDouble("fire-rate", 2.0),
                s.getDouble("range", 50),
                s.getInt("magazine", 10),
                s.getInt("reload-ticks", 30),
                s.getString("sound", "minecraft:entity.firework_rocket.blast"),
                (float) s.getDouble("sound-pitch", 1.5)
            ));
        }
    }

    public Gun get(String id) { return id == null ? null : guns.get(id.toLowerCase()); }
    public Set<String> ids() { return guns.keySet(); }

    /** Create a new gun with defaults and persist it. Returns null if the id already exists. */
    public Gun create(String id) throws IOException {
        String key = id.toLowerCase();
        if (guns.containsKey(key)) return null;
        yaml.set("guns." + key + ".name", "&f" + id);
        yaml.set("guns." + key + ".model", "gun_" + key);
        yaml.set("guns." + key + ".damage", 4.0);
        yaml.set("guns." + key + ".fire-rate", 2.0);
        yaml.set("guns." + key + ".range", 50);
        yaml.set("guns." + key + ".magazine", 10);
        yaml.set("guns." + key + ".reload-ticks", 30);
        yaml.set("guns." + key + ".sound", "minecraft:entity.firework_rocket.blast");
        yaml.set("guns." + key + ".sound-pitch", 1.5);
        yaml.save(file);
        load();
        return get(key);
    }

    /** Edit one stat and persist. Returns an error message, or null on success. */
    public String edit(String id, String stat, String value) throws IOException {
        Gun gun = get(id);
        if (gun == null) return "Unknown gun: " + id;
        String path = "guns." + gun.id() + ".";
        switch (stat.toLowerCase()) {
            case "name" -> yaml.set(path + "name", value);
            case "model" -> yaml.set(path + "model", value);
            case "sound" -> yaml.set(path + "sound", value);
            case "damage", "firerate", "range", "soundpitch" -> {
                double d;
                try { d = Double.parseDouble(value); } catch (NumberFormatException e) { return "Not a number: " + value; }
                yaml.set(path + switch (stat.toLowerCase()) {
                    case "damage" -> "damage";
                    case "firerate" -> "fire-rate";
                    case "range" -> "range";
                    default -> "sound-pitch";
                }, d);
            }
            case "magazine", "reloadticks" -> {
                int n;
                try { n = Integer.parseInt(value); } catch (NumberFormatException e) { return "Not a whole number: " + value; }
                yaml.set(path + (stat.equalsIgnoreCase("magazine") ? "magazine" : "reload-ticks"), n);
            }
            default -> { return "Unknown stat '" + stat + "'. Stats: " + String.join(", ", EDITABLE); }
        }
        yaml.save(file);
        load();
        return null;
    }

    /**
     * Build the gun item: a crossbow pre-loaded with an arrow, which makes the player hold
     * it in the crossbow AIMING POSE. Vanilla firing is cancelled by ShootListener; the
     * charged arrow is purely for the pose.
     */
    public ItemStack buildItem(Gun gun) {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        CrossbowMeta meta = (CrossbowMeta) item.getItemMeta();
        meta.addChargedProjectile(new ItemStack(Material.ARROW));
        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(gun.name())
            .decoration(TextDecoration.ITALIC, false);
        meta.itemName(name);
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(gun.model()));
        meta.setCustomModelDataComponent(cmd);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, gun.id());
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, gun.magazine());
        item.setItemMeta(meta);
        return item;
    }

    /** The gun this item is, or null if it isn't one. */
    public Gun gunOf(ItemStack item) {
        if (item == null || item.getType() != Material.CROSSBOW || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return get(id);
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
