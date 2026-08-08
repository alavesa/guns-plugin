package fi.alavesa.guns;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ammo bar: a boss bar at the top of the screen, visible only while a gun is
 * held. Alongside the "7 / 12" count it draws a row of textured BULLET SYMBOLS -
 * a full round for each bullet left, an empty casing for each spent one - from
 * the guns:ammo resource-pack font. It also shows the current fire mode.
 */
public final class AmmoBar {

    /** guns:ammo font glyphs (see resource-pack/tools/gen_ammo.py). */
    private static final Key AMMO_FONT = Key.key("guns", "ammo");
    private static final String FULL = "\uE000";   // a loaded round
    private static final String EMPTY = "\uE001";  // a spent casing
    private static final int MAX_SYMBOLS = 30;      // don't draw a mile of bullets

    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public void update(Player player, Gun gun, int ammo, String mode) {
        update(player, gun, ammo, mode, -1);
    }

    /** reserve = spare rounds not loaded (full mags x capacity + the leftover pool);
     *  pass -1 to hide the reserve readout. */
    public void update(Player player, Gun gun, int ammo, String mode, int reserve) {
        int mag = Math.max(1, gun.magazine());
        float fraction = Math.max(0f, Math.min(1f, ammo / (float) mag));
        BossBar.Color color = fraction > 0.5f ? BossBar.Color.GREEN
            : fraction > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED;

        // symbol row: 1:1 with the mag when it's small, scaled down when it's huge
        int slots = Math.min(mag, MAX_SYMBOLS);
        int full = (int) Math.round(ammo / (double) mag * slots);
        full = Math.max(0, Math.min(slots, full));
        Component bullets = Component.text(FULL.repeat(full) + EMPTY.repeat(slots - full))
            .font(AMMO_FONT);

        Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(gun.name())
            .append(Component.text("  "))
            .append(bullets)
            .append(Component.text("  " + ammo + " / " + mag,
                ammo == 0 ? NamedTextColor.RED : NamedTextColor.WHITE));
        if (reserve >= 0) {
            title = title.append(Component.text("  (+" + reserve + ")",
                reserve == 0 ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY));
        }
        if (mode != null && gun.modes().size() > 1) {
            title = title.append(Component.text("  [" + mode.toUpperCase() + "]",
                NamedTextColor.GRAY));
        }

        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, fraction, color, BossBar.Overlay.NOTCHED_10);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(fraction);
            bar.color(color);
        }
    }

    public void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }
}
