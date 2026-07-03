package fi.alavesa.guns;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Grenades are snowballs under the hood: vanilla handles the throw + arc, we tag the
 * projectile on launch and explode it on impact (fuse-ticks 0) or let it land and cook
 * (fuse-ticks > 0, shown as a small item display lying on the ground).
 */
public final class GrenadeListener implements Listener {

    private final GunsPlugin plugin;
    private final GunRegistry registry;

    public GrenadeListener(GunsPlugin plugin, GunRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler
    public void onThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) return;
        if (!(ball.getShooter() instanceof Player player)) return;
        Grenade grenade = registry.grenadeOf(player.getInventory().getItemInMainHand());
        if (grenade == null) return;
        ball.getPersistentDataContainer().set(registry.grenadeKey(), PersistentDataType.STRING, grenade.id());
        ball.setVelocity(ball.getVelocity().multiply(grenade.velocity()));
        player.setCooldown(Material.SNOWBALL, 10); // short anti-spam on throws
        player.getWorld().playSound(player.getLocation(), "minecraft:entity.witch.throw", 0.8f, 1.4f);
    }

    @EventHandler
    public void onLand(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) return;
        String id = ball.getPersistentDataContainer().get(registry.grenadeKey(), PersistentDataType.STRING);
        Grenade grenade = registry.getGrenade(id);
        if (grenade == null) return;

        Location loc = ball.getLocation();
        Player thrower = ball.getShooter() instanceof Player p ? p : null;

        if (grenade.fuseTicks() <= 0) {
            explode(grenade, loc, thrower);
            return;
        }

        // Land and cook: show the grenade lying where it hit, then boom
        ItemStack shown = registry.buildGrenadeItem(grenade);
        shown.setAmount(1);
        ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(shown);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(new Transformation(
                new Vector3f(0f, 0.1f, 0f), new Quaternionf(),
                new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
        });
        loc.getWorld().playSound(loc, "minecraft:block.note_block.hat", 1f, 2f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            display.remove();
            explode(grenade, loc, thrower);
        }, grenade.fuseTicks());
    }

    private void explode(Grenade grenade, Location loc, Player thrower) {
        // A real explosion: vanilla damage falloff, knockback, sound and particles, credited
        // to the thrower. Blocks only break if the grenade says so.
        loc.getWorld().createExplosion(thrower, loc, (float) grenade.power(), false, grenade.breakBlocks());
    }
}
