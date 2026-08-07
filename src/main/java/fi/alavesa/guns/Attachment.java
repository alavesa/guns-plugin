package fi.alavesa.guns;

/**
 * A gun attachment (scope, grip, barrel, …). Applying one to a gun changes the gun's model (the
 * gun's model string gets this attachment's {@code gunSuffix} appended, so the pack can show the
 * attachment physically on the gun) and multiplies its stats - each attachment is a trade-off:
 * a scope tightens spread but might add weight, a grip cuts recoil, a heavy barrel adds damage, etc.
 */
public record Attachment(
    String id,
    String name,
    String itemModel,     // custom_model_data for the attachment ITEM (held in the inventory)
    String gunSuffix,     // appended to the gun's model when attached, e.g. "scope" -> gun_rifle_scope
    double recoilMult,    // multiplies BOTH vertical and horizontal recoil (0.7 = 30% less kick)
    double spreadMult,    // multiplies spread + aim-spread (0.5 = twice as accurate)
    double damageMult,    // multiplies bullet damage (1.2 = +20%)
    String lore           // one-line pros/cons shown on the attachment item and the gun
) {}
