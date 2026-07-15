package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
 * F (swap-hands) reloads; guns with a mag stat consume one matching magazine item from the
 * inventory (one mag = a full gun). Bullets can ricochet off blocks (gun stat), hits can
 * apply bleed or any potion effect, and shot players are told where they were hit
 * (head/chest/stomach/arm/leg/foot, with head/leg damage scaling).
 */
public final class ShootListener implements Listener {

    private static final Particle.DustOptions TRACER =
        new Particle.DustOptions(Color.fromRGB(255, 220, 120), 0.5f);
    private static final Particle.DustOptions BLOOD =
        new Particle.DustOptions(Color.fromRGB(160, 20, 20), 0.7f);

    private final GunsPlugin plugin;
    private final GunRegistry registry;
    private final AmmoBar ammoBar;
    private final Map<UUID, Long> nextShotAt = new ConcurrentHashMap<>();
    private final Set<UUID> reloading = ConcurrentHashMap.newKeySet();

    public ShootListener(GunsPlugin plugin, GunRegistry registry, AmmoBar ammoBar) {
        this.plugin = plugin;
        this.registry = registry;
        this.ammoBar = ammoBar;
    }

    /** Aim-down-sights per player: toggled by right-click, dropped on
     *  slot change or unequip. Aiming slows the walk and steadies the
     *  hand (Slowness gives the vanilla FOV zoom for free). */
    private final java.util.Set<java.util.UUID> aiming = new java.util.HashSet<>();

    public boolean isAiming(Player player) {
        return aiming.contains(player.getUniqueId());
    }

    @org.bukkit.event.EventHandler
    public void onAim(PlayerInteractEvent event) {
        boolean left = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        Gun aimedGun = registry.gunOf(held);
        if (aimedGun == null) return;
        repairPose(held);
        if (aimedGun.isSpyglass()) return; // vanilla scoping IS the ADS - with our overlay
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (aiming.remove(player.getUniqueId())) {
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            Msg.actionbar(player, net.kyori.adventure.text.Component.text("[ - ]",
                net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        } else {
            aiming.add(player.getUniqueId());
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, 20 * 3600, 3, true, false));
            Msg.actionbar(player, net.kyori.adventure.text.Component.text("[ + ]",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_SPYGLASS_USE, 0.5f, 1.4f);
    }

    @org.bukkit.event.EventHandler
    public void onAimDrop(org.bukkit.event.player.PlayerItemHeldEvent event) {
        if (aiming.remove(event.getPlayer().getUniqueId())) {
            event.getPlayer().removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        }
        // self-heal: any gun that lost its pose-arrow (a discharge that
        // slipped through before the net existed) gets it back on pickup
        ItemStack next = event.getPlayer().getInventory().getItem(event.getNewSlot());
        repairPose(next);
    }

    /** Guns are crossbows whose charged arrow exists only for the aiming
     *  pose and the charged-state model - if it ever discharged, the model
     *  and pose broke on that item. Recharge silently. */
    private void repairPose(ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.CROSSBOW) return;
        if (registry.gunOf(item) == null) return;
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.CrossbowMeta meta)) return;
        if (meta.hasChargedProjectiles()) return;
        meta.addChargedProjectile(new ItemStack(org.bukkit.Material.ARROW));
        item.setItemMeta(meta);
    }

    /** Right click fires (back to the original trigger). Spyglass guns are
     *  the exception: their right click is the scope, so they fire on left
     *  (their aim-toggle path is skipped in onAim). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShoot(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        boolean left = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(item);
        if (gun == null) return;
        // crossbow guns fire on RIGHT (left is the aim toggle, cancelled in
        // onAim); spyglass guns fire on LEFT and their right click must pass
        // through UNCANCELLED - that's the vanilla scope
        boolean fires = gun.isSpyglass() ? left : right;
        if (!fires) return;
        event.setCancelled(true);
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
            Msg.actionbar(player, Component.text("Magazine full", NamedTextColor.GRAY));
            return;
        }
        if (gun.requiresMag() && findMagSlot(player, gun.magId()) == -1) {
            noMagazine(player);
            return;
        }
        reloading.add(player.getUniqueId());
        Msg.actionbar(player, Component.text("Reloading...", NamedTextColor.YELLOW));
        player.getWorld().playSound(player.getLocation(), "minecraft:item.crossbow.loading_middle", 1f, 1f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            reloading.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            ItemStack now = player.getInventory().getItemInMainHand();
            Gun held = registry.gunOf(now);
            if (held == null || !held.id().equals(gun.id())) return; // switched items mid-reload
            if (held.requiresMag()) {
                // Re-find the mag: it may have been dropped/moved during the reload timer.
                int slot = findMagSlot(player, held.magId());
                if (slot == -1) {
                    noMagazine(player);
                    return;
                }
                // One mag = a full gun, whatever the mag's cosmetic capacity number says.
                ItemStack magItem = player.getInventory().getItem(slot);
                if (magItem.getAmount() <= 1) player.getInventory().setItem(slot, null);
                else magItem.setAmount(magItem.getAmount() - 1);
            }
            registry.setAmmo(now, held.magazine());
            player.getInventory().setItemInMainHand(now);
            player.getWorld().playSound(player.getLocation(), "minecraft:item.crossbow.loading_end", 1f, 1.2f);
            ammoBar.update(player, held, held.magazine());
        }, gun.reloadTicks());
    }

    /** First inventory slot holding a mag of this type, or -1. */
    private int findMagSlot(Player player, String magId) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (magId.equals(registry.magIdOf(inv.getItem(i)))) return i;
        }
        return -1;
    }

    /** Reload refused: dry click, nothing to feed the gun with. */
    private void noMagazine(Player player) {
        Msg.actionbar(player, Component.text("No magazine.", NamedTextColor.GRAY)
            .decorate(TextDecoration.ITALIC));
        player.getWorld().playSound(player.getLocation(), "minecraft:block.dispenser.fail", 0.8f, 1.6f);
    }

    /** A left click that lands ON a target arrives as a melee attack, not
     *  an interact - the gun still fires (and never bonks like a stick). */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onPointBlank(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(held);
        if (gun == null) return;
        event.setCancelled(true);
        if (gun.isSpyglass()) shoot(player, gun, held); // sniper fires on left
    }

    /** The knife-server trick: a client-only empty hand for one tick makes
     *  the re-equip dip play OVER the punch animation - the gun visibly
     *  lowers for a fraction of a second instead of swinging. */
    private void dipHand(Player player) {
        if (!plugin.getConfig().getBoolean("fire-dip", true)) return;
        player.sendEquipmentChange(player, org.bukkit.inventory.EquipmentSlot.HAND,
            new ItemStack(org.bukkit.Material.AIR));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendEquipmentChange(player, org.bukkit.inventory.EquipmentSlot.HAND,
                    player.getInventory().getItemInMainHand());
            }
        });
    }

    /** Nobody else sees the punch either: gun-in-hand arm swings are
     *  cancelled server-side (Paper's arm swing event). */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent event) {
        if (registry.gunOf(event.getPlayer().getInventory().getItemInMainHand()) != null) {
            event.setCancelled(true);
        }
    }

    /** The charged arrow exists only for the aiming pose - if anything
     *  slips past the interact cancel, the discharge itself is refused. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onDischarge(org.bukkit.event.entity.EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (registry.gunOf(event.getBow()) == null) return;
        event.setCancelled(true);
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
            Msg.actionbar(player, Component.text("Out of ammo - press F to reload", NamedTextColor.RED));
            return;
        }
        registry.setAmmo(item, ammo - 1);
        player.getInventory().setItemInMainHand(item);
        if (plugin.getConfig().getBoolean("recoil-kick", true)) {
            org.bukkit.util.Vector kick = player.getLocation().getDirection()
                .setY(0).normalize().multiply(-0.06);
            kick.setY(0.02);
            player.setVelocity(player.getVelocity().add(kick));
        }
        dipHand(player); // the knife trick: the item dips instead of punching
        player.getWorld().playSound(player.getEyeLocation(), gun.sound(), 1f, gun.soundPitch());

        long shotStart = System.nanoTime();

        // Trace the bullet, bouncing off blocks up to gun.ricochet() times. After the first
        // bounce the shooter is a valid target too (your own ricochet CAN hit you).
        Location from = player.getEyeLocation();
        Vector dir = from.getDirection();
        double remaining = gun.range();
        int bounces = gun.ricochet();
        boolean firstSegment = true;

        int tracerBudget = plugin.getConfig().getInt("tracer-max-points", 40);

        while (remaining > 0.5) {
            boolean excludeShooter = firstSegment;

            // Cheap block trace first; entities are then only searched up to the wall.
            // A full-range entity sweep builds a huge bounding box (90 blocks for the
            // rifle) and scans every entity in it - THE server-lag hotspot indoors.
            RayTraceResult blockHit = player.getWorld().rayTraceBlocks(
                from, dir, remaining, FluidCollisionMode.NEVER, true);
            double searchDist = blockHit != null
                ? from.toVector().distance(blockHit.getHitPosition())
                : remaining;
            RayTraceResult entityHit = searchDist > 0.1 ? player.getWorld().rayTraceEntities(
                from, dir, searchDist, 0.25,
                e -> e instanceof LivingEntity && (!excludeShooter || e != player)) : null;
            RayTraceResult hit = entityHit != null ? entityHit : blockHit;

            Location end = hit != null
                ? hit.getHitPosition().toLocation(player.getWorld())
                : from.clone().add(dir.clone().multiply(remaining));
            tracerBudget -= drawTracer(from, end, firstSegment ? 1.5 : 0.0, tracerBudget);

            if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
                applyHit(player, gun, target, end);
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
                player.getWorld().spawnParticle(Particle.WAX_OFF, end, 3, 0.05, 0.05, 0.05, 0.2);
                continue;
            }
            if (hit != null) {
                player.getWorld().spawnParticle(Particle.SMOKE, end, 3, 0.05, 0.05, 0.05, 0.01);
            }
            break;
        }

        ammoBar.update(player, gun, ammo - 1);

        // Lag canary: if one shot stalls the main thread noticeably, say so in the console
        // with the entity count - that tells us WHY the server is slow, without guessing.
        long ms = (System.nanoTime() - shotStart) / 1_000_000;
        if (ms > 50) {
            plugin.getLogger().warning("Slow shot: " + gun.id() + " took " + ms + " ms (world has "
                + player.getWorld().getEntityCount() + " entities). If this repeats, entity"
                + " buildup near players is the likely lag source.");
        }
    }

    private void applyHit(Player shooter, Gun gun, LivingEntity target, Location end) {
        double damage = gun.damage();

        // Hit location (players only): tell the victim where the round landed, and
        // scale damage - headshots hurt more, leg/foot hits are grazes.
        String part = target instanceof Player victim ? hitLocation(victim, end) : null;
        if (part != null) {
            damage *= switch (part) {
                case "head" -> 1.5;
                case "leg", "foot" -> 0.75;
                default -> 1.0;
            };
        }

        target.damage(damage, shooter);
        target.getWorld().spawnParticle(Particle.CRIT, end, 8, 0.1, 0.1, 0.1, 0.05);
        if (part != null) {
            ((Player) target).sendActionBar(Component.text("You were shot in the " + part + ".",
                NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
            if (part.equals("head")) {
                Msg.actionbar(shooter, Component.text("Headshot.", NamedTextColor.GRAY)
                    .decorate(TextDecoration.ITALIC));
            }
        }
        applyEffect(shooter, gun, target);
    }

    /** Which body part the shot at `end` (the ray's hit position) struck, judged by height
     *  up the victim's hitbox: head = top 0.35 blocks, then chest/stomach/legs/feet bands.
     *  Torso-height hits far off the center axis count as arms. */
    private String hitLocation(Player victim, Location end) {
        double height = victim.getHeight();
        double relY = end.getY() - victim.getLocation().getY();
        if (relY >= height - 0.35) return "head";
        double f = Math.max(0, relY) / Math.max(0.1, height); // fraction up the hitbox
        if (f < 0.15) return "foot";
        if (f < 0.42) return "leg";
        double dx = end.getX() - victim.getLocation().getX();
        double dz = end.getZ() - victim.getLocation().getZ();
        if (Math.hypot(dx, dz) > victim.getWidth() * 0.35) return "arm";
        return f < 0.62 ? "stomach" : "chest";
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

    /** Draws up to maxPoints tracer particles; returns how many were spawned. */
    private int drawTracer(Location from, Location to, double skip, int maxPoints) {
        if (maxPoints <= 0) return 0;
        double step = Math.max(0.5, plugin.getConfig().getDouble("tracer-step", 2.0));
        Vector dir = to.toVector().subtract(from.toVector());
        double length = dir.length();
        if (length < 0.01) return 0;
        dir.normalize();
        int spawned = 0;
        for (double d = skip; d < length && spawned < maxPoints; d += step) {
            Location p = from.clone().add(dir.clone().multiply(d));
            from.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, TRACER);
            spawned++;
        }
        return spawned;
    }

}
