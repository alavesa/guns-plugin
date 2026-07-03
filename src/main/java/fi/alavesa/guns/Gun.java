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
    float soundPitch
) {
    /** Minimum milliseconds between shots. */
    public long shotIntervalMs() {
        return fireRate <= 0 ? 1000L : (long) (1000.0 / fireRate);
    }
}
