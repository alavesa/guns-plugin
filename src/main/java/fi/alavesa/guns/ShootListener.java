package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.GameMode;
import org.bukkit.Material;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
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
        this.bulletGunKey = new NamespacedKey(plugin, "bullet_gun");
        this.bulletShooterKey = new NamespacedKey(plugin, "bullet_shooter");
        this.bulletBouncesKey = new NamespacedKey(plugin, "bullet_bounces");
        this.bulletBornKey = new NamespacedKey(plugin, "bullet_born");
        this.gunAttackerKey = new NamespacedKey(plugin, "gun_attacker");
        this.gunAttackerAtKey = new NamespacedKey(plugin, "gun_attacker_at");
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
    /** The custom_model_data suffix for the empty-magazine (reloading) model state. */
    private static final String EMPTY_SUFFIX = "_emptymag";

    /** Scoreboard tag on every bullet-hole decal, so leftovers can be swept on enable. */
    public static final String TAG_BULLET_HOLE = "guns.bullethole";

    /** Players we lent a single arrow to so the client would animate the crossbow
     *  reload pull; reclaimed when the reload lands (or on quit). */
    private final Set<UUID> lentArrow = ConcurrentHashMap.newKeySet();

    /** Set while a gun's OWN shot damage is being applied, so onPointBlank
     *  doesn't mistake it for a melee swing and cancel it (the 'guns stopped
     *  dealing damage' bug: the plugin was cancelling its own gunfire). */
    private final java.util.Set<java.util.UUID> firing = new java.util.HashSet<>();
    /** When each shooter last dealt bullet damage - lets onPointBlank allow a gun's
     *  own hit even if the damage event is dispatched a tick late (26.x), which
     *  would otherwise see the firing flag already cleared and cancel the shot. */
    private final java.util.Map<java.util.UUID, Long> recentGunHit = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isAiming(Player player) {
        return aiming.contains(player.getUniqueId());
    }

    // ---- aim reticle -------------------------------------------------------
    /** guns:reticle font (see resource-pack/tools/gen_reticle.py): two bracket
     *  glyphs lifted to the crosshair by their ascent, plus wide/narrow spacer
     *  glyphs that set the gap around the cursor. */
    private static final net.kyori.adventure.key.Key RETICLE_FONT =
        net.kyori.adventure.key.Key.key("guns", "reticle");
    private static final String R_LEFT = "";
    private static final String R_RIGHT = "";
    private static final String R_GAP_WIDE = "";     // hip-fire: brackets far out
    private static final String R_GAP_NARROW = "";   // aiming: brackets close in
    private final Map<UUID, Long> reticleHideUntil = new ConcurrentHashMap<>();

    /** Briefly hold the reticle back so a transient gun message (fire mode, empty,
     *  reload...) stays readable before the reticle paints back over the bar. */
    private void suppressReticle(Player player) {
        reticleHideUntil.put(player.getUniqueId(), System.currentTimeMillis() + 1600);
    }

    /** Per-tick: paint the bracket reticle around the crosshair for everyone
     *  holding a gun - WIDE when hip-firing, TIGHT when aiming. It rides the
     *  action bar but the font's ascent lifts it up to cursor level, and the
     *  action bar's centering keeps the brackets symmetric around the cursor. */
    public void tickReticle() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Gun gun = registry.gunOf(player.getInventory().getItemInMainHand());
            if (gun == null) continue;
            manageBobCooldown(player, gun);
            // The bracket reticle is GONE entirely (by request - the brackets that flashed on
            // aim/un-aim). Just keep it cleared; the vanilla crosshair is the aim point.
            Msg.clearReticle(player);
        }
    }

    /** THE bob fix (Leo's option A): the crossbow's own charge->fire->uncharge->reload PUMP is
     *  what visibly jolts the gun on screen every shot. We keep a LOADED gun permanently on an
     *  item-cooldown so vanilla never plays that pump - the plugin fires the arrow manually from
     *  the (still-cancelled) right-click, and the gun just sits in its static charged pose. The
     *  cooldown is re-applied EVERY tick so its white overlay stays completely full and constant
     *  (no sweeping animation - by request) and the use is never unblocked for even one tick.
     *  An EMPTY gun must clear the cooldown, because the reload needs the vanilla charging pull. */
    private void manageBobCooldown(Player player, Gun gun) {
        if (gun.isSpyglass()) return;   // spyglass sniper uses SPYGLASS, not the crossbow pose
        if (registry.ammoOf(player.getInventory().getItemInMainHand()) > 0) {
            player.setCooldown(Material.CROSSBOW, GUN_COOLDOWN_TICKS);
        } else if (player.getCooldown(Material.CROSSBOW) > 0) {
            player.setCooldown(Material.CROSSBOW, 0);   // let the empty gun reload
        }
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
        // the reticle (tickReticle) tightens to the aimed spacing on its own
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_SPYGLASS_USE, 0.5f, 1.4f);
    }

    private void stopAiming(Player player) {
        if (!aiming.remove(player.getUniqueId())) return;
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        swapHeldModel(player, false);
        // the reticle (tickReticle) widens back to the hip-fire spacing on its own
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_SPYGLASS_USE, 0.5f, 1.4f);
    }

    /** Swap the main-hand gun between `<model>` and `<model>_aim`. */
    private void swapHeldModel(Player player, boolean aim) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (registry.gunOf(held) == null) return;
        if (registry.ammoOf(held) <= 0) return;   // empty gun keeps its _emptymag model
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
            && registry.ammoOf(next) > 0   // empty gun stays on its _emptymag model
            && aiming.add(player.getUniqueId())) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, 20 * 3600, 3, true, false));
            ItemStack drawn = player.getInventory().getItem(event.getNewSlot());
            if (drawn != null && applyModelSuffix(drawn, true)) {
                player.getInventory().setItem(event.getNewSlot(), drawn);
            }
        }
        // no longer holding an empty gun to reload? take our lent round back
        Gun drawnGun = registry.gunOf(next);
        if (drawnGun == null || registry.ammoOf(next) > 0) {
            reclaimLentArrow(player);
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
        reclaimLentArrow(player);
    }

    /** Guns are crossbows whose CHARGED state now mirrors their ammo: a gun with
     *  rounds left is kept charged (the aiming pose + firing), and an EMPTY gun is
     *  kept UNCHARGED so that holding right-click plays the crossbow's own reload
     *  (charging) animation - which is the reload mechanic now. Keeps the two in
     *  sync whenever a gun is drawn/interacted, self-healing a stuck state. */
    private void repairPose(ItemStack item) {
        if (item == null || item.getType() != Material.CROSSBOW) return;
        if (registry.gunOf(item) == null) return;
        if (!(item.getItemMeta() instanceof CrossbowMeta meta)) return;
        boolean shouldBeCharged = registry.ammoOf(item) > 0;
        if (shouldBeCharged && !meta.hasChargedProjectiles()) {
            meta.addChargedProjectile(new ItemStack(Material.ARROW));
            item.setItemMeta(meta);
        } else if (!shouldBeCharged && meta.hasChargedProjectiles()) {
            meta.setChargedProjectiles(java.util.List.of());
            item.setItemMeta(meta);
        }
    }

    /** Drop a gun crossbow's charged projectile so the client will play the
     *  natural reload (charging) animation while right-click is held. */
    private void unchargeGun(ItemStack item) {
        if (item.getItemMeta() instanceof CrossbowMeta meta && meta.hasChargedProjectiles()) {
            meta.setChargedProjectiles(java.util.List.of());
            item.setItemMeta(meta);
        }
    }

    /** Set the held gun's model to its empty-magazine variant (shown while reloading). */
    private void showEmptyModel(Player player, ItemStack item, Gun gun) {
        setModelString(item, gun.model() + EMPTY_SUFFIX);
        player.getInventory().setItemInMainHand(item);
    }

    /** Restore the held gun's model to normal (or ironsights if the player is aiming). */
    private void showNormalModel(Player player, ItemStack item, Gun gun) {
        setModelString(item, gun.model() + (aiming.contains(player.getUniqueId()) ? AIM_SUFFIX : ""));
        player.getInventory().setItemInMainHand(item);
    }

    private void setModelString(ItemStack item, String model) {
        var meta = item.getItemMeta();
        if (meta == null) return;
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(java.util.List.of(model));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
    }

    /** The client only animates the crossbow pull if it thinks there's ammo to load. Lend the
     *  player one custom ROUND (custom NBT + texture, not a bare vanilla arrow) if they have no
     *  arrow-type item, so an empty gun still reloads with the real animation. Tracked and
     *  reclaimed on reload/quit. Creative charges without ammo, so no loan there. */
    private void lendArrowFor(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (player.getInventory().contains(Material.ARROW)) return;   // already has an arrow/round to pull
        player.getInventory().addItem(registry.buildRound());
        lentArrow.add(player.getUniqueId());
    }

    /** Take back the ROUND we lent (if any) - the reload consumes a MAGAZINE, not rounds, so the
     *  loaned round must never linger or be spent. Removes OUR tagged round specifically so a
     *  player's real arrows are never touched. */
    private void reclaimLentArrow(Player player) {
        if (!lentArrow.remove(player.getUniqueId())) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (registry.isRound(contents[i])) {
                ItemStack it = contents[i];
                it.setAmount(it.getAmount() - 1);
                player.getInventory().setItem(i, it.getAmount() > 0 ? it : null);
                return;
            }
        }
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
        Player player = event.getPlayer();
        // crossbow gun: no vanilla behavior ever - right fires, LEFT switches the
        // fire mode (semi <-> auto) with no command to type.
        if (right) {
            if (registry.ammoOf(item) <= 0) {
                // EMPTY: the reload. Do NOT cancel - the uncharged crossbow plays
                // its own charging animation while right-click is held (the same
                // button you fire with). onCrossbowLoad finishes the reload when
                // the pull completes. Show the empty-mag model + lend a round so
                // the client actually animates.
                showEmptyModel(player, item, gun);
                lendArrowFor(player);
                return;
            }
            event.setCancelled(true);
            lastTrigger.put(player.getUniqueId(), System.currentTimeMillis());
            // AUTO: hold right-click to keep firing; SEMI: one shot per click.
            if ("auto".equals(registry.fireModeOf(item, gun))) startAuto(player, gun);
            else shoot(player, gun, item);
        } else if (left) {
            event.setCancelled(true);
            toggleMode(player, gun, item);
        }
    }

    /** While seated in a vehicle (a car) your view is filled by the car model, so
     *  right-clicks land on the car ENTITY (PlayerInteractEntityEvent) and never
     *  reach onShoot - which is why you couldn't shoot from a car seat. Fire the
     *  held gun from here too. Gated on isInsideVehicle so it never hijacks the
     *  right-click you use to ENTER a car. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onShootFromVehicle(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.isInsideVehicle()) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(item);
        if (gun == null || gun.isSpyglass()) return;   // spyglass fires on left-click/swing
        event.setCancelled(true);
        lastTrigger.put(player.getUniqueId(), System.currentTimeMillis());
        if ("auto".equals(registry.fireModeOf(item, gun))) startAuto(player, gun);
        else shoot(player, gun, item);
    }

    /** Left-click cycles the held gun's fire mode (only if it offers more than
     *  one). A short cooldown stops a stray double-click double-toggling. */
    private final Map<UUID, Long> modeSwapCd = new ConcurrentHashMap<>();
    private void toggleMode(Player player, Gun gun, ItemStack item) {
        java.util.List<String> modes = gun.modes();
        if (modes.size() < 2) return;
        long now = System.currentTimeMillis();
        Long until = modeSwapCd.get(player.getUniqueId());
        if (until != null && now < until) return;
        modeSwapCd.put(player.getUniqueId(), now + 300);
        String next = modes.get((modes.indexOf(registry.fireModeOf(item, gun)) + 1) % modes.size());
        registry.setFireMode(item, next);
        player.getInventory().setItemInMainHand(item);
        Msg.actionbar(player, Component.text("Fire mode: " + next.toUpperCase(), NamedTextColor.GRAY));
        suppressReticle(player);
        player.playSound(player.getLocation(), "minecraft:block.lever.click", 0.7f, 1.4f);
        ammoBar.update(player, gun, registry.ammoOf(item), next, reserveRounds(player, gun));
    }

    /** How long a LOADED gun is kept on item-cooldown (re-applied every tick, so the value only
     *  has to be big enough that the 1-tick decay is invisible = the overlay reads as constantly,
     *  completely white). See manageBobCooldown. */
    private static final int GUN_COOLDOWN_TICKS = 200;

    /** Since the bob-fix cooldown stops the crossbow from ever entering its vanilla "using" state,
     *  isHandRaised() can no longer tell us the trigger is held for full-auto. Instead every
     *  right-click refreshes a timestamp; auto keeps firing while clicks keep arriving within this
     *  grace window and stops shortly after the trigger is released. */
    private static final long AUTO_GRACE_MS = 300;
    private final Map<UUID, Long> lastTrigger = new ConcurrentHashMap<>();

    /** Players currently auto-firing (one repeating task each). */
    private final Set<UUID> autoFiring = ConcurrentHashMap.newKeySet();

    /** Keep firing an AUTO gun while the trigger is held. shoot() enforces the
     *  fire-rate, so a per-tick call fires exactly at the gun's cadence. Stops the
     *  moment the player releases (hand no longer raised), swaps the gun, empties,
     *  or flips back to semi. */
    private void startAuto(Player player, Gun gun) {
        UUID id = player.getUniqueId();
        if (!autoFiring.add(id)) return;
        new BukkitRunnable() {
            @Override public void run() {
                ItemStack held = player.getInventory().getItemInMainHand();
                Gun g = registry.gunOf(held);
                Long last = lastTrigger.get(id);
                boolean triggerHeld = last != null
                    && (System.currentTimeMillis() - last) <= AUTO_GRACE_MS;
                if (!player.isOnline() || g == null || !g.id().equals(gun.id())
                    || !"auto".equals(registry.fireModeOf(held, g)) || !triggerHeld
                    || registry.ammoOf(held) <= 0) {   // empty: stop so the player can reload
                    autoFiring.remove(id);
                    cancel();
                    return;
                }
                shoot(player, g, held);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Belt and suspenders: no vanilla arrow may ever leave a gun. */
    @EventHandler
    public void onCrossbowFire(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player && registry.gunOf(event.getBow()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * F (swap-hands) = EJECT THE MAGAZINE. It does NOT reload - it just drops the
     * current mag out of the gun, leaving it empty. The rounds that were still in
     * it are BANKED into your reserve pool (never lost, spent last), and the mags
     * themselves stay identical/stackable because the leftovers live on the player,
     * not the item. Reloading is a separate action: hold right-click to play the
     * crossbow animation and load a fresh mag. The swap is always cancelled so a
     * gun never lands in the off-hand.
     */
    @EventHandler
    public void onEjectMag(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Gun gun = registry.gunOf(item);
        if (gun != null || registry.gunOf(event.getOffHandItem()) != null) {
            event.setCancelled(true);   // never swap a gun to the off-hand
        }
        if (gun == null) return;
        if (registry.ammoOf(item) <= 0) {
            // nothing loaded to eject - just remind them how to reload
            Msg.actionbar(player, Component.text("Empty - hold right-click to reload", NamedTextColor.YELLOW));
            suppressReticle(player);
            return;
        }
        // Eject just empties the chamber and readies the hold-right-click reload.
        // Rounds aren't banked (mags are spent by FIRING now), so eject/reload can't
        // be farmed for free ammo.
        registry.setAmmo(item, 0);
        unchargeGun(item);                 // uncharged crossbow -> right-click plays the reload pull
        showEmptyModel(player, item, gun); // the empty/no-mag model
        lendArrowFor(player);              // so the client animates the pull
        player.getWorld().playSound(player.getLocation(), "minecraft:block.iron_trapdoor.open", 0.7f, 1.6f);
        Msg.actionbar(player, Component.text("Magazine out - hold right-click to reload", NamedTextColor.YELLOW));
        suppressReticle(player);
        ammoBar.update(player, gun, 0, registry.fireModeOf(item, gun), reserveRounds(player, gun));
    }

    /**
     * The reload lands here: an EMPTY gun crossbow, held with right-click, plays
     * its natural charging animation and fires this event when the pull completes.
     * We turn that finished charge into "magazine loaded": consume one mag (if the
     * gun uses mags), refill the ammo, restore the normal model, and never consume
     * a real arrow (the loaded round is virtual - guns feed on magazines).
     */
    @EventHandler
    public void onCrossbowLoad(EntityLoadCrossbowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getCrossbow();
        Gun gun = registry.gunOf(item);
        if (gun == null) return;
        // A full gun is charged and can't be re-loaded; only an empty one reloads.
        if (registry.ammoOf(item) > 0) { event.setCancelled(true); return; }
        // Draw the next load: a fresh full mag if we have one, else the banked
        // leftover pool - and nothing at all means refuse the reload.
        int load = drawReload(player, gun);
        if (load < 0) {
            event.setCancelled(true);
            noMagazine(player);
            return;
        }
        event.setConsumeItem(false);   // magazines feed the gun, never real arrows
        reclaimLentArrow(player);
        // Apply the refill next tick, after vanilla has finished charging the
        // crossbow (so the charged state - our "full gun" - and the ammo agree).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            ItemStack now = player.getInventory().getItemInMainHand();
            Gun held = registry.gunOf(now);
            if (held == null || !held.id().equals(gun.id())) return;
            registry.setAmmo(now, load);
            showNormalModel(player, now, held);
            player.getWorld().playSound(player.getLocation(), "minecraft:item.crossbow.loading_end", 1f, 1.2f);
            ammoBar.update(player, held, load, registry.fireModeOf(now, held), reserveRounds(player, held));
        });
    }

    /** First inventory slot holding a mag of this type, or -1. */
    private int findMagSlot(Player player, String magId) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (magId.equals(registry.magIdOf(inv.getItem(i)))) return i;
        }
        return -1;
    }

    // ---- reserve ammo: whole magazines in the inventory --------------------

    /** How many rounds the next load gives, CONSUMING one magazine from the inventory.
     *  So reloading actually costs a mag (no free re-chamber), and reloading a
     *  partly-spent gun discards the partial - standard, non-exploitable. Returns -1
     *  if there's no mag to reload with. */
    private int drawReload(Player player, Gun gun) {
        if (!gun.requiresMag()) return gun.magazine();   // loose-round guns top up full
        int slot = findMagSlot(player, gun.magId());
        if (slot == -1) return -1;
        ItemStack mag = player.getInventory().getItem(slot);
        if (mag.getAmount() <= 1) player.getInventory().setItem(slot, null);
        else mag.setAmount(mag.getAmount() - 1);
        return gun.magazine();
    }

    /** Spare rounds not currently loaded: mags x the gun's magazine size. */
    public int reserveRounds(Player player, Gun gun) {
        if (!gun.requiresMag()) return 0;
        int mags = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (gun.magId().equals(registry.magIdOf(it))) mags += it.getAmount();
        }
        return mags * gun.magazine();
    }

    /** Reload refused: dry click, nothing to feed the gun with. */
    private void noMagazine(Player player) {
        Msg.actionbar(player, Component.text("No magazine.", NamedTextColor.GRAY)
            .decorate(TextDecoration.ITALIC));
        suppressReticle(player);
        player.getWorld().playSound(player.getLocation(), "minecraft:block.dispenser.fail", 0.8f, 1.6f);
    }

    /** A left click that lands ON a target arrives as a melee attack, not
     *  an interact - the gun still fires (and never bonks like a stick). */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onPointBlank(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (firing.contains(player.getUniqueId())) return; // our own bullet - let it through
        Long lastHit = recentGunHit.get(player.getUniqueId());
        if (lastHit != null && System.currentTimeMillis() - lastHit < 150) return; // gun hit dispatched late
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
            Msg.actionbar(player, Component.text("Out of ammo - hold right-click to reload", NamedTextColor.RED));
            suppressReticle(player);
            return;
        }
        // Ammo now lives in RAM (GunRegistry.liveAmmo), so this does NOT rewrite the held item -
        // the item is byte-for-byte identical after a shot, so the client has nothing to re-equip
        // and the gun cannot bob. No setItemInMainHand here on purpose.
        registry.setAmmo(item, ammo - 1);
        if (ammo - 1 <= 0) {
            // that was the last round: uncharge so holding right-click plays the
            // crossbow reload animation, show the empty-mag model, and lend a round
            // so the client animates the pull.
            unchargeGun(item);
            showEmptyModel(player, item, gun);
            lendArrowFor(player);
            Msg.actionbar(player, Component.text("Empty - hold right-click to reload", NamedTextColor.YELLOW));
            suppressReticle(player);
        }
        // NOTE: no hand-dip on fire - the gun used to visibly drop on each shot; the
        // arm-swing is already cancelled by onSwing, so the gun just stays put.
        player.getWorld().playSound(player.getEyeLocation(), gun.sound(), 1f, gun.soundPitch());

        // launch a real projectile: spread cone (tighter while aiming),
        // configurable speed, and a mid-flight arc applied by the tracker
        Vector dir = player.getEyeLocation().getDirection();
        double spread = isAiming(player) ? gun.aimSpread() : gun.spread();
        if (spread > 0) {
            var rng = java.util.concurrent.ThreadLocalRandom.current();
            dir = rotate(dir, Math.toRadians(rng.nextGaussian() * spread * 0.5),
                Math.toRadians(rng.nextGaussian() * spread * 0.5));
        }
        dir.normalize();
        // Fire from the GUN BARREL, not the player's head: the muzzle sits down and to
        // the right of the eye (where the gun is in first-person). Aim the round from
        // there toward the crosshair (a far point down the eye ray) so it still lands
        // where you're looking.
        Location muzzle = barrelLocation(player, dir);
        Vector aimPoint = player.getEyeLocation().toVector().add(dir.clone().multiply(60));
        Vector velocity = aimPoint.clone().subtract(muzzle.toVector()).normalize().multiply(gun.speed());

        // muzzle flash: a small white spark burst at the barrel
        player.getWorld().spawnParticle(Particle.DUST, muzzle, 6, 0.03, 0.03, 0.03, 0,
            new Particle.DustOptions(Color.WHITE, 0.7f));
        ejectCasing(player, gun);   // fling a spent casing out of the ejection port

        // CLOSE-RANGE HITSCAN: a fast no-gravity arrow covers its ENTIRE first tick of
        // travel (several blocks) before the per-tick tracker runs, so a nearby wall is
        // passed and the forward ray-trace misses it (bullet negated, no mark). So we
        // hitscan the first tick's reach here at fire time: anything a solid distance in
        // front - wall or enemy - is resolved now instead of by a clipping arrow. Glass
        // shatters and the shot still spawns to carry on. Beyond this the arrow flies.
        double pbRange = Math.max(4.0, gun.speed() + 1.0);
        Location eye = player.getEyeLocation();
        RayTraceResult pb = player.getWorld().rayTrace(eye, dir, pbRange,
            FluidCollisionMode.NEVER, true, 0.3,
            e -> e instanceof LivingEntity && e != player && !bullets.contains(e.getUniqueId()));
        if (pb != null) {
            if (pb.getHitEntity() instanceof LivingEntity target) {
                applyHit(player, gun, target, pb.getHitPosition().toLocation(player.getWorld()));
                applyRecoil(player, gun);
                return;
            }
            if (pb.getHitBlock() != null) {
                org.bukkit.block.Block b = pb.getHitBlock();
                if (isGlass(b.getType())) {
                    shatterGlass(b);   // break it point-blank; the arrow below carries on
                } else {
                    Location mark = pb.getHitPosition().toLocation(player.getWorld());
                    player.getWorld().spawnParticle(Particle.SMOKE, mark, 3, 0.05, 0.05, 0.05, 0.01);
                    spawnBulletHole(b, mark, pb.getHitBlockFace());
                    applyRecoil(player, gun);
                    return;   // solid wall right in front - round stops here
                }
            }
        }
        Arrow bullet = player.getWorld().spawnArrow(muzzle, velocity, 1f, 0f);
        bullet.setShooter(player);
        bullet.setGravity(false);              // curve is applied manually by the tracker
        bullet.setVelocity(velocity);
        bullet.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        bullet.setPersistent(false);
        bullet.setDamage(0);                   // our applyHit does the damage
        bullet.setCritical(false);
        bullet.setSilent(true);
        var pdc = bullet.getPersistentDataContainer();
        pdc.set(bulletGunKey, PersistentDataType.STRING, gun.id());
        pdc.set(bulletShooterKey, PersistentDataType.STRING, player.getUniqueId().toString());
        pdc.set(bulletBouncesKey, PersistentDataType.INTEGER, gun.ricochet());
        pdc.set(bulletBornKey, PersistentDataType.LONG, System.currentTimeMillis());
        bullets.add(bullet.getUniqueId());
        ammoBar.update(player, gun, ammo - 1, registry.fireModeOf(item, gun), reserveRounds(player, gun));

        applyRecoil(player, gun);
    }

    /** The gun barrel (and muzzle-flash point): forward/right/up from the eye, where
     *  the gun sits in first-person, so bullets and the flash leave the muzzle - not
     *  the player's head. The offset is config-driven with SEPARATE values for the
     *  aim state and the normal (hip) state; tune live with /guns barrel. */
    private Location barrelLocation(Player player, Vector dir) {
        boolean aim = isAiming(player);
        String k = aim ? "barrel.aim." : "barrel.normal.";
        double fwd = plugin.getConfig().getDouble(k + "forward", aim ? 0.9 : 0.7);
        double rt  = plugin.getConfig().getDouble(k + "right",   aim ? 0.0 : 0.28);
        double up  = plugin.getConfig().getDouble(k + "up",      aim ? -0.05 : -0.22);
        // left-handed players hold the gun on the OTHER side, so mirror the sideways
        // offset (the same values, flipped) - the muzzle sits on their left.
        if (player.getMainHand() == org.bukkit.inventory.MainHand.LEFT) rt = -rt;
        Vector forward = dir.clone().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        return player.getEyeLocation()
            .add(forward.multiply(fwd))
            .add(right.multiply(rt))
            .add(0, up, 0);
    }

    /** Recoil feel:
     *  - CAMERA recoil: pitch (gun.recoil, up) + a random left/right yaw (gun.hRecoil), sent as a
     *    rotation-only ClientboundPlayerPositionPacket (relative X_ROT/Y_ROT) so the view moves
     *    without a teleport. Spread over 2 quick pans. Falls back to a relative teleport if NMS
     *    isn't reachable.
     *  - FOV spike: a short high-level Speed burst per shot - the client punches the FOV wider
     *    for a beat (the recoil "kick" feel). */
    private void applyRecoil(Player player, Gun gun) {
        if (player.isInsideVehicle()) return;   // never disturb a seated (driving) player

        // Subtle FOV pulse: a tiny, brief SLOWNESS (not Speed) - it nudges the FOV in a little and
        // reads as a recoil jolt, and slowing (never speeding up) is what makes sense for recoil.
        // Kept small on purpose; turn up fov-spike-level/ticks if you want it stronger.
        if (plugin.getConfig().getBoolean("fov-spike", true)) {
            int lvl = Math.max(0, plugin.getConfig().getInt("fov-spike-level", 0));   // 0 = Slowness I
            int ticks = Math.max(1, plugin.getConfig().getInt("fov-spike-ticks", 2));
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, ticks, lvl, true, false, false));
        }

        if (!plugin.getConfig().getBoolean("camera-recoil", true)) return;
        double up = gun.recoil(), side = gun.hRecoil();
        if (up <= 0 && side <= 0) return;
        final int steps = 10;   // 10 pans - the rotation packet lets us pan smoothly & cheaply
        final float yawSign = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1f : -1f;
        // easeOutBack: the cumulative pan rises PAST the target then eases back to EXACTLY it - a
        // bouncy overshoot. Per-step deltas sum to the set recoil amount (f(0)=0, f(1)=1).
        final double c1 = Math.max(0.0, plugin.getConfig().getDouble("recoil-overshoot", 1.70158));
        final double c3 = c1 + 1.0;
        final float[] pitch = new float[steps];
        final float[] yaw = new float[steps];
        double prevF = 0.0;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps, tb = t - 1.0;
            double f = 1.0 + c3 * tb * tb * tb + c1 * tb * tb;   // overshoots ~+10% mid-way, ends at 1
            float inc = (float) (f - prevF);
            prevF = f;
            pitch[i - 1] = (float) (-up) * inc;                 // up = negative pitch delta
            yaw[i - 1] = yawSign * (float) side * inc;
        }
        for (int i = 0; i < steps; i++) {
            final int idx = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isInsideVehicle()) return;
                if (!NmsRecoil.sendRotation(player, yaw[idx], pitch[idx])) {
                    teleportRotate(player, yaw[idx], pitch[idx]);   // fallback: relative teleport
                }
            }, i);   // ticks 0..9
        }
    }

    /** Fallback camera nudge when the position packet can't be sent: a relative teleport that keeps
     *  X/Y/Z but sets pitch/yaw absolutely (so position never jumps). */
    private void teleportRotate(Player player, float yawDelta, float pitchDelta) {
        try {
            Location aim = player.getLocation();
            aim.setPitch((float) Math.max(-90.0, Math.min(90.0, aim.getPitch() + pitchDelta)));
            aim.setYaw(aim.getYaw() + yawDelta);
            player.teleport(aim, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
                io.papermc.paper.entity.TeleportFlag.Relative.X,
                io.papermc.paper.entity.TeleportFlag.Relative.Y,
                io.papermc.paper.entity.TeleportFlag.Relative.Z);
        } catch (Throwable t) {
            // recoil is cosmetic - never let it break firing
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

    // ---- projectile bullets ----------------------------------------------

    private final NamespacedKey bulletGunKey;
    private final NamespacedKey bulletShooterKey;
    private final NamespacedKey bulletBouncesKey;
    private final NamespacedKey bulletBornKey;
    /** Stamped on a player victim (shooter UUID + when) so gun kills credit the
     *  shooter in stats even when the killing blow is source-less (PvP off). */
    private final NamespacedKey gunAttackerKey;
    private final NamespacedKey gunAttackerAtKey;
    private final java.util.Set<java.util.UUID> bullets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long BULLET_LIFETIME_MS = 5000;

    /** Every tick: arc live bullets down by their gun's curve, trail them,
     *  and retire the spent ones. One global task, not one per shot. */
    public void bulletTick() {
        for (java.util.UUID id : bullets.toArray(new java.util.UUID[0])) {
            Entity e = plugin.getServer().getEntity(id);
            if (!(e instanceof Arrow bullet) || bullet.isDead() || !bullet.isValid()) {
                bullets.remove(id);
                continue;
            }
            var pdc = bullet.getPersistentDataContainer();
            long born = pdc.getOrDefault(bulletBornKey, PersistentDataType.LONG, 0L);
            if (System.currentTimeMillis() - born > BULLET_LIFETIME_MS || bullet.isOnGround()) {
                bullet.remove();
                bullets.remove(id);
                continue;
            }
            Gun gun = registry.get(pdc.get(bulletGunKey, PersistentDataType.STRING));
            if (gun != null && gun.curve() > 0) {
                Vector v = bullet.getVelocity();
                v.setY(v.getY() - gun.curve() * 0.08);
                bullet.setVelocity(v);
            }

            // Manual hit detection: fast, no-gravity arrows routinely TUNNEL through
            // players AND walls between ticks, so ProjectileHitEvent fires late or not
            // at all - which is why bullets failed to mark/damage at close (and any)
            // range. Ray-trace this tick's travel segment ourselves, for BOTH entities
            // and blocks, and resolve whichever is nearer.
            if (gun != null) {
                Vector vel = bullet.getVelocity();
                double reach = vel.length() + 0.5;
                if (reach > 0.01) {
                    Vector dir = vel.clone().normalize();
                    Location from = bullet.getLocation();
                    String shooterId = pdc.get(bulletShooterKey, PersistentDataType.STRING);
                    Player shooter = shooterId == null ? null
                        : plugin.getServer().getPlayer(java.util.UUID.fromString(shooterId));
                    org.bukkit.util.RayTraceResult ent = bullet.getWorld().rayTraceEntities(
                        from, dir, reach, 0.35,
                        e2 -> e2 instanceof LivingEntity && e2 != shooter
                            && !bullets.contains(e2.getUniqueId()));
                    org.bukkit.util.RayTraceResult blk = bullet.getWorld().rayTraceBlocks(
                        from, dir, reach, FluidCollisionMode.NEVER, true);
                    double entD = ent != null ? ent.getHitPosition().distanceSquared(from.toVector()) : Double.MAX_VALUE;
                    double blkD = blk != null && blk.getHitBlock() != null
                        ? blk.getHitPosition().distanceSquared(from.toVector()) : Double.MAX_VALUE;
                    // entity first if it's nearer than the block
                    if (ent != null && ent.getHitEntity() instanceof LivingEntity target && entD <= blkD) {
                        applyHit(shooter, gun, target, ent.getHitPosition().toLocation(bullet.getWorld()));
                        bullet.remove(); bullets.remove(id); continue;
                    }
                    if (blk != null && blk.getHitBlock() != null) {
                        org.bukkit.block.Block b = blk.getHitBlock();
                        if (isGlass(b.getType())) {
                            shatterGlass(b);   // punch through, keep flying
                        } else {
                            int bounces = pdc.getOrDefault(bulletBouncesKey, PersistentDataType.INTEGER, 0);
                            Vector nrm = blk.getHitBlockFace() == null ? null : blk.getHitBlockFace().getDirection();
                            if (bounces > 0 && nrm != null
                                    && canRicochet(vel, nrm, gun == null ? 0 : gun.ricochetAngle())) {
                                bullet.setVelocity(vel.subtract(nrm.multiply(2 * vel.dot(nrm))));
                                pdc.set(bulletBouncesKey, PersistentDataType.INTEGER, bounces - 1);
                                bullet.getWorld().playSound(from, "minecraft:block.chain.hit", 0.7f, 1.8f);
                            } else {
                                Location mark = blk.getHitPosition().toLocation(bullet.getWorld());
                                bullet.getWorld().spawnParticle(Particle.SMOKE, mark, 3, 0.05, 0.05, 0.05, 0.01);
                                spawnBulletHole(b, mark, blk.getHitBlockFace());
                                bullet.remove(); bullets.remove(id); continue;
                            }
                        }
                    }
                }
            }
            bullet.getWorld().spawnParticle(Particle.CRIT, bullet.getLocation(), 1, 0, 0, 0, 0);
        }
    }

    /** A bullet lands: apply the gun's hit to a living target, or bounce/expire. */
    @EventHandler
    public void onBulletHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow bullet)) return;
        var pdc = bullet.getPersistentDataContainer();
        String gunId = pdc.get(bulletGunKey, PersistentDataType.STRING);
        if (gunId == null) return;
        Gun gun = registry.get(gunId);
        String shooterId = pdc.get(bulletShooterKey, PersistentDataType.STRING);
        Player shooter = shooterId == null ? null
            : plugin.getServer().getPlayer(java.util.UUID.fromString(shooterId));

        if (event.getHitEntity() instanceof LivingEntity target
            && target != shooter && gun != null) {
            applyHit(shooter, gun, target, bullet.getLocation());
            bullet.remove();
            bullets.remove(bullet.getUniqueId());
            return;
        }
        if (event.getHitBlock() != null) {
            // Bullets punch through glass: shatter the pane and keep flying, so you
            // can shoot out a window (or through it at whoever's behind it).
            org.bukkit.block.Block block = event.getHitBlock();
            if (isGlass(block.getType())) {
                shatterGlass(block);
                event.setCancelled(true);   // don't let the arrow stick - it carries on
                return;
            }
            int bounces = pdc.getOrDefault(bulletBouncesKey, PersistentDataType.INTEGER, 0);
            if (bounces > 0 && event.getHitBlockFace() != null && gun != null) {
                Vector normal = event.getHitBlockFace().getDirection();
                Vector v = bullet.getVelocity();
                if (canRicochet(v, normal, gun.ricochetAngle())) {
                    Vector reflected = v.subtract(normal.multiply(2 * v.dot(normal)));
                    event.setCancelled(true);
                    bullet.setVelocity(reflected);
                    pdc.set(bulletBouncesKey, PersistentDataType.INTEGER, bounces - 1);
                    bullet.getWorld().playSound(bullet.getLocation(), "minecraft:block.chain.hit", 0.7f, 1.8f);
                    return;
                }
            }
            bullet.getWorld().spawnParticle(Particle.SMOKE, bullet.getLocation(), 3, 0.05, 0.05, 0.05, 0.01);
            spawnBulletHole(event.getHitBlock(), bullet.getLocation(), event.getHitBlockFace());
            bullet.remove();
            bullets.remove(bullet.getUniqueId());
        }
    }

    /** Leave a small bullet-hole decal on the wall a bullet stopped against - an
     *  ItemDisplay of the guns:bullet_hole sprite, laid flat on the struck face and
     *  removed after 15 seconds. */
    private void spawnBulletHole(org.bukkit.block.Block wall, Location hit, org.bukkit.block.BlockFace face) {
        if (face == null || wall == null || wall.getWorld() == null) return;
        Vector n = face.getDirection();
        // Pin the decal exactly onto the struck FACE PLANE at the hit point, so it
        // lies on the surface instead of floating or sinking into the block.
        double px = hit.getX(), py = hit.getY(), pz = hit.getZ();
        switch (face) {
            case UP -> py = wall.getY() + 1.0;
            case DOWN -> py = wall.getY();
            case NORTH -> pz = wall.getZ();
            case SOUTH -> pz = wall.getZ() + 1.0;
            case WEST -> px = wall.getX();
            case EAST -> px = wall.getX() + 1.0;
            default -> { }
        }
        Location loc = new Location(wall.getWorld(), px, py, pz).add(n.clone().multiply(0.015));
        ItemStack holeItem = new ItemStack(org.bukkit.Material.FLINT);
        var meta = holeItem.getItemMeta();
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(java.util.List.of("bullet_hole"));
        meta.setCustomModelDataComponent(cmd);
        holeItem.setItemMeta(meta);
        org.bukkit.entity.ItemDisplay disp = wall.getWorld().spawn(loc,
            org.bukkit.entity.ItemDisplay.class, d -> {
                d.setItemStack(holeItem);
                // NEVER persist a bullet hole: if the server stops/crashes before its 15s removal
                // task runs, a persistent display would linger forever. Non-persistent displays
                // aren't saved with the chunk, so they're gone on restart no matter what. The tag
                // lets onEnable sweep any legacy (persistent) holes left by older versions.
                d.setPersistent(false);
                d.addScoreboardTag(TAG_BULLET_HOLE);
                d.getPersistentDataContainer().set(bulletBornKey, PersistentDataType.LONG,
                    System.currentTimeMillis());
                // FIXED = the item-frame context: the flat item is centred and faces
                // outward, exactly like a picture on a wall - the right base for a decal.
                d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                d.setViewRange(0.5f);   // only visible up close; dynamic light (no fixed brightness)
                // Orient the sprite's face (+Z) to the wall normal, and SQUASH the
                // model's depth to ~0 so the extruded item becomes a flat sheet lying
                // flush on the surface - no 3D lump poking through the wall.
                org.joml.Quaternionf rot = new org.joml.Quaternionf().rotationTo(
                    0f, 0f, 1f, (float) n.getX(), (float) n.getY(), (float) n.getZ());
                d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(0f, 0f, 0f), rot,
                    new org.joml.Vector3f(0.3f, 0.3f, 0.02f), new org.joml.Quaternionf()));
            });
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> { if (disp.isValid()) disp.remove(); }, 300L);   // 15 s
    }

    /** Shatter a glass block (sound + particles) and respawn the exact pane after
     *  2 minutes if the space is still empty. Shared by normal and point-blank hits. */
    private void shatterGlass(org.bukkit.block.Block block) {
        final org.bukkit.block.data.BlockData data = block.getBlockData();
        final Location loc = block.getLocation();
        block.getWorld().playSound(loc, org.bukkit.Sound.BLOCK_GLASS_BREAK, 1f, 1f);
        block.getWorld().spawnParticle(Particle.BLOCK,
            loc.clone().add(0.5, 0.5, 0.5), 20, 0.25, 0.25, 0.25, 0, data);
        block.setType(org.bukkit.Material.AIR);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (loc.getBlock().getType() == org.bukkit.Material.AIR) {
                loc.getBlock().setBlockData(data);
                loc.getWorld().playSound(loc, org.bukkit.Sound.BLOCK_GLASS_PLACE, 0.7f, 1.2f);
            }
        }, 2400L);   // 120s
    }

    /** Glass, stained glass, tinted glass and all their panes - the blocks a
     *  bullet shatters and passes through. */
    private boolean isGlass(org.bukkit.Material m) {
        String n = m.name();
        return n.endsWith("GLASS") || n.endsWith("GLASS_PANE");
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

        // Ballistic armour: a worn vest may absorb the round, get chewed up, or shatter. This
        // returns the damage that actually reaches the player (0 if the vest ate it).
        if (target instanceof Player armored) {
            damage = resolveArmor(armored, gun, damage);
            if (damage <= 0) {   // fully absorbed - no hit to deal, but still show the impact spark
                target.getWorld().spawnParticle(Particle.CRIT, end, 6, 0.1, 0.1, 0.1, 0.03);
                target.setVelocity(target.getVelocity());
                return;
            }
        }

        // A bullet is not a knockback stick: keep the victim's own momentum through
        // the hit so a gunner can't shove a melee player away for free. We snapshot
        // the velocity and restore it right after the damage (which is where vanilla
        // would otherwise apply attack knockback). Damage still lands in full.
        Vector preHit = target.getVelocity();
        double hpBefore = target.getHealth();
        // Credit gun KILLS in the menu stats even when PvP is off: that path lands
        // as source-less damage below, so the death's getKiller() is null. Stamp the
        // shooter + timestamp on the victim; Facility's StatsListener reads it on
        // death and credits the kill (keys are the shared "guns:" namespace).
        if (target instanceof Player victimPlayer && shooter != null) {
            var vpdc = victimPlayer.getPersistentDataContainer();
            vpdc.set(gunAttackerKey, PersistentDataType.STRING, shooter.getUniqueId().toString());
            vpdc.set(gunAttackerAtKey, PersistentDataType.LONG, System.currentTimeMillis());
        }
        if (shooter != null) {
            recentGunHit.put(shooter.getUniqueId(), System.currentTimeMillis());
            firing.add(shooter.getUniqueId());
            try {
                target.damage(damage, shooter);
            } finally {
                firing.remove(shooter.getUniqueId());
            }
        } else {
            target.damage(damage);   // shooter left the server - still deal the hit
        }
        // If PvP is off (server.properties pvp=false or a world/region flag), the
        // credited player-vs-player hit above is silently cancelled by the game -
        // which is exactly why bullets hurt mobs but not players. When bypass-pvp
        // is on (default), force the hit through as source-less damage so guns
        // still work. Enable real PvP for full kill-credit instead.
        if (target instanceof Player && damage > 0
                && target.getHealth() >= hpBefore - 0.001
                && !target.isDead()
                && plugin.getConfig().getBoolean("bypass-pvp", true)) {
            target.setNoDamageTicks(0);
            target.damage(damage);
        }
        target.setVelocity(preHit);
        target.getWorld().spawnParticle(Particle.CRIT, end, 8, 0.1, 0.1, 0.1, 0.05);
        if (part != null) {
            ((Player) target).sendActionBar(Component.text("You were shot in the " + part + ".",
                NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
            suppressReticle((Player) target);   // don't let the victim's reticle eat the message
            if (part.equals("head") && shooter != null) {
                Msg.actionbar(shooter, Component.text("Headshot.", NamedTextColor.GRAY)
                    .decorate(TextDecoration.ITALIC));
                suppressReticle(shooter);
            }
        }
        applyEffect(shooter, gun, target);
    }

    /** Run a bullet against the victim's worn vest. Returns the damage that reaches the player:
     *  0 if the vest absorbed it, else the (near-)full amount. Degrades or shatters the vest.
     *  - round outclasses the vest (pierce >= tier): punches through, vest shatters, full damage.
     *  - vest rated above the round (pierce < tier): 55% absorb / 45% pierce, scaled by wear; the
     *    vest loses 33% (one tier up) or 20% (higher) protection per hit, +5% more on a pierce,
     *    and shatters at 0%. */
    private double resolveArmor(Player victim, Gun gun, double damage) {
        ItemStack chest = victim.getInventory().getChestplate();
        int vTier = registry.vestTier(chest);
        if (vTier <= 0) return damage;                 // not wearing a vest
        int pierce = gun.pierce();

        if (pierce >= vTier) {                          // outclassed -> straight through, shatters
            breakVest(victim);
            return damage;
        }

        int prot = registry.vestProt(chest);
        double absorbChance = 0.55 * (prot / 100.0);
        boolean absorbed = java.util.concurrent.ThreadLocalRandom.current().nextDouble() < absorbChance;
        int wear = (vTier == pierce + 1) ? 33 : 20;     // 33% one tier above, 20% for higher tiers
        if (!absorbed) wear += 5;                        // a pierce chews it a bit more
        int newProt = prot - wear;

        if (newProt <= 0) breakVest(victim);
        else { registry.setVestProt(chest, newProt); victim.getInventory().setChestplate(chest); }

        if (absorbed) {
            victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
            Msg.actionbar(victim, Component.text("Vest absorbed the round (" + Math.max(0, newProt) + "%)",
                NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
            return 0;
        }
        victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ITEM_TRIDENT_HIT, 1f, 1.2f);
        Msg.actionbar(victim, Component.text("Your vest was pierced!", NamedTextColor.RED)
            .decorate(TextDecoration.ITALIC));
        return damage;
    }

    private void breakVest(Player victim) {
        victim.getInventory().setChestplate(null);
        victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BREAK, 1f, 0.7f);
        Msg.actionbar(victim, Component.text("Your ballistic vest shattered!", NamedTextColor.RED)
            .decorate(TextDecoration.ITALIC));
    }

    /** Sweep ALL bullet-hole decals in loaded chunks - called once on enable to clear legacy
     *  persistent holes from older versions (new ones are non-persistent). */
    public void sweepBulletHoles() {
        for (org.bukkit.World w : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.ItemDisplay d : w.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class)) {
                if (d.getScoreboardTags().contains(TAG_BULLET_HOLE)) d.remove();
            }
        }
    }

    /** Safety net: remove any bullet hole older than 15s whose own removal task was lost (e.g. a
     *  crash/restart right as it spawned). Runs periodically; born-time is stamped on each hole. */
    public void sweepAgedBulletHoles() {
        long now = System.currentTimeMillis();
        for (org.bukkit.World w : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.ItemDisplay d : w.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class)) {
                if (!d.getScoreboardTags().contains(TAG_BULLET_HOLE)) continue;
                long born = d.getPersistentDataContainer().getOrDefault(bulletBornKey, PersistentDataType.LONG, 0L);
                if (born == 0L || now - born >= 15_000L) d.remove();
            }
        }
    }

    /** Ricochet only on a shallow/grazing hit: the angle between the round's path and the SURFACE
     *  must be <= maxSurfaceAngle degrees. A head-on hit (near the normal) never bounces. 0 = off. */
    private boolean canRicochet(Vector velocity, Vector normal, double maxSurfaceAngle) {
        if (maxSurfaceAngle <= 0 || velocity.lengthSquared() < 1e-9) return false;
        Vector v = velocity.clone().normalize();
        Vector n = normal.clone().normalize();
        double angleToNormal = Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(v.dot(n)))));
        return (90.0 - angleToNormal) <= maxSurfaceAngle;
    }

    /** Eject a small custom-modelled brass casing on each shot. Direction + spawn offset are the
     *  gun's casingDir / casingPos ("right,up,forward" relative to aim); "off" disables it. */
    private void ejectCasing(Player player, Gun gun) {
        String dirSpec = gun.casingDir();
        if (dirSpec == null || dirSpec.equalsIgnoreCase("off") || dirSpec.equalsIgnoreCase("none")) return;
        double[] d = parse3(dirSpec, new double[]{1, 0.6, -0.1});
        double[] o = parse3(gun.casingPos(), new double[]{0.3, -0.2, 0.35});
        Vector fwd = player.getEyeLocation().getDirection().normalize();
        Vector up = new Vector(0, 1, 0);
        Vector right = fwd.clone().crossProduct(up);
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0); else right.normalize();
        Location spawn = player.getEyeLocation().clone()
            .add(right.clone().multiply(o[0])).add(0, o[1], 0).add(fwd.clone().multiply(o[2]));
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        Vector vel = right.clone().multiply(d[0]).add(up.clone().multiply(d[1])).add(fwd.clone().multiply(d[2]))
            .multiply(0.18)
            .add(new Vector((rnd.nextDouble() - 0.5) * 0.05, rnd.nextDouble() * 0.04, (rnd.nextDouble() - 0.5) * 0.05));
        ItemStack casing = new ItemStack(org.bukkit.Material.IRON_NUGGET);
        var meta = casing.getItemMeta();
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(java.util.List.of("bullet_casing"));
        meta.setCustomModelDataComponent(cmd);
        casing.setItemMeta(meta);
        org.bukkit.entity.Item drop = player.getWorld().dropItem(spawn, casing);
        drop.setVelocity(vel);
        drop.setPickupDelay(Integer.MAX_VALUE);   // never picked up
        drop.setPersistent(false);
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> { if (drop.isValid()) drop.remove(); }, 40L);   // 2s then gone
        player.getWorld().playSound(spawn, "minecraft:block.metal.hit", 0.25f, 1.9f);
    }

    private double[] parse3(String s, double[] def) {
        if (s == null) return def;
        String[] p = s.split("[ ,]+");
        if (p.length != 3) return def;
        try { return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])}; }
        catch (NumberFormatException e) { return def; }
    }

    /** Once a second: heavy vests (BALLISTIC and up) slow the wearer, heaviest most. */
    public void armorTick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Armor a = Armor.byTier(registry.vestTier(p.getInventory().getChestplate()));
            if (a == null || a.slowness < 0) continue;
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, 40, a.slowness, true, false, false));
        }
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

    /** Apply a gun's on-hit effect(s) to the thing it hit. A gun can now carry SEVERAL effects
     *  at once, semicolon-separated, so a non-lethal weapon can stack a convincing jolt - e.g. a
     *  TASER "slowness:6:80;weakness:3:80;nausea:1:80;blindness:1:30" (set its damage to 0 or ~0.5),
     *  or a BB gun with just low damage and no effect. Each token is "bleed" or a potion name with
     *  optional "POTION:level:ticks"; when level/ticks are omitted a token falls back to the gun's
     *  effect-level / effect-ticks, so a plain single value (the old "slowness") behaves as before. */
    private void applyEffect(Player shooter, Gun gun, LivingEntity target) {
        String raw = gun.effect() == null ? "none" : gun.effect().trim();
        if (raw.isEmpty() || raw.equalsIgnoreCase("none")) return;
        for (String token : raw.split(";")) applyOneEffect(shooter, gun, target, token.trim());
    }

    private void applyOneEffect(Player shooter, Gun gun, LivingEntity target, String token) {
        if (token.isEmpty()) return;
        String[] parts = token.split(":");
        String name = parts[0].trim().toLowerCase();
        if (name.equals("none")) return;
        int level = parts.length > 1 ? parseIntOr(parts[1], gun.effectLevel()) : gun.effectLevel();
        int ticks = parts.length > 2 ? parseIntOr(parts[2], gun.effectTicks()) : gun.effectTicks();

        if (name.equals("bleed")) {
            // Custom bleed: `level` raw damage once per second while the timer runs.
            int pulses = Math.max(1, ticks / 20);
            final int dps = Math.max(1, level);
            new BukkitRunnable() {
                int left = pulses;
                @Override public void run() {
                    if (left-- <= 0 || !target.isValid() || target.isDead()) { cancel(); return; }
                    target.damage(dps, shooter);
                    target.getWorld().spawnParticle(Particle.DUST,
                        target.getLocation().add(0, target.getHeight() / 2, 0), 6, 0.2, 0.3, 0.2, 0, BLOOD);
                }
            }.runTaskTimer(plugin, 20L, 20L);
            return;
        }

        @SuppressWarnings("deprecation")
        PotionEffectType type = PotionEffectType.getByName(name);
        if (type == null) {
            plugin.getLogger().warning("Gun '" + gun.id() + "' has unknown effect '" + token
                + "' - use 'bleed' or a potion name (slowness, weakness, nausea, blindness, poison...).");
            return;
        }
        target.addPotionEffect(new PotionEffect(type, Math.max(1, ticks), Math.max(0, level - 1)));
    }

    private int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
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
