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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Right-click with a gun = one shot (instant raytrace, no projectile). The gun item is a
 * charged crossbow purely for the AIMING POSE - all vanilla crossbow firing is cancelled.
 * F (swap-hands) reloads. Bullets can ricochet off blocks (gun stat), hits from behind get
 * the backstab multiplier, and hits can apply bleed or any potion effect.
 */
public final class ShootListener implements Listener {

    private static final Particle.DustOptions TRACER =
        new Particle.DustOptions(Color.fromRGB(255, 220, 120), 0.5f);
    private static final Particle.DustOptions BLOOD =
        new Particle.DustOptions(Color.fromRGB(160, 20, 20), 0.7f);

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
        if (event.getEntity() instanceof Player && registry.gunOf(event.getBow()) != null) {
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
        player.getWorld().playSound(player.getEyeLocation(), gun.sound(), 1f, gun.soundPitch());

        // Trace the bullet, bouncing off blocks up to gun.ricochet() times. After the first
        // bounce the shooter is a valid target too (your own ricochet CAN hit you).
        Location from = player.getEyeLocation();
        Vector dir = from.getDirection();
        double remaining = gun.range();
        int bounces = gun.ricochet();
        boolean firstSegment = true;

        while (remaining > 0.5) {
            boolean excludeShooter = firstSegment;
            RayTraceResult hit = player.getWorld().rayTrace(
                from, dir, remaining, FluidCollisionMode.NEVER, true, 0.25,
                e -> e instanceof LivingEntity && (!excludeShooter || e != player));

            Location end = hit != null
                ? hit.getHitPosition().toLocation(player.getWorld())
                : from.clone().add(dir.clone().multiply(remaining));
            drawTracer(from, end, firstSegment ? 1.5 : 0.0);

            if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
                applyHit(player, gun, target, dir, end);
                break;
            }
            if (hit != null && hit.getHitBlock() != null && bounces > 0 && hit.getHitBlockFace() != null) {
                Vector normal = hit.getHitBlockFace().getDirection();
                dir = dir.clone().subtract(normal.clone().multiply(2 * dir.dot(normal))).normalize();
                remaining -= from.distance(end);
                from = end.clone().add(normal.clone().multiply(0.05));
                bounces--;
                firstSegment = false;
                player.getWorld().playSound(end, "minecraft:block.chain.hit", 0.7f, 1.8f);
                player.getWorld().spawnParticle(Particle.WAX_OFF, end, 4, 0.05, 0.05, 0.05, 0.2);
                continue;
            }
            if (hit != null) {
                player.getWorld().spawnParticle(Particle.SMOKE, end, 4, 0.05, 0.05, 0.05, 0.01);
            }
            break;
        }

        player.sendActionBar(ammoBar(gun, ammo - 1));
    }

    private void applyHit(Player shooter, Gun gun, LivingEntity target, Vector shotDir, Location end) {
        // Backstab: the shot direction roughly matches the way the target is facing
        boolean backstab = gun.backstab() > 1.0
            && target.getLocation().getDirection().normalize().dot(shotDir.clone().normalize()) > 0.5;
        double damage = backstab ? gun.damage() * gun.backstab() : gun.damage();
        target.damage(damage, shooter);
        target.getWorld().spawnParticle(Particle.CRIT, end, 8, 0.1, 0.1, 0.1, 0.05);
        if (backstab) {
            shooter.sendActionBar(Component.text("Backstab!", NamedTextColor.DARK_RED));
            shooter.getWorld().playSound(end, "minecraft:entity.player.attack.crit", 1f, 0.8f);
        }
        applyEffect(shooter, gun, target);
    }

    private void applyEffect(Player shooter, Gun gun, LivingEntity target) {
        String effect = gun.effect() == null ? "none" : gun.effect().toLowerCase();
        if (effect.equals("none") || effect.isEmpty()) return;

        if (effect.equals("bleed")) {
            // Custom bleed: effectLevel raw damage once per second while the timer runs
            int pulses = Math.max(1, gun.effectTicks() / 20);
            new BukkitRunnable() {
                int left = pulses;
                @Override public void run() {
                    if (left-- <= 0 || !target.isValid() || target.isDead()) { cancel(); return; }
                    target.damage(Math.max(1, gun.effectLevel()), shooter);
                    target.getWorld().spawnParticle(Particle.DUST,
                        target.getLocation().add(0, target.getHeight() / 2, 0), 6, 0.2, 0.3, 0.2, 0, BLOOD);
                }
            }.runTaskTimer(plugin, 20L, 20L);
            return;
        }

        @SuppressWarnings("deprecation")
        PotionEffectType type = PotionEffectType.getByName(effect);
        if (type == null) {
            plugin.getLogger().warning("Gun '" + gun.id() + "' has unknown effect '" + gun.effect()
                + "' - use 'bleed' or a potion effect name (poison, wither, slowness, glowing...).");
            return;
        }
        target.addPotionEffect(new PotionEffect(type, gun.effectTicks(), Math.max(0, gun.effectLevel() - 1)));
    }

    private void drawTracer(Location from, Location to, double skip) {
        Vector dir = to.toVector().subtract(from.toVector());
        double length = dir.length();
        if (length < 0.01) return;
        dir.normalize();
        for (double d = skip; d < length; d += 1.0) {
            Location p = from.clone().add(dir.clone().multiply(d));
            from.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, TRACER);
        }
    }

    private Component ammoBar(Gun gun, int ammo) {
        return Component.text("Ammo " + ammo + "/" + gun.magazine(),
            ammo == 0 ? NamedTextColor.RED : NamedTextColor.GOLD);
    }
}
