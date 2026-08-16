package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
        "speed", "curve", "spread", "aimspread", "firemodes", "recoil", "pierce",
        "hrecoil", "ricochetangle", "casingdir", "casingpos", "pellets", "bulletmodel");

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
    private final NamespacedKey vestHitsKey;
    private final NamespacedKey vestBrokenKey;
    private final NamespacedKey armorIdKey;
    private final NamespacedKey attachIdKey = new NamespacedKey("guns", "attachment_id");   // on an attachment item
    private final NamespacedKey gunAttachKey = new NamespacedKey("guns", "gun_attachments"); // CSV of ids on a gun
    /** Config-defined attachments by id. */
    private final Map<String, Attachment> attachments = new LinkedHashMap<>();
    /** Config-defined armour variants (any slot), by id. */
    private final java.util.LinkedHashMap<String, ArmorType> armor = new java.util.LinkedHashMap<>();
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
        this.vestHitsKey = new NamespacedKey(plugin, "vest_hits");
        this.vestBrokenKey = new NamespacedKey(plugin, "vest_broken");
        this.armorIdKey = new NamespacedKey(plugin, "armor_id");
        loadArmor();
    }

    // ============================================================ armour registry

    /**
     * Build the armour registry. The five chestplate vests are FIXED built-ins (never config-driven,
     * so they always exist even with a stale config), alongside default helmet/leggings/boots
     * variants. The config's {@code armor:} section then ADDS or tunes NON-chest variants only -
     * chestplate entries in config are ignored, because vests are fixed.
     */
    public void loadArmor() {
        armor.clear();
        for (ArmorType t : builtinArmor()) armor.put(t.id, t);   // 5 vests + default helmets/legs/boots
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig().getConfigurationSection("armor");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection e = sec.getConfigurationSection(id);
                if (e == null) continue;
                org.bukkit.inventory.EquipmentSlot slot = ArmorType.parseSlot(e.getString("slot", "helmet"));
                if (slot == org.bukkit.inventory.EquipmentSlot.CHEST) {
                    plugin.getLogger().warning("Ignoring config armour '" + id
                        + "': chestplates are fixed - custom variants are only for helmet/leggings/boots.");
                    continue;
                }
                String display = e.getString("display", id);
                int tier = e.getInt("tier", 1);
                int absorb = e.getInt("absorb-hits", 1);
                double speedMod = e.getDouble("speed-mod", 0.0);
                org.bukkit.Color dye = parseDye(e.getString("dye", "170,170,180"));
                net.kyori.adventure.text.format.NamedTextColor color = parseColor(e.getString("color", "gray"));
                String model = e.getString("model", "armor_" + id);
                int insulation = e.getInt("insulation", 0);
                armor.put(id, new ArmorType(id, display, slot, tier, absorb, speedMod, dye, color, model)
                    .withInsulation(insulation));
            }
        }
        // Runtime speed-modifier overrides (set by /guns armor slowness) win for ANY piece - chest
        // included - so even the fixed vests' weight can be retuned by command.
        applyOverride("armor-speed", (t, v) -> t.withSpeedMod(v.doubleValue()));
        applyOverride("armor-insulation", (t, v) -> t.withInsulation(v.intValue()));
        plugin.getLogger().info("Armour: " + armor.size() + " variants (5 fixed vests + "
            + (armor.size() - 5) + " helmet/leggings/boots).");
    }

    /** Apply a config override section ({@code armor-speed} / {@code armor-insulation}) to any piece. */
    private void applyOverride(String section, java.util.function.BiFunction<ArmorType, Number, ArmorType> apply) {
        org.bukkit.configuration.ConfigurationSection ov = plugin.getConfig().getConfigurationSection(section);
        if (ov == null) return;
        for (String id : ov.getKeys(false)) {
            ArmorType t = armor.get(id);
            Object v = ov.get(id);
            if (t != null && v instanceof Number n) armor.put(id, apply.apply(t, n));
        }
    }

    /** The always-present variants: the 5 fixed chestplate vests + default non-chest pieces. The
     *  heavier pieces carry some thermal insulation (they resist catching fire, at a durability cost). */
    private java.util.List<ArmorType> builtinArmor() {
        var HEAD = org.bukkit.inventory.EquipmentSlot.HEAD;
        var CHEST = org.bukkit.inventory.EquipmentSlot.CHEST;
        var LEGS = org.bukkit.inventory.EquipmentSlot.LEGS;
        var FEET = org.bukkit.inventory.EquipmentSlot.FEET;
        var GRAY = net.kyori.adventure.text.format.NamedTextColor.GRAY;
        var DARK = net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY;
        return java.util.List.of(
            // --- the five FIXED chestplate vests --- (last number = speed modifier fraction)
            new ArmorType("ultra_light", "Ultra Light Ballistic Vest", CHEST, 1, 1,  0.05, org.bukkit.Color.fromRGB(224, 224, 228), GRAY, "vest_ultra_light"),
            new ArmorType("light",       "Light Ballistic Vest",       CHEST, 2, 1,  0.00, org.bukkit.Color.fromRGB(188, 188, 194), GRAY, "vest_light"),
            new ArmorType("ballistic",   "Ballistic Vest",             CHEST, 3, 2, -0.05, org.bukkit.Color.fromRGB(150, 150, 156), GRAY, "vest_ballistic").withInsulation(1),
            new ArmorType("heavy",       "Heavy Ballistic Vest",       CHEST, 4, 3, -0.10, org.bukkit.Color.fromRGB(96,  96,  102), DARK, "vest_heavy").withInsulation(2),
            new ArmorType("ultra_heavy", "Ultra Heavy Ballistic Vest", CHEST, 5, 4, -0.15, org.bukkit.Color.fromRGB(60,  60,  66),  DARK, "vest_ultra_heavy").withInsulation(3),
            // --- default helmets / leggings / boots (config can tune or add more) ---
            new ArmorType("light_helmet",   "Light Ballistic Helmet",  HEAD, 2, 1,  0.00, org.bukkit.Color.fromRGB(188, 188, 194), GRAY, "helmet_light"),
            new ArmorType("combat_helmet",  "Combat Helmet",           HEAD, 3, 2, -0.02, org.bukkit.Color.fromRGB(120, 124, 110), GRAY, "helmet_combat"),
            new ArmorType("heavy_helmet",   "Heavy Ballistic Helmet",  HEAD, 4, 2, -0.05, org.bukkit.Color.fromRGB(96,  96,  102), DARK, "helmet_heavy").withInsulation(1),
            new ArmorType("combat_leggings","Combat Leggings",         LEGS, 3, 2, -0.05, org.bukkit.Color.fromRGB(120, 124, 110), GRAY, "leggings_combat"),
            new ArmorType("heavy_leggings", "Heavy Ballistic Leggings",LEGS, 4, 2, -0.08, org.bukkit.Color.fromRGB(96,  96,  102), DARK, "leggings_heavy").withInsulation(1),
            new ArmorType("combat_boots",   "Combat Boots",            FEET, 3, 1, -0.02, org.bukkit.Color.fromRGB(120, 124, 110), GRAY, "boots_combat"),
            new ArmorType("heavy_boots",    "Heavy Ballistic Boots",   FEET, 4, 2, -0.05, org.bukkit.Color.fromRGB(96,  96,  102), DARK, "boots_heavy").withInsulation(1));
    }

    public ArmorType armorType(String id) { return id == null ? null : armor.get(id); }
    public java.util.Collection<ArmorType> armorTypes() { return armor.values(); }

    /** The armour variant an item represents, or null if it isn't ballistic armour. Recognises new
     *  armor_id items and, for backward-compatibility, old vests tagged only with vest_tier. */
    public ArmorType armorType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(armorIdKey, PersistentDataType.STRING);
        if (id != null) return armor.get(id);
        int tier = pdc.getOrDefault(vestTierKey, PersistentDataType.INTEGER, 0);
        if (tier >= 1) {   // legacy vest: map its tier to the default chest variant of that tier
            for (ArmorType a : armor.values()) {
                if (a.slot == org.bukkit.inventory.EquipmentSlot.CHEST && a.tier == tier) return a;
            }
        }
        return null;
    }

    private org.bukkit.Color parseDye(String s) {
        try {
            String[] p = s.split(",");
            return org.bukkit.Color.fromRGB(Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (Exception e) { return org.bukkit.Color.fromRGB(170, 170, 180); }
    }

    private net.kyori.adventure.text.format.NamedTextColor parseColor(String s) {
        net.kyori.adventure.text.format.NamedTextColor c =
            net.kyori.adventure.text.format.NamedTextColor.NAMES.value(s == null ? "gray" : s.toLowerCase());
        return c != null ? c : net.kyori.adventure.text.format.NamedTextColor.GRAY;
    }

    /** How many bullets this vest has already absorbed (0 for a fresh one). */
    public int vestHits(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(vestHitsKey, PersistentDataType.INTEGER, 0);
    }

    /** Record the vest's absorbed-bullet count (caller re-equips it). */
    public void setVestHits(ItemStack item, int hits) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(vestHitsKey, PersistentDataType.INTEGER, Math.max(0, hits));
        item.setItemMeta(meta);
    }

    /** The cosmetic health-bar range an armour piece fills against (the break is PDC-driven, not this). */
    public static final int ARMOR_BAR_MAX = 100;

    /** Set the absorbed-round count AND the cosmetic durability bar (proportional to hits/absorbHits)
     *  in one write, so the bar shrinks with each hit without the game destroying the piece. */
    public void setArmorHits(ItemStack item, int hits, int absorbHits) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(vestHitsKey, PersistentDataType.INTEGER, Math.max(0, hits));
        if (meta instanceof org.bukkit.inventory.meta.Damageable dm) {
            int barMax = dm.hasMaxDamage() ? dm.getMaxDamage() : ARMOR_BAR_MAX;
            int d = absorbHits <= 0 ? 0 : (int) Math.round((double) hits / absorbHits * barMax);
            dm.setDamage(Math.max(0, Math.min(barMax, d)));
        }
        item.setItemMeta(meta);
    }

    /** Build an armour piece for its slot: a dyed leather item carrying its variant id and a fresh
     *  (zero) absorbed-round count. */
    public ItemStack buildArmor(ArmorType t) {
        ItemStack item = new ItemStack(t.baseMaterial());
        org.bukkit.inventory.meta.LeatherArmorMeta meta =
            (org.bukkit.inventory.meta.LeatherArmorMeta) item.getItemMeta();
        meta.setColor(t.dye);
        meta.itemName(Component.text(t.display, t.nameColor).decoration(TextDecoration.ITALIC, false));
        java.util.List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("Guards " + t.region() + " - soaks " + t.absorbHits + " round(s).",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Toughness tier " + t.tier + ".",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        if (t.speedMod != 0) lore.add(Component.text("Weight: " + t.speedLabel() + " (stacks).",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        if (t.insulation > 0) lore.add(Component.text("Thermal insulation " + t.insulation
            + " - resists fire (wears the piece).",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        // #2 dynamic health bar: a large COSMETIC max_damage the bar fills against. The break is driven
        // by the vest_hits PDC counter (below), NOT by the durability reaching 0 - so the game can't
        // destroy the piece out from under us before it hands over the broken variant.
        ((org.bukkit.inventory.meta.Damageable) meta).setMaxDamage(ARMOR_BAR_MAX);
        ((org.bukkit.inventory.meta.Damageable) meta).setDamage(0);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(t.model));
        meta.setCustomModelDataComponent(cmd);
        // Weight as a MOVEMENT_SPEED attribute modifier baked on the item (applies only in its slot),
        // so several worn pieces STACK - which potion effects can't. MULTIPLY_SCALAR_1 = a fraction.
        if (t.speedMod != 0) {
            org.bukkit.NamespacedKey modKey = new org.bukkit.NamespacedKey(plugin, "armor_speed_" + t.id);
            meta.addAttributeModifier(org.bukkit.attribute.Attribute.MOVEMENT_SPEED,
                new org.bukkit.attribute.AttributeModifier(modKey, t.speedMod,
                    org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1, t.slotGroup()));
        }
        meta.getPersistentDataContainer().set(armorIdKey, PersistentDataType.STRING, t.id);
        meta.getPersistentDataContainer().set(vestHitsKey, PersistentDataType.INTEGER, 0);
        if (t.slot == org.bukkit.inventory.EquipmentSlot.CHEST)
            meta.getPersistentDataContainer().set(vestTierKey, PersistentDataType.INTEGER, t.tier);
        item.setItemMeta(meta);
        return item;
    }

    /** Build the BROKEN variant - the wreck left after an armour piece's durability runs out. It's a
     *  distinct, UNWEARABLE item (a non-armour base, so it can't be equipped at all - #3), named
     *  "Broken ...", with its own custom_model_data + a vest_broken PDC tag. Repair it in SCP-914
     *  (recipe-book side). */
    public ItemStack buildBrokenArmor(ArmorType t) {
        ItemStack item = new ItemStack(Material.LEATHER);   // NOT armour -> can't be worn
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Broken " + t.display,
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(Component.text("Wrecked - can't be worn. Repair it in SCP-914.",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(t.brokenModel()));
        meta.setCustomModelDataComponent(cmd);
        // Records which piece it was (tier + id) for clarity / 914; no armor_id so it gives no protection.
        meta.getPersistentDataContainer().set(vestBrokenKey, PersistentDataType.INTEGER, t.tier);
        meta.getPersistentDataContainer().set(armorIdKey, PersistentDataType.STRING, "broken_" + t.id);
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

    // ============================================================ attachments

    /** Seed default example attachments once, then load the attachments.<id> section. */
    private void loadAttachments() {
        attachments.clear();
        if (yaml.getConfigurationSection("attachments") == null && !yaml.getBoolean("attachments-offered", false)) {
            yaml.set("attachments.scope.name", "&bTactical Scope");
            yaml.set("attachments.scope.item-model", "att_scope");
            yaml.set("attachments.scope.gun-suffix", "scope");
            yaml.set("attachments.scope.spread-mult", 0.5);
            yaml.set("attachments.scope.recoil-mult", 1.1);
            yaml.set("attachments.scope.lore", "&a+Accuracy  &c-a touch more kick");
            yaml.set("attachments.grip.name", "&7Foregrip");
            yaml.set("attachments.grip.item-model", "att_grip");
            yaml.set("attachments.grip.gun-suffix", "grip");
            yaml.set("attachments.grip.recoil-mult", 0.6);
            yaml.set("attachments.grip.lore", "&a-40% recoil");
            yaml.set("attachments.heavybarrel.name", "&8Heavy Barrel");
            yaml.set("attachments.heavybarrel.item-model", "att_heavybarrel");
            yaml.set("attachments.heavybarrel.gun-suffix", "heavybarrel");
            yaml.set("attachments.heavybarrel.damage-mult", 1.25);
            yaml.set("attachments.heavybarrel.recoil-mult", 1.2);
            yaml.set("attachments.heavybarrel.lore", "&a+25% damage  &c-more recoil");
            yaml.set("attachments-offered", true);
            try { yaml.save(file); } catch (java.io.IOException e) {
                plugin.getLogger().severe("Could not save guns.yml: " + e.getMessage());
            }
        }
        ConfigurationSection aroot = yaml.getConfigurationSection("attachments");
        if (aroot == null) return;
        for (String id : aroot.getKeys(false)) {
            ConfigurationSection s = aroot.getConfigurationSection(id);
            if (s == null) continue;
            attachments.put(id.toLowerCase(), new Attachment(
                id.toLowerCase(),
                s.getString("name", id),
                s.getString("item-model", "att_" + id),
                s.getString("gun-suffix", id.toLowerCase()),
                clamp(id, "recoil-mult", s.getDouble("recoil-mult", 1.0), 0, 5),
                clamp(id, "spread-mult", s.getDouble("spread-mult", 1.0), 0, 5),
                clamp(id, "damage-mult", s.getDouble("damage-mult", 1.0), 0, 5),
                s.getString("lore", "")));
        }
    }

    public Map<String, Attachment> attachments() { return attachments; }
    public Attachment attachment(String id) { return id == null ? null : attachments.get(id.toLowerCase()); }

    public boolean isAttachment(ItemStack i) {
        return i != null && i.hasItemMeta()
            && i.getItemMeta().getPersistentDataContainer().has(attachIdKey, PersistentDataType.STRING);
    }
    public String attachmentId(ItemStack i) {
        return isAttachment(i) ? i.getItemMeta().getPersistentDataContainer().get(attachIdKey, PersistentDataType.STRING) : null;
    }

    /** The attachment item you hold and then apply to a gun. */
    public ItemStack buildAttachment(String id) {
        Attachment a = attachment(id);
        if (a == null) return null;
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        applyCosmetics(meta, a.name(), a.itemModel());
        if (!a.lore().isEmpty()) meta.lore(List.of(
            LegacyComponentSerializer.legacyAmpersand().deserialize(a.lore()).decoration(TextDecoration.ITALIC, false),
            Component.text("Hold a gun + /guns attach " + a.id(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(attachIdKey, PersistentDataType.STRING, a.id());
        it.setItemMeta(meta);
        return it;
    }

    public java.util.List<String> gunAttachments(ItemStack gun) {
        if (gun == null || !gun.hasItemMeta()) return java.util.List.of();
        String csv = gun.getItemMeta().getPersistentDataContainer().getOrDefault(gunAttachKey, PersistentDataType.STRING, "");
        return csv.isEmpty() ? java.util.List.of() : java.util.Arrays.asList(csv.split(","));
    }
    private void setGunAttachments(ItemStack gun, java.util.List<String> list) {
        ItemMeta meta = gun.getItemMeta();
        meta.getPersistentDataContainer().set(gunAttachKey, PersistentDataType.STRING, String.join(",", list));
        gun.setItemMeta(meta);
    }
    public boolean attachToGun(ItemStack gun, String attId) {
        Attachment a = attachment(attId);
        if (a == null) return false;
        var list = new java.util.ArrayList<>(gunAttachments(gun));
        if (list.contains(a.id())) return false;
        list.add(a.id());
        setGunAttachments(gun, list);
        return true;
    }
    public boolean detachFromGun(ItemStack gun, String attId) {
        var list = new java.util.ArrayList<>(gunAttachments(gun));
        if (!list.remove(attId == null ? "" : attId.toLowerCase())) return false;
        setGunAttachments(gun, list);
        return true;
    }
    private double mult(ItemStack gun, java.util.function.ToDoubleFunction<Attachment> f) {
        double m = 1.0;
        for (String id : gunAttachments(gun)) { Attachment a = attachment(id); if (a != null) m *= f.applyAsDouble(a); }
        return m;
    }
    public double attachRecoilMult(ItemStack gun) { return mult(gun, Attachment::recoilMult); }
    public double attachSpreadMult(ItemStack gun) { return mult(gun, Attachment::spreadMult); }
    public double attachDamageMult(ItemStack gun) { return mult(gun, Attachment::damageMult); }

    /** The gun's base model state string (index 0 of custom_model_data). Attachments do NOT swap the
     *  whole gun model - they're carried as EXTRA custom_model_data strings (see {@link #modelStrings})
     *  so the pack can composite an attachment overlay ON the base model. */
    public String effectiveModel(ItemStack gun, Gun g) {
        return g.model();
    }

    /** The full custom_model_data string list for a gun: [base-state, attachment ids...]. A composite
     *  item model renders the base gun (index 0) plus an overlay per attachment (index 1+). */
    public List<String> modelStrings(ItemStack gun, String stateModel) {
        List<String> out = new java.util.ArrayList<>();
        out.add(stateModel);
        out.addAll(gunAttachments(gun));   // extra strings = attachment overlays, added not swapped
        return out;
    }

    /** Re-point a held gun's model to the base + its attachment overlay strings (normal, un-aimed). */
    public void refreshGunModel(ItemStack gun, Gun g) {
        ItemMeta meta = gun.getItemMeta();
        if (meta == null) return;
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(modelStrings(gun, g.model()));
        meta.setCustomModelDataComponent(cmd);
        gun.setItemMeta(meta);
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "guns.yml");
        if (!file.exists()) plugin.saveResource("guns.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
        migrate();
        guns.clear();
        grenades.clear();
        mags.clear();
        loadAttachments();
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
                    (int) clamp(id, "pierce", s.getInt("pierce", 2), 0, 5),
                    clamp(id, "h-recoil", s.getDouble("h-recoil", 0.0), 0, 30),
                    clamp(id, "ricochet-angle", s.getDouble("ricochet-angle", 35.0), 0, 90),
                    s.getString("casing-dir", "1,0.6,-0.1"),
                    s.getString("casing-pos", "0.3,-0.2,0.35"),
                    (int) clamp(id, "pellets", s.getInt("pellets", 1), 1, 20),
                    s.getString("bullet-model", "")
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
        // v0.30: a default combat SHOTGUN - fires 8 pellets in a wide, short-range spread.
        if (yaml.getConfigurationSection("guns") != null
            && yaml.getConfigurationSection("guns.shotgun") == null
            && !yaml.getBoolean("shotgun-offered", false)) {
            yaml.set("guns.shotgun.name", "&cCombat Shotgun");
            yaml.set("guns.shotgun.model", "gun_shotgun");
            yaml.set("guns.shotgun.damage", 3.0);
            yaml.set("guns.shotgun.pellets", 8);
            yaml.set("guns.shotgun.spread", 6.0);
            yaml.set("guns.shotgun.aim-spread", 4.0);
            yaml.set("guns.shotgun.fire-rate", 1.0);
            yaml.set("guns.shotgun.range", 25);
            yaml.set("guns.shotgun.magazine", 6);
            yaml.set("guns.shotgun.reload-ticks", 60);
            yaml.set("guns.shotgun.sound", "minecraft:entity.generic.explode");
            yaml.set("guns.shotgun.sound-pitch", 1.2);
            yaml.set("guns.shotgun.speed", 2.5);
            yaml.set("guns.shotgun.mag", "shells_shotgun");
            yaml.set("shotgun-offered", true);
            try {
                yaml.save(file);
            } catch (java.io.IOException e) {
                plugin.getLogger().severe("Could not save guns.yml: " + e.getMessage());
            }
            plugin.getLogger().info("Added the default combat shotgun to guns.yml (delete it or set shotgun-offered if unwanted).");
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
                case "casingdir", "casingpos" -> {
                    String key3 = statKey.equals("casingdir") ? "casing-dir" : "casing-pos";
                    String v = value.trim();
                    if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("none")) {
                        yaml.set(path + key3, "off");
                    } else {
                        String[] p = v.split("[ ,]+");
                        if (p.length != 3) return "Give three numbers: <right> <up> <forward> (or 'off').";
                        for (String s2 : p) if (parseDouble(s2) == null) return "Not a number: " + s2;
                        yaml.set(path + key3, p[0] + "," + p[1] + "," + p[2]);
                    }
                }
                default -> {   // hrecoil, ricochetangle, and the other doubles
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
            case "hrecoil" -> "h-recoil";
            case "ricochetangle" -> "ricochet-angle";
            case "bulletmodel" -> "bullet-model";
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
            return noSwing(item);
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
        // (attack_speed is applied DYNAMICALLY to the player while a gun is held - see GunsPlugin - so it
        //  works on every gun immediately, no re-give needed.)
        item.setItemMeta(meta);
        return noSwing(item);
    }

    /** Bakes the vanilla `minecraft:swing_animation={type:"none"}` component onto the gun. On MC 1.21.11
     *  through 26.3 Snapshot 6 this REMOVES the left-click arm swing entirely - the shooter's own first-person
     *  view AND everyone else's - because it's client-honored item data (the one no-mod lever that beats
     *  client prediction). On 26.3 Snapshot 7+ Mojang removed the "none" type (items migrate to "whack"), so
     *  it silently has no effect there; on older builds the call throws and is ignored. Applied via
     *  UnsafeValues because paper-api 1.21.4 (compile target) predates the component; the server parses it. */
    private ItemStack noSwing(ItemStack item) {
        try {
            ItemStack modified = org.bukkit.Bukkit.getUnsafe().modifyItemStack(item,
                item.getType().getKey() + "[minecraft:swing_animation={type:\"none\"}]");
            return modified != null ? modified : item;
        } catch (Throwable t) {
            return item;
        }
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
