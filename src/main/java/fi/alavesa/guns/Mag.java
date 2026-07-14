package fi.alavesa.guns;

/** One magazine type, as loaded from guns.yml. Guns reference a mag by id
 *  (gun's "mag" stat); reloading such a gun consumes one matching mag item
 *  and fills the gun to its own full capacity. */
public record Mag(
    String id,
    String name,
    String model,
    int capacity   // kept for config compat; cosmetic since 0.6.0 (one mag = full gun)
) {}
