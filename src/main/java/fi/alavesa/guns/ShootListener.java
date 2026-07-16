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

    /** Aim-down-sights per player: QualityArmory-style - you aim by SNEAKING
     *  with a crossbow-gun in hand. Un-sneak, slot change or drop ends it.
     *  Aiming slows the walk and steadies the hand (Slowness gives the
     *  vanilla FOV zoom for free) and swaps the item to its `<model>_aim`
     *  ironsights model. Spyglass guns keep their vanilla scope instead. */
    private final java.util.Set<java.util.UUID> aiming = new java.util.HashSet<>();

    /** The custom_model_data suffix the resource pack dispatches to the ironsights model. */
    private static final String AIM_SUFFIX = "_aim";

    /** Set while a gun's OWN shot damage is being applied, so onPointBlank
     *  doesn't mistake it for a melee swing and cancel it (the 'guns stopped
     *  dealing damage' bug: the plugin was cancelling its own gunfire). */
    private final java.util.Set<java.util.UUID> firing = new java.util.HashSet<>();

    public boolean isAiming(Player player) {
        return aiming.contains(player.getUniqueId());
    }

    /** Crouch = aim (crossbow guns only; the spyglass sniper scopes on right-click). */
    @org.bukkit.event.EventHandler
    public void onSneakAim(org.bukkit.event.player.PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) {
            stopAiming(player);
            return;
        }
        Gun gun = registry.gunOf(player.getInventory().getItemInMainHand());
        if (gun == null || gun.isSpyglass()) return;
        startAiming(player);
    }

    private void startAiming(Player player) {
        if (!aiming.add(player.getUniqueId())) return;
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.SLOWNESS, 20 * 3600, 3, true, false));
        swapHeldModel(player, true);
        Msg.actionbar(player, net.kyori.adventure.text.Component.text("[ + ]",
            net.kyori.adventure.text.format.NamedTextColor.GREEN));
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_SPYGLASS_USE, 0.5f, 1.4f);
    }

    private void stopAiming(Player player) {
        if (!aiming.remove(player.getUniqueId())) return;
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        swapHeldModel(player, false);
        Msg.actionbar(player, net.kyori.adventure.text.Component.text("[ - ]",
            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_SPYGLASS_USE, 0.5f, 1.4f);
    }

    /** Swap the main-hand gun between `<model>` and `<model>_aim`. */
    private void swapHeldModel(Player player, boolean aim) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (registry.gunOf(held) == null) return;
        if (applyModelSuffix(held, aim)) player.getInventory().setItemInMainHand(held);
    }

    /** Rewrites the item's custom_model_data string to the aimed/normal variant.
     *  Returns true if the item changed (caller must write it back). */
    private boolean applyModelSuffix(ItemStack item, boolean aim) {
        var meta = item.getItemMeta();
        if (meta == null) return false;
        var cmd = meta.getCustomModelDataComponent();
        java.util.List<String> strings = cmd.getStrings();
        if (strings.isEmpty()) return false;
        String model = strings.get(0);
        String want = aim
            ? (model.endsWith(AIM_SUFFIX) ? model : model + AIM_SUFFIX)
            : (model.endsWith(AIM_SUFFIX) ? model.substring(0, model.length() - AIM_SUFFIX.length()) : model);
        if (want.equals(model)) return false;
        cmd.setStrings(java.util.List.of(want));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return true;
    }

    /** A gun stuck showing its ironsights model while nobody aims it gets normalized. */
    private void normalizeSlot(Player player, int slot) {
        ItemStack item = player.getInventory().getItem(slot);
        if (registry.gunOf(item) == null) return;
        if (applyModelSuffix(item, false)) player.getInventory().setItem(slot, item);
    }

    @org.bukkit.event.EventHandler
    public void onAimDrop(org.bukkit.event.player.PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        // slot change ends the aim; the aimed gun is still in the PREVIOUS
        // slot at this point, so normalize it there
        if (aiming.remove(player.getUniqueId())) {
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        }
        normalizeSlot(player, event.getPreviousSlot());
        // belt and suspenders: never show a stuck _aim model on the drawn item
        normalizeSlot(player, event.getNewSlot());
        // self-heal: any gun that lost its pose-arrow (a discharge that
        // slipped through before the net existed) gets it back on pickup
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        repairPose(next);
        // still crouched? the newly drawn crossbow-gun comes up aimed
        Gun nextGun = registry.gunOf(next);
        if (nextGun != null && !nextGun.isSpyglass() && player.isSneaking()
            && aiming.add(player.getUniqueId())) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, 20 * 3600, 3, true, false));
            ItemStack drawn = player.getInventory().getItem(event.getNewSlot());
            if (drawn != null && applyModelSuffix(drawn, true)) {
                player.getInventory().setItem(event.getNewSlot(), drawn);
            }
        }
    }

    /** Dropping the gun ends the aim, and the flying item never keeps the ironsights model. */
    @org.bukkit.event.EventHandler
    public void onGunDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (registry.gunOf(dropped) == null) return;
        if (applyModelSuffix(dropped, false)) event.getItemDrop().setItemStack(dropped);
        stopAiming(event.getPlayer());
    }

    /** Logout while aiming: clear the effect and the ironsights model so
     *  nothing sticks across the relog. */
    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (aiming.remove(player.getUniqueId())) {
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            swapHeldModel(player, false);
        }
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

    /** Right click fires crossbow guns; their left click is cancelled and does
     *  nothing (aiming is crouch now). Spyglass guns are the exception: they
     *  fire on LEFT and their right click must pass through UNCANCELLED -
     *  that's the vanilla scope. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShoot(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        boolean left = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(item);
        if (gun == null) return;
        repairPose(item);
        if (gun.isSpyglass()) return; // right scopes (vanilla), left fires via onSwing
        // crossbow gun: no vanilla behavior ever - right fires, left is a dead key
        event.setCancelled(true);
        if (right) shoot(event.getPlayer(), gun, item);
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
        if (firing.contains(player.getUniqueId())) return; // our own bullet - let it through
        ItemStack held = player.getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(held);
        if (gun == null) return;
        event.setCancelled(true); // no melee bonk with a gun; firing is handled elsewhere
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
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(held);
        if (gun == null) return;
        event.setCancelled(true); // no visible swing with a gun
        // the arm-swing packet fires on left-click even WHILE scoping a
        // spyglass, so this is the sniper's trigger - it works aimed or not
        if (gun.isSpyglass()) shoot(player, gun, held);
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
        // launch inaccuracy: a small random cone, tightened by aiming (crouch),
        // so the bullet doesn't leave perfectly along the crosshair
        double spread = gun.spread();
        if (isAiming(player)) spread *= 0.3;
        if (spread > 0) {
            var rng = java.util.concurrent.ThreadLocalRandom.current();
            dir = rotate(dir, Math.toRadians(rng.nextGaussian() * spread * 0.5),
                Math.toRadians(rng.nextGaussian() * spread * 0.5));
        }
        double remaining = gun.range();
        int bounces = gun.ricochet();
        boolean firstSegment = true;
        // the path is walked in short steps; after each clear step the
        // direction bends downward (drop), so the trajectory ARCS instead
        // of running dead straight
        final double STEP = 6.0;
        int tracerBudget = plugin.getConfig().getInt("tracer-max-points", 40);

        while (remaining > 0.5) {
            boolean excludeShooter = firstSegment;
            double segLen = Math.min(remaining, STEP);

            RayTraceResult blockHit = player.getWorld().rayTraceBlocks(
                from, dir, segLen, FluidCollisionMode.NEVER, true);
            double searchDist = blockHit != null
                ? from.toVector().distance(blockHit.getHitPosition())
                : segLen;
            RayTraceResult entityHit = searchDist > 0.1 ? player.getWorld().rayTraceEntities(
                from, dir, searchDist, 0.25,
                e -> e instanceof LivingEntity && (!excludeShooter || e != player)) : null;
            RayTraceResult hit = entityHit != null ? entityHit : blockHit;

            Location end = hit != null
                ? hit.getHitPosition().toLocation(player.getWorld())
                : from.clone().add(dir.clone().multiply(segLen));
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
                break;
            }
            // clear step: advance and bend the path down by the drop
            remaining -= segLen;
            from = end;
            if (gun.drop() > 0) {
                dir = dir.clone().add(new Vector(0, -gun.drop() * segLen, 0)).normalize();
            }
            firstSegment = false;
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

    /** Rotate a direction by small yaw/pitch offsets (radians) for spread. */
    private Vector rotate(Vector dir, double yaw, double pitch) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double x = dir.getX() * cy - dir.getZ() * sy;
        double z = dir.getX() * sy + dir.getZ() * cy;
        Vector v = new Vector(x, dir.getY(), z);
        // pitch around the horizontal axis perpendicular to v
        Vector axis = new Vector(-z, 0, x);
        if (axis.lengthSquared() > 1e-6) {
            axis.normalize();
            double cp = Math.cos(pitch), sp = Math.sin(pitch);
            v = v.clone().multiply(cp)
                .add(axis.clone().crossProduct(v).multiply(sp))
                .add(axis.clone().multiply(axis.dot(v) * (1 - cp)));
        }
        return v.normalize();
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

        firing.add(shooter.getUniqueId());
        try {
            target.damage(damage, shooter);
        } finally {
            firing.remove(shooter.getUniqueId());
        }
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
