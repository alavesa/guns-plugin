package fi.alavesa.guns;

/** One gun's stats, as loaded from guns.yml. Items carry only the gun id (PDC) -
 *  stats are read live from the registry at shoot time, so /guns edit affects
 *  already-given guns immediately (except name/model, which are baked into the item). */
public record Gun(
    String id,
    String name,
    String model,
    double damage,
    double fireRate,
    double range,
    int magazine,
    int reloadTicks,
    String sound,
    float soundPitch,
    String effect,     // "none", "bleed", or a potion effect name (poison, wither, slowness...)
    int effectTicks,   // how long the effect lasts
    int effectLevel,   // effect strength (bleed: damage per second; potions: amplifier+1)
    int ricochet,      // how many times a bullet bounces off blocks (0 = none)
    String magId,      // mag id this gun reloads from ("" = old loose-rounds reload)
    String base,       // "crossbow" (default) or "spyglass" - snipers scope for real
    double spread,     // launch inaccuracy in degrees (0 = laser-accurate)
    double drop,       // legacy hitscan downward curve per block (superseded by curve; kept for back-compat)
    double speed,      // bullet projectile speed in blocks/tick (arrow launch velocity)
    double curve,      // how much the bullet arcs down mid-flight (0 = flat, higher = more arc)
    double aimSpread,  // launch inaccuracy in degrees while aiming (crouch) - tighter than spread
    String fireModes,  // which trigger modes it offers: "semi", "auto", or "semi,auto"
    double recoil      // camera kick UP in degrees per shot (0 = none)
) {

    /** The trigger modes this gun offers, in order (first is the default). */
    public java.util.List<String> modes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (fireModes != null) {
            for (String m : fireModes.toLowerCase().split(",")) {
                String t = m.trim();
                if ((t.equals("semi") || t.equals("auto")) && !out.contains(t)) out.add(t);
            }
        }
        return out.isEmpty() ? java.util.List.of("semi") : out;
    }

    public String defaultMode() { return modes().get(0); }

    public boolean hasMode(String mode) { return modes().contains(mode == null ? "" : mode.toLowerCase()); }

    /** Spyglass guns: right-click scopes with the vanilla spyglass zoom and
     *  the pack's custom sight overlay instead of the slowness ADS. */
    public boolean isSpyglass() {
        return "spyglass".equalsIgnoreCase(base);
    }

    /** True if reloading needs (and consumes) a magazine item from the inventory. */
    public boolean requiresMag() {
        return magId != null && !magId.isEmpty();
    }

    /** Minimum milliseconds between shots. */
    public long shotIntervalMs() {
        return fireRate <= 0 ? 1000L : (long) (1000.0 / fireRate);
    }
}
