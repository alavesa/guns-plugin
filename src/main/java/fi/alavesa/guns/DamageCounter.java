package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-screen damage counter. Whenever a player deals damage - with a gun OR their fists (any melee) - the
 * total is shown on their action bar and grows with each hit. It clears after 3 seconds of dealing no
 * damage to anything. Purely cosmetic feedback; it never changes the damage itself.
 */
public final class DamageCounter implements Listener, Runnable {

    private static final long CLEAR_AFTER_MS = 3000L;

    private final Plugin plugin;
    private final Map<UUID, Double> total = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHit = new ConcurrentHashMap<>();

    public DamageCounter(Plugin plugin) { this.plugin = plugin; }

    /** Called by the Guns fire code when a bullet actually deals damage (raycast guns don't always go
     *  through EntityDamageByEntityEvent, so the shooter reports its hits here directly). */
    public void record(Player dealer, double amount) {
        if (dealer == null || amount <= 0) return;
        double t = total.merge(dealer.getUniqueId(), amount, Double::sum);
        lastHit.put(dealer.getUniqueId(), now());
        show(dealer, t);
    }

    /** Fists and any other melee/vanilla damage a player deals go through this event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        record(p, event.getFinalDamage());
    }

    private void show(Player p, double running) {
        p.sendActionBar(Component.text("⚔ ", NamedTextColor.RED)
            .append(Component.text(fmt(running), NamedTextColor.WHITE, TextDecoration.BOLD))
            .append(Component.text(" dmg", NamedTextColor.GRAY)));
    }

    @Override
    public void run() {
        long cutoff = now() - CLEAR_AFTER_MS;
        for (var e : lastHit.entrySet()) {
            if (e.getValue() < cutoff) {
                total.remove(e.getKey());
                lastHit.remove(e.getKey());
                Player p = plugin.getServer().getPlayer(e.getKey());
                if (p != null && p.isOnline()) p.sendActionBar(Component.empty());   // fade out
            }
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }

    private static long now() { return java.lang.System.currentTimeMillis(); }
}
