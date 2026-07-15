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
    String base        // "crossbow" (default) or "spyglass" - snipers scope for real
) {
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
