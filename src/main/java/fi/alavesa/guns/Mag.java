package fi.alavesa.guns;

/** One magazine type, as loaded from guns.yml. Guns reference a mag by id
 *  (gun's "mag" stat); reloading such a gun consumes one matching mag item
 *  and loads min(mag capacity, gun capacity) rounds. */
public record Mag(
    String id,
    String name,
    String model,
    int capacity   // rounds one mag loads (capped at the gun's own magazine size)
) {}
