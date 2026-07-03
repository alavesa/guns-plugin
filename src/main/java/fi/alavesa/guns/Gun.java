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
    double backstab,   // damage multiplier when shooting a target from behind (1.0 = off)
    String effect,     // "none", "bleed", or a potion effect name (poison, wither, slowness...)
    int effectTicks,   // how long the effect lasts
    int effectLevel,   // effect strength (bleed: damage per second; potions: amplifier+1)
    int ricochet       // how many times a bullet bounces off blocks (0 = none)
) {
    /** Minimum milliseconds between shots. */
    public long shotIntervalMs() {
        return fireRate <= 0 ? 1000L : (long) (1000.0 / fireRate);
    }
}
