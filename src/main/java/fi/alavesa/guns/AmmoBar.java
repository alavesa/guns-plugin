package fi.alavesa.guns;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ammo bar: a boss bar at the top of the screen, visible only while a gun is held.
 * Shows "<gun name>  7/12" as a draining bar that goes green -> yellow -> red.
 */
public final class AmmoBar {

    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public void update(Player player, Gun gun, int ammo) {
        float fraction = gun.magazine() <= 0 ? 0f
            : Math.max(0f, Math.min(1f, ammo / (float) gun.magazine()));
        BossBar.Color color = fraction > 0.5f ? BossBar.Color.GREEN
            : fraction > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED;
        Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(gun.name())
            .append(Component.text("  " + ammo + " / " + gun.magazine(),
                ammo == 0 ? NamedTextColor.RED : NamedTextColor.WHITE));

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
