package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * WEAPON SELECTOR - a transient action-bar strip. When you switch your held hotbar slot to a gun
 * (by pressing its number key 1..9, or by scrolling), it flashes the guns you're carrying (up to 4
 * weapon slots) as "1:Pistol  2:Rifle ...", the one now in hand highlighted. Press the number of a
 * slot to equip that gun - that's just the vanilla hotbar, which we leave completely untouched: no
 * scroll hijack, so you can freely switch to any item.
 *
 * It's drawn NON-BOLD through the Labra action-bar hub so it composes with the other HUD lines
 * instead of erasing them, and never bleeds bold into them (the earlier bug). The sidebar is left
 * free for the radio plugin's chat.
 */
public final class GunSelector implements Listener {

    private static final int MAX_SLOTS = 4;

    private final GunRegistry registry;

    GunSelector(GunRegistry registry) { this.registry = registry; }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();

        // The up-to-4 hotbar slots that hold a gun, lowest first.
        List<Integer> guns = new ArrayList<>();
        for (int i = 0; i < 9 && guns.size() < MAX_SLOTS; i++)
            if (registry.gunOf(p.getInventory().getItem(i)) != null) guns.add(i);
        if (guns.isEmpty()) return;

        boolean switchedToGun = registry.gunOf(p.getInventory().getItem(event.getNewSlot())) != null;
        if (!switchedToGun) return;   // only surface the strip when you actually draw a gun

        TextComponent.Builder line = Component.text();
        for (int slot : guns) {
            Gun g = registry.gunOf(p.getInventory().getItem(slot));
            String name = g == null ? "?" : stripColor(g.name());
            boolean selected = slot == event.getNewSlot();
            line.append(Component.text((selected ? " ▸" : "  ") + (slot + 1) + ":" + name + " ",
                    selected ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false));
        }
        Msg.actionbar(p, line.build().decoration(TextDecoration.BOLD, false));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("[&§].", "");
    }
}
