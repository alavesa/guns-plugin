package fi.alavesa.guns;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

/**
 * One ballistic armour variant, defined in config (the {@code armor:} section) so ops can make as
 * many as they like - helmets, vests, leggings, boots - and tune each one's toughness (tier) and
 * bullet absorption (absorbHits) without a code change. A piece protects the body region of its
 * slot: a helmet stops head shots, a vest body shots, leggings leg shots, boots foot shots.
 *
 * Rendered as a dyed leather piece carrying a {@code custom_model_data} string ({@link #model}) so a
 * resource pack can give it a bespoke texture later; until then the dye colour tells the tiers apart.
 */
public final class ArmorType {

    public final String id;
    public final String display;
    public final EquipmentSlot slot;
    public final int tier;         // toughness / pierce rating (1 = light .. 5 = heaviest)
    public final int absorbHits;   // bullets it soaks before it breaks
    public final int slowness;     // -1 = none, 0 = Slowness I, 1 = Slowness II ...
    public final boolean speedBoost;
    public final Color dye;
    public final NamedTextColor nameColor;
    public final String model;     // custom_model_data string; broken variant is model + "_broken"

    public ArmorType(String id, String display, EquipmentSlot slot, int tier, int absorbHits,
                     int slowness, boolean speedBoost, Color dye, NamedTextColor nameColor, String model) {
        this.id = id;
        this.display = display;
        this.slot = slot;
        this.tier = tier;
        this.absorbHits = Math.max(1, absorbHits);
        this.slowness = slowness;
        this.speedBoost = speedBoost;
        this.dye = dye;
        this.nameColor = nameColor;
        this.model = model;
    }

    /** The leather base item for this slot. */
    public Material baseMaterial() {
        return switch (slot) {
            case HEAD -> Material.LEATHER_HELMET;
            case LEGS -> Material.LEATHER_LEGGINGS;
            case FEET -> Material.LEATHER_BOOTS;
            default -> Material.LEATHER_CHESTPLATE;
        };
    }

    public String brokenModel() { return model + "_broken"; }

    /** The body region this piece guards, for lore/messages. */
    public String region() {
        return switch (slot) {
            case HEAD -> "the head";
            case LEGS -> "the legs";
            case FEET -> "the feet";
            default -> "the body";
        };
    }

    /** Parse a config slot word (helmet/head, chest/chestplate, legs/leggings, boots/feet). */
    public static EquipmentSlot parseSlot(String s) {
        if (s == null) return EquipmentSlot.CHEST;
        return switch (s.toLowerCase()) {
            case "helmet", "head" -> EquipmentSlot.HEAD;
            case "legs", "leggings", "pants" -> EquipmentSlot.LEGS;
            case "boots", "feet" -> EquipmentSlot.FEET;
            default -> EquipmentSlot.CHEST;
        };
    }
}
