package fi.alavesa.guns;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Suppresses the arm-swing (punch) animation while a player holds a gun. ProtocolLib half of the "steady
 * weapon" feel; only loaded when ProtocolLib is installed (GunsPlugin guards the call).
 *
 * IMPORTANT - we cancel the OUTBOUND animation, never the inbound one:
 *
 *  - The client's inbound swing packet is ALSO how the server derives a LEFT-CLICK (PlayerInteractEvent
 *    LEFT_CLICK_AIR) - which is how the gun FIRES. Cancelling that inbound packet stops the gun firing
 *    entirely, so we must not touch it.
 *  - Instead we cancel the OUTBOUND EntityAnimation (server -> nearby clients) for the swing action, so
 *    OTHER players never see a gun holder's swing (third-person: fully removed) while firing still works.
 *  - The shooter's OWN first-person swing is client-predicted and can't be cancelled server-side; it's
 *    mitigated separately by a held-slot re-equip on each shot (see ShootListener). A full first-person
 *    removal needs a client mod.
 */
final class GunSwingSuppressor {

    private GunSwingSuppressor() { }

    static void register(Plugin plugin, GunRegistry registry, ShootListener shootListener) {
        ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
        protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.ANIMATION) {     // server -> other clients "entity swung"

            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer p = event.getPacket();
                int action = p.getIntegers().read(1);          // 0 = swing main hand, 3 = swing off hand
                if (action != 0 && action != 3) return;        // leave hurt/crit/etc. animations alone
                int entityId = p.getIntegers().read(0);
                for (Player pl : event.getPlayer().getWorld().getPlayers()) {
                    if (pl.getEntityId() == entityId) {
                        // The swinging entity is a player: hide the swing from others only if they hold a gun.
                        if (registry.gunOf(pl.getInventory().getItemInMainHand()) != null) event.setCancelled(true);
                        return;
                    }
                }
            }
        });

        // INBOUND diagnostic + auto-fire driver. We listen (never cancel) to the three packet types that a
        // held LEFT-click could stream, count them for /guns swingdebug, and FIRE the gun on dig / attack
        // packets (arm-swing is already handled by onSwing). Whichever type STREAMS while LEFT is held drives
        // continuous full-auto - and the live counter shows exactly which one that is on this client.
        protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.MONITOR,
                PacketType.Play.Client.ARM_ANIMATION,
                PacketType.Play.Client.BLOCK_DIG,
                PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null) return;
                if (registry.gunOf(player.getInventory().getItemInMainHand()) == null) return;
                PacketType type = event.getPacketType();
                final int idx = type == PacketType.Play.Client.ARM_ANIMATION ? 0
                              : type == PacketType.Play.Client.BLOCK_DIG ? 1 : 2;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    shootListener.debugPacket(player, idx);
                    // Fire ONLY on the block-dig stream (left-click mining). NOT on USE_ENTITY: right-click
                    // sends USE_ENTITY (interact) too, and firing on that made the gun shoot on right-click.
                    if (idx == 1) shootListener.swingFire(player);   // swings are handled by onSwing
                });
            }
        });
        plugin.getLogger().info("ProtocolLib detected - swing hidden from others + swing/dig/attack fire+debug.");
    }
}
