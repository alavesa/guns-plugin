package fi.alavesa.guns;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Fires third-person rig animations on the Labra BetterModel rig when a gun does something (reload,
 * fire). No hard dependency on Labra: it's reached by reflection, so Guns runs fine with or without it.
 * The method is resolved once and cached; if Labra/BetterModel isn't present it stays a no-op.
 */
final class RigBridge {

    private static Method trigger;
    private static boolean resolved;

    private RigBridge() { }

    /** Play a one-shot rig clip (e.g. "reload", "fire") on the player's rig, if one exists. */
    static void trigger(Player player, String key) {
        Method m = resolve();
        if (m == null) return;
        try { m.invoke(null, player, key); } catch (Throwable ignored) { }
    }

    private static Method resolve() {
        if (resolved) return trigger;
        resolved = true;
        try {
            Class<?> c = Class.forName("fi.alavesa.labra.BmRig");
            trigger = c.getMethod("triggerFor", Player.class, String.class);
        } catch (Throwable ignored) {
            trigger = null;   // Labra not present - stay a no-op
        }
        return trigger;
    }
}
