package fi.alavesa.guns;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;

/**
 * The five ballistic vest tiers (SCP:CB-style body armour). Tier is the ordering used by the
 * bullet-piercing system: a bullet with piercing level P defeats vests of tier &lt;= P outright,
 * and is "rated against" (may be stopped by) vests of tier &gt; P. Vests above LIGHT slow the
 * wearer, heaviest most. Names are light grey up to BALLISTIC and dark grey for the heavy tiers.
 */
public enum Armor {
    ULTRA_LIGHT (1, "Ultra Light Ballistic Vest", NamedTextColor.GRAY,      -1, Color.fromRGB(224, 224, 228)),
    LIGHT       (2, "Light Ballistic Vest",        NamedTextColor.GRAY,      -1, Color.fromRGB(188, 188, 194)),
    BALLISTIC   (3, "Ballistic Vest",              NamedTextColor.GRAY,       0, Color.fromRGB(150, 150, 156)),
    HEAVY       (4, "Heavy Ballistic Vest",        NamedTextColor.DARK_GRAY,  1, Color.fromRGB(96,  96,  102)),
    ULTRA_HEAVY (5, "Ultra Heavy Ballistic Vest",  NamedTextColor.DARK_GRAY,  2, Color.fromRGB(60,  60,  66));

    public final int tier;
    public final String display;
    public final NamedTextColor color;
    public final int slowness;   // slowness amplifier while worn (-1 = none, 0 = Slowness I, ...)
    public final Color dye;

    Armor(int tier, String display, NamedTextColor color, int slowness, Color dye) {
        this.tier = tier;
        this.display = display;
        this.color = color;
        this.slowness = slowness;
        this.dye = dye;
    }

    /** Resource-pack custom_model_data string, e.g. "vest_ballistic". */
    public String model() { return "vest_" + name().toLowerCase(); }

    public static Armor byTier(int tier) {
        for (Armor a : values()) if (a.tier == tier) return a;
        return null;
    }

    /** Parse a command argument like "ballistic", "ultraheavy", "ultra_heavy", "heavy". */
    public static Armor byId(String id) {
        if (id == null) return null;
        String k = id.toLowerCase().replace("-", "").replace("_", "").replace(" ", "");
        return switch (k) {
            case "ultralight", "ul" -> ULTRA_LIGHT;
            case "light", "l" -> LIGHT;
            case "ballistic", "vest", "b" -> BALLISTIC;
            case "heavy", "h" -> HEAVY;
            case "ultraheavy", "uh" -> ULTRA_HEAVY;
            default -> null;
        };
    }
}
