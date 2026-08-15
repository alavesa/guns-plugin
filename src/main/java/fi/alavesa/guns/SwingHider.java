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
 * Hides the gun swing from OTHER players via ProtocolLib. Cancels the outbound ANIMATION packet
 * (action 0 = swing main hand, 3 = swing off hand) whenever the swinging entity is a player holding a
 * gun - so nobody sees the wild full-auto arm. This class references ProtocolLib, so it is ONLY loaded
 * when ProtocolLib is installed (GunsPlugin guards the register() call); the plugin runs fine without it
 * (Paper's cancelled PlayerArmSwingEvent already hides the swing from others - this is the finer-grained
 * packet-level version for those who run ProtocolLib).
 */
final class SwingHider {

    private SwingHider() { }

    static void register(Plugin plugin, GunRegistry registry) {
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.ANIMATION) {
            @Override public void onPacketSending(PacketEvent event) {
                PacketContainer p = event.getPacket();
                int action = p.getIntegers().read(1);          // 0 = swing main, 3 = swing off
                if (action != 0 && action != 3) return;        // leave hurt/crit/etc. alone
                int entityId = p.getIntegers().read(0);
                for (Player pl : event.getPlayer().getWorld().getPlayers()) {
                    if (pl.getEntityId() == entityId) {
                        if (registry.gunOf(pl.getInventory().getItemInMainHand()) != null) event.setCancelled(true);
                        return;
                    }
                }
            }
        });
        plugin.getLogger().info("ProtocolLib detected - gun arm-swing hidden from other players at packet level.");
    }
}
