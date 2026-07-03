package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Right-click with a gun = one shot (instant raytrace, no projectile). The gun item is a
 * charged crossbow purely for the AIMING POSE - all vanilla crossbow firing is cancelled.
 * F (swap-hands) reloads.
 */
public final class ShootListener implements Listener {

    private static final Particle.DustOptions TRACER =
        new Particle.DustOptions(Color.fromRGB(255, 220, 120), 0.5f);

    private final GunsPlugin plugin;
    private final GunRegistry registry;
    private final Map<UUID, Long> nextShotAt = new ConcurrentHashMap<>();
    private final Set<UUID> reloading = ConcurrentHashMap.newKeySet();

    public ShootListener(GunsPlugin plugin, GunRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShoot(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(item);
        if (gun == null) return;
        event.setCancelled(true); // never fire the crossbow itself
        shoot(event.getPlayer(), gun, item);
    }

    /** Belt and suspenders: no vanilla arrow may ever leave a gun. */
    @EventHandler
    public void onCrossbowFire(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player
                && registry.gunOf(event.getBow()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onReload(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(item);
        if (gun == null) return;
        event.setCancelled(true);
        if (reloading.contains(player.getUniqueId())) return;
        if (registry.ammoOf(item) >= gun.magazine()) {
            player.sendActionBar(Component.text("Magazine full", NamedTextColor.GRAY));
            return;
        }
        reloading.add(player.getUniqueId());
        player.sendActionBar(Component.text("Reloading...", NamedTextColor.YELLOW));
        player.getWorld().playSound(player.getLocation(), "minecraft:item.crossbow.loading_middle", 1f, 1f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            reloading.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            ItemStack now = player.getInventory().getItemInMainHand();
            Gun held = registry.gunOf(now);
            if (held == null || !held.id().equals(gun.id())) return; // switched items mid-reload
            registry.setAmmo(now, held.magazine());
            player.getInventory().setItemInMainHand(now);
            player.getWorld().playSound(player.getLocation(), "minecraft:item.crossbow.loading_end", 1f, 1.2f);
            player.sendActionBar(ammoBar(held, held.magazine()));
        }, gun.reloadTicks());
    }

    private void shoot(Player player, Gun gun, ItemStack item) {
        if (reloading.contains(player.getUniqueId())) return;

        long now = System.currentTimeMillis();
        Long next = nextShotAt.get(player.getUniqueId());
        if (next != null && now < next) return; // fire-rate cap
        nextShotAt.put(player.getUniqueId(), now + gun.shotIntervalMs());

        int ammo = registry.ammoOf(item);
        if (ammo <= 0) {
            player.getWorld().playSound(player.getLocation(), "minecraft:block.dispenser.fail", 0.8f, 1.6f);
            player.sendActionBar(Component.text("Out of ammo - press F to reload", NamedTextColor.RED));
            return;
        }
        registry.setAmmo(item, ammo - 1);
        player.getInventory().setItemInMainHand(item);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        player.getWorld().playSound(eye, gun.sound(), 1f, gun.soundPitch());

        RayTraceResult hit = player.getWorld().rayTrace(
            eye, dir, gun.range(), FluidCollisionMode.NEVER, true, 0.25,
            e -> e != player && e instanceof LivingEntity);

        Location end = hit != null
            ? hit.getHitPosition().toLocation(player.getWorld())
            : eye.clone().add(dir.clone().multiply(gun.range()));

        // Tracer line (skip the first 1.5 blocks so it doesn't fill the shooter's screen)
        double length = eye.toVector().distance(end.toVector());
        for (double d = 1.5; d < length; d += 1.0) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            player.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, TRACER);
        }

        if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
            target.damage(gun.damage(), player);
            player.getWorld().spawnParticle(Particle.CRIT, end, 8, 0.1, 0.1, 0.1, 0.05);
        } else if (hit == null || hit.getHitBlock() != null) {
            player.getWorld().spawnParticle(Particle.SMOKE, end, 4, 0.05, 0.05, 0.05, 0.01);
        }

        player.sendActionBar(ammoBar(gun, ammo - 1));
    }

    private Component ammoBar(Gun gun, int ammo) {
        return Component.text("Ammo " + ammo + "/" + gun.magazine(),
            ammo == 0 ? NamedTextColor.RED : NamedTextColor.GOLD);
    }
}
