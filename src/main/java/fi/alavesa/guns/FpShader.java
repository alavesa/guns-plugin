package fi.alavesa.guns;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * FIRST-PERSON CORE-SHADER driver (proof of concept, OFF by default: fp-shader.enabled).
 *
 * Minecraft 1.17+ has vanilla, overridable CORE SHADERS (assets/minecraft/shaders/core/*), so a
 * resource pack can animate the first-person held item with NO client mod - smooth, no display-entity
 * stutter, because it's your real held item rendered by the client. The catch is passing a per-item
 * animation PHASE to the shader: the only vanilla per-item channel a core shader can read is the
 * vertex COLOR (the item's dyed_color tint). So this driver writes an animation phase into the held
 * gun's dyed_color; the core shader reads {@code vertexColor} and displaces the geometry:
 *   - RED   channel = recoil phase (1 -> 0 over a few ticks on each shot)
 *   - BLUE  channel = aim (1 while aiming, else 0)     [wire from the aim toggle if wanted]
 *
 * REQUIREMENTS you must satisfy for it to be visible (documented in CUSTOM-MODELS-AND-ANIMATIONS.md):
 *   1. The gun's first-person model must have {@code tintindex: 0} on the parts that should move, so
 *      the client feeds our dyed_color in as {@code vertexColor}. (A base item with a colour provider.)
 *   2. The core shader GLSL must match YOUR client's shader format - copy your 26.2 client's item
 *      core shader as the base and inject the displacement snippet from the template. I can't fetch a
 *      future client's shaders offline, so the GLSL ships as a template to finalise + test in-game.
 */
final class FpShader {

    private final Plugin plugin;
    private final GunRegistry registry;

    FpShader(Plugin plugin, GunRegistry registry) { this.plugin = plugin; this.registry = registry; }

    private boolean enabled() { return plugin.getConfig().getBoolean("fp-shader.enabled", false); }

    /** On each shot, animate the RED channel 255 -> 0 over recoil-ticks, so the shader kicks the model back. */
    public void onFire(Player player) {
        if (!enabled()) return;
        final int ticks = Math.max(1, plugin.getConfig().getInt("fp-shader.recoil-ticks", 4));
        new BukkitRunnable() {
            int t = ticks;
            @Override public void run() {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (!player.isOnline() || registry.gunOf(held) == null || t < 0) { cancel(); return; }
                setChannels(held, (int) Math.round(255.0 * t / ticks), null, null);
                player.getInventory().setItemInMainHand(held);
                t--;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Sustained aim signal on the BLUE channel (call from the aim toggle / poll if you want it). */
    public void setAim(Player player, boolean aiming) {
        if (!enabled()) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (registry.gunOf(held) == null) return;
        setChannels(held, null, null, aiming ? 255 : 0);
        player.getInventory().setItemInMainHand(held);
    }

    /** Write the given channels (null = keep current) into the item's dyed_color component. */
    private void setChannels(ItemStack item, Integer r, Integer g, Integer b) {
        int cur = 0;
        DyedItemColor existing = item.getData(DataComponentTypes.DYED_COLOR);
        if (existing != null) cur = existing.color().asRGB();
        int nr = r != null ? clamp(r) : (cur >> 16) & 0xFF;
        int ng = g != null ? clamp(g) : (cur >> 8) & 0xFF;
        int nb = b != null ? clamp(b) : cur & 0xFF;
        item.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(nr, ng, nb), false));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
