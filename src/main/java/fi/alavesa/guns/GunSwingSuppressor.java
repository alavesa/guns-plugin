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

        // CounterMine-style FULL-AUTO: the client streams inbound arm-swing packets while LEFT-click is held.
        // We LISTEN (never cancel - the server derives the fire interact from this very packet) and fire the
        // gun on each swing (hopped to the main thread). Holding an auto gun sprays; releasing stops at once.
        protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.MONITOR,
                PacketType.Play.Client.ARM_ANIMATION) {     // client -> server "I swung my arm" (left-click)
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null) return;
                if (registry.gunOf(player.getInventory().getItemInMainHand()) == null) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> shootListener.swingFire(player));
            }
        });
        shootListener.swingFireActive = true;
        plugin.getLogger().info("ProtocolLib detected - gun swing hidden from others + swing-driven full-auto enabled.");
    }
}
