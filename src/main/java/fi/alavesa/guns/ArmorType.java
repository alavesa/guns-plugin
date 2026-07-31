package fi.alavesa.guns;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * One ballistic armour variant, defined in config (helmet/leggings/boots) or built in (the five
 * fixed vests). A piece protects the body region of its slot - a helmet stops head shots, a vest
 * body shots, leggings leg shots, boots foot shots - soaking {@link #absorbHits} rounds before it
 * breaks.
 *
 * Its weight is a MOVEMENT-SPEED ATTRIBUTE MODIFIER baked onto the item ({@link #speedMod}, a
 * MULTIPLY_SCALAR_1 fraction: -0.05 = -5% walk speed, +0.05 = +5%), NOT a potion effect - so several
 * worn pieces STACK, which potion effects don't. Change it per piece with /guns armor slowness.
 */
public final class ArmorType {

    public final String id;
    public final String display;
    public final EquipmentSlot slot;
    public final int tier;         // toughness / pierce rating (1 light .. 5 heaviest)
    public final int absorbHits;   // bullets it soaks before it breaks
    public final double speedMod;  // walk-speed delta as a fraction (-0.05 = -5%, +0.05 = +5%)
    public final Color dye;
    public final NamedTextColor nameColor;
    public final String model;     // custom_model_data string; broken variant is model + "_broken"
    public final int insulation;   // thermal insulation 0..N: near fire you don't ignite, but it wears the piece

    public ArmorType(String id, String display, EquipmentSlot slot, int tier, int absorbHits,
                     double speedMod, Color dye, NamedTextColor nameColor, String model) {
        this(id, display, slot, tier, absorbHits, speedMod, dye, nameColor, model, 0);
    }

    private ArmorType(String id, String display, EquipmentSlot slot, int tier, int absorbHits,
                      double speedMod, Color dye, NamedTextColor nameColor, String model, int insulation) {
        this.id = id;
        this.display = display;
        this.slot = slot;
        this.tier = tier;
        this.absorbHits = Math.max(1, absorbHits);
        this.speedMod = speedMod;
        this.dye = dye;
        this.nameColor = nameColor;
        this.model = model;
        this.insulation = Math.max(0, insulation);
    }

    /** A copy with a different speed modifier (used by /guns armor slowness and config overrides). */
    public ArmorType withSpeedMod(double newMod) {
        return new ArmorType(id, display, slot, tier, absorbHits, newMod, dye, nameColor, model, insulation);
    }

    /** A copy with a different thermal insulation (used by /guns armor insulation and config). */
    public ArmorType withInsulation(int newInsulation) {
        return new ArmorType(id, display, slot, tier, absorbHits, speedMod, dye, nameColor, model, newInsulation);
    }

    public Material baseMaterial() {
        return switch (slot) {
            case HEAD -> Material.LEATHER_HELMET;
            case LEGS -> Material.LEATHER_LEGGINGS;
            case FEET -> Material.LEATHER_BOOTS;
            default -> Material.LEATHER_CHESTPLATE;
        };
    }

    /** The slot group the speed modifier applies in (so it only counts while actually worn there). */
    public EquipmentSlotGroup slotGroup() {
        return switch (slot) {
            case HEAD -> EquipmentSlotGroup.HEAD;
            case LEGS -> EquipmentSlotGroup.LEGS;
            case FEET -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.CHEST;
        };
    }

    public String brokenModel() { return model + "_broken"; }

    public String region() {
        return switch (slot) {
            case HEAD -> "the head";
            case LEGS -> "the legs";
            case FEET -> "the feet";
            default -> "the body";
        };
    }

    /** A human label for the speed effect, e.g. "+5% speed", "-10% speed", or "no speed change". */
    public String speedLabel() {
        if (speedMod == 0) return "no speed change";
        int pct = (int) Math.round(speedMod * 100);
        return (pct > 0 ? "+" : "") + pct + "% speed";
    }

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
