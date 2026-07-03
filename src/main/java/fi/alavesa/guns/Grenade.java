package fi.alavesa.guns;

/** One grenade's stats. power = explosion strength (TNT is 4.0). fuseTicks 0 = explode on
 *  impact; otherwise the grenade lands and cooks for that long before exploding. */
public record Grenade(
    String id,
    String name,
    String model,
    double power,
    int fuseTicks,
    double velocity,
    boolean breakBlocks
) {}
