package fi.alavesa.guns;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WEAPON SELECTOR - a PERSISTENT boss bar listing the guns you carry (up to 4), the one in your
 * hand highlighted. It stays on screen the whole time you're carrying a gun (even when a gun isn't the
 * held item), so it never flickers away; it only hides when you have no guns at all. Press a slot's
 * number (1..9) to equip that gun - the hotbar is pure vanilla.
 *
 * A boss bar is the only server-side HUD element that is per-player, always-visible and can't be
 * corrupted by other overlays. A truly free-floating bottom-right-corner HUD isn't possible with a
 * server plugin + resource pack on a vanilla client - that needs a client mod.
 */
public final class WeaponBar {

    private static final int MAX_SLOTS = 4;

    private final GunRegistry registry;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    WeaponBar(GunRegistry registry) { this.registry = registry; }

    /** Refresh (or hide) a player's selector bar. Call every few ticks + on held-slot change. */
    public void update(Player p) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 9 && slots.size() < MAX_SLOTS; i++)
            if (registry.gunOf(p.getInventory().getItem(i)) != null) slots.add(i);
        if (slots.isEmpty()) { hide(p); return; }

        int held = p.getInventory().getHeldItemSlot();
        TextComponent.Builder line = Component.text();
        line.append(Component.text("Weapons  ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false));
        for (int slot : slots) {
            Gun g = registry.gunOf(p.getInventory().getItem(slot));
            String name = g == null ? "?" : stripColor(g.name());
            boolean selected = slot == held;
            line.append(Component.text((selected ? "▸" : " ") + (slot + 1) + ":" + name + " ",
                    selected ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false));
        }
        Component title = line.build().decoration(TextDecoration.BOLD, false);

        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(title);
        }
    }

    public void hide(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("[&§].", "");
    }
}
