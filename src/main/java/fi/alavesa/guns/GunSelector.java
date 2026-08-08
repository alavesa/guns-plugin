package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scroll-wheel weapon selector. With 2+ guns in the inventory, the mouse wheel no longer changes the
 * held hotbar slot (it's LOCKED on slot 0); instead it cycles a backend loadout of the player's guns,
 * showing a selector HUD. When scrolling STOPS (~2 ticks) the chosen gun is swapped into slot 0 (its
 * CustomModelData model becomes the held weapon). No ProtocolLib needed - PlayerItemHeldEvent + cancel.
 */
public final class GunSelector implements Listener, Runnable {

    private final Plugin plugin;
    private final GunRegistry registry;
    private final Map<UUID, Integer> sel = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastScroll = new ConcurrentHashMap<>();

    public GunSelector(Plugin plugin, GunRegistry registry) { this.plugin = plugin; this.registry = registry; }

    private boolean enabled() { return plugin.getConfig().getBoolean("scroll-selector", true); }

    /** Gun slots (0-8), lowest first - the loadout order. Capped at 4 weapon slots. */
    private static final int MAX_SLOTS = 4;
    private List<Integer> gunSlots(Player p) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 9 && out.size() < MAX_SLOTS; i++)
            if (registry.gunOf(p.getInventory().getItem(i)) != null) out.add(i);
        return out;
    }

    @EventHandler
    public void onScroll(PlayerItemHeldEvent event) {
        if (!enabled()) return;
        Player p = event.getPlayer();
        List<Integer> guns = gunSlots(p);
        if (guns.size() < 2) return;                 // nothing to cycle - leave scrolling vanilla
        event.setCancelled(true);                    // slot stays locked (on slot 0)
        int dir = wrapDelta(event.getPreviousSlot(), event.getNewSlot());
        if (dir == 0) dir = 1;
        int cur = sel.getOrDefault(p.getUniqueId(), guns.indexOf(0) < 0 ? 0 : guns.indexOf(0));
        int i = ((cur + Integer.signum(dir)) % guns.size() + guns.size()) % guns.size();
        sel.put(p.getUniqueId(), i);
        lastScroll.put(p.getUniqueId(), System.currentTimeMillis());
        hud(p, guns, i);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    /** Equip the selected gun a couple of ticks after the player stops scrolling. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : new ArrayList<>(lastScroll.entrySet())) {
            if (now - e.getValue() < 120) continue;   // ~2-3 ticks with no scroll
            lastScroll.remove(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) equip(p);
        }
    }

    private void equip(Player p) {
        List<Integer> guns = gunSlots(p);
        if (guns.isEmpty()) return;
        int i = Math.min(sel.getOrDefault(p.getUniqueId(), 0), guns.size() - 1);
        int slot = guns.get(i);
        if (slot != 0) {                              // swap the chosen gun into the locked slot 0
            ItemStack chosen = p.getInventory().getItem(slot);
            p.getInventory().setItem(slot, p.getInventory().getItem(0));
            p.getInventory().setItem(0, chosen);
        }
        p.getInventory().setHeldItemSlot(0);
        sel.put(p.getUniqueId(), gunSlots(p).indexOf(0));   // the held gun is now slot 0
        Gun g = registry.gunOf(p.getInventory().getItem(0));
        if (g != null) Msg.actionbar(p, Component.text("▸ " + g.name(), NamedTextColor.WHITE, TextDecoration.BOLD));
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_TRIDENT_RETURN, 0.5f, 1.4f);
    }

    /** The selector strip: every gun in the loadout, the chosen one highlighted. (Bottom-right with a
     *  custom font is the polish pass; this is the functional actionbar version.) */
    private void hud(Player p, List<Integer> guns, int chosen) {
        var line = Component.text();
        for (int k = 0; k < guns.size(); k++) {
            Gun g = registry.gunOf(p.getInventory().getItem(guns.get(k)));
            String nm = g == null ? "?" : g.name();
            if (k == chosen) line.append(Component.text(" [" + nm + "] ", NamedTextColor.YELLOW, TextDecoration.BOLD));
            else line.append(Component.text(" " + nm + " ", NamedTextColor.DARK_GRAY));
        }
        Msg.actionbar(p, line.build());
    }

    /** Wheel delta with hotbar wrap (8->0 is +1, 0->8 is -1). */
    private int wrapDelta(int prev, int now) {
        int d = now - prev;
        if (d > 4) d -= 9;
        if (d < -4) d += 9;
        return d;
    }
}
