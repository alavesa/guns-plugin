package fi.alavesa.guns;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Suppresses the arm-swing (punch) animation while a player holds a gun. This is the ProtocolLib half of the
 * gun's "steady weapon" feel; it references ProtocolLib, so it is ONLY loaded when ProtocolLib is installed
 * (GunsPlugin guards the constructor call).
 *
 * There are TWO independent swings and each needs a different fix:
 *
 *  1) THIRD PERSON (what OTHER players see) is server-authoritative. The client sends a Serverbound arm
 *     animation packet (PacketPlayInArmAnimation / ARM_ANIMATION); the server then broadcasts an
 *     EntityAnimation to nearby players. Cancelling that inbound packet means the server never relays it, so
 *     nobody around the shooter sees the swing. This is a complete fix.
 *
 *  2) FIRST PERSON (what the SHOOTER sees) is CLIENT-PREDICTED: the client plays its own swing locally the
 *     instant the button is pressed, before the server is told. No inbound cancel can undo it. The one lever
 *     left is to force the client to REDRAW the held item - re-sending the held hotbar slot (SetSlot) makes
 *     the client re-equip, resetting the hand's animation state and cutting the swing short. It's a
 *     mitigation (a frame or two can slip through), not a perfect removal - vanilla exposes no way to fully
 *     cancel a self-predicted swing.
 */
final class GunSwingSuppressor {

    private GunSwingSuppressor() { }

    static void register(Plugin plugin, GunRegistry registry) {
        ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
        protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Client.ARM_ANIMATION) {     // client -> server "I swung"

            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null) return;
                // Only while a gun is held - melee/tools keep their normal swing.
                if (registry.gunOf(player.getInventory().getItemInMainHand()) == null) return;

                // (1) Cancel the inbound swing: the server never relays it -> no third-person animation.
                event.setCancelled(true);

                // (2) The shooter's own client already predicted the swing; force a held-item redraw next
                // tick to reset the hand and cut it short. Packet events run off-thread, so hop to main.
                new BukkitRunnable() {
                    @Override public void run() {
                        if (player.isOnline() && registry.gunOf(player.getInventory().getItemInMainHand()) != null) {
                            resyncHeldSlot(protocol, player);
                        }
                    }
                }.runTask(plugin);
            }
        });
        plugin.getLogger().info("ProtocolLib detected - gun arm-swing suppressed (third-person cancelled, "
            + "first-person reset via held-slot resync).");
    }

    /**
     * Re-send a SetSlot (PacketPlayOutSetSlot) for ONLY the held hotbar slot, back to that same player. The
     * client treats a fresh item in the slot it's already holding as a re-equip: it resets the first-person
     * hand animation (killing the mid-swing arc) and snaps the client's hand state back in sync with the
     * server after the cancelled inbound swing - so the weapon stays steady instead of stuttering.
     */
    private static void resyncHeldSlot(ProtocolManager protocol, Player player) {
        try {
            int containerSlot = 36 + player.getInventory().getHeldItemSlot();   // hotbar 0..8 -> container 36..44
            PacketContainer setSlot = protocol.createPacket(PacketType.Play.Server.SET_SLOT);
            setSlot.getIntegers().write(0, 0);              // window id 0 = the player's own inventory
            setSlot.getIntegers().write(1, 0);              // state id (1.17.1+); 0 is fine for a forced resync
            setSlot.getIntegers().write(2, containerSlot);  // the exact slot to refresh (the held gun)
            setSlot.getItemModifier().write(0, player.getInventory().getItemInMainHand());
            protocol.sendServerPacket(player, setSlot);
        } catch (Throwable t) {
            player.updateInventory();   // version-proof fallback: re-sends the whole inventory, same effect
        }
    }
}
