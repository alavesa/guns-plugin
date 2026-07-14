package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Actionbar bridge. When the Labra plugin is on the server, messages go
 * through its ActionBars hub so they STACK above persistent HUD lines (the
 * NVG battery bar) instead of overwriting them. Without Labra, plain
 * sendActionBar - checked once, not per shot.
 */
final class Msg {

    private static final boolean HUB;

    static {
        boolean found;
        try {
            Class.forName("fi.alavesa.labra.ActionBars");
            found = org.bukkit.Bukkit.getPluginManager().getPlugin("Labra") != null;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        HUB = found;
    }

    private Msg() { }

    static void actionbar(Player player, Component text) {
        if (HUB) {
            fi.alavesa.labra.ActionBars.message(player, text);
        } else {
            player.sendActionBar(text);
        }
    }
}
