package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WEAPON SELECTOR HUD - a right-edge sidebar listing the guns you're carrying (up to 4 slots),
 * the one in your hand highlighted with an arrow.
 *
 * It lives on a DEDICATED per-player scoreboard, which is the key design choice: the sidebar is a
 * completely separate render channel from the action bar, the boss bars and the titles, so it can
 * NEVER be corrupted by them (that's what caused the earlier bold-bleed) and adding any new HUD
 * overlay elsewhere never requires touching this one. It's isolated by construction.
 *
 * The catch with owning the player's viewed scoreboard: the only thing other plugins render through
 * it is IdCards' hide-nametags team ("idc_hidden") - and any team that hides nametags. So on each
 * refresh we MIRROR those teams onto our board, keeping nametags exactly as IdCards wants them. All
 * the other cross-plugin scoreboard objects (facility.menu, blink, z008 ...) are plain data flags
 * written/read on the MAIN scoreboard object directly, independent of what a player is viewing, so
 * they keep working untouched.
 *
 * The sidebar is only shown while a gun is held; when you switch to anything else it's cleared, so
 * the right edge is empty during normal play.
 */
public final class WeaponHud {

    private static final int MAX_SLOTS = 4;
    private static final String OBJ = "gun_wpn";

    private final GunRegistry registry;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    WeaponHud(GunRegistry registry) { this.registry = registry; }

    /** Get (creating if needed) this player's dedicated weapon board and make it the one they view. */
    private Scoreboard board(Player p) {
        Scoreboard b = boards.get(p.getUniqueId());
        if (b == null) {
            b = Bukkit.getScoreboardManager().getNewScoreboard();
            b.registerNewObjective(OBJ, Criteria.DUMMY, Component.text("WEAPONS", NamedTextColor.GOLD));
            boards.put(p.getUniqueId(), b);
        }
        if (p.getScoreboard() != b) p.setScoreboard(b);
        return b;
    }

    /**
     * Refresh a player's weapon sidebar. Called from the ammo poll loop (every few ticks) and after
     * shots/reloads via GunsPlugin. Safe to call every tick - it only rewrites when the line set changes.
     */
    public void refresh(Player p) {
        Scoreboard b = board(p);
        mirrorNametagTeams(b);
        Objective obj = b.getObjective(OBJ);
        if (obj == null) return;

        List<Integer> guns = gunSlots(p);
        int held = p.getInventory().getHeldItemSlot();
        boolean holdingGun = registry.gunOf(p.getInventory().getItemInMainHand()) != null;

        if (!holdingGun || guns.isEmpty()) {                 // nothing to select -> clear the sidebar
            if (obj.getDisplaySlot() != null) obj.setDisplaySlot(null);
            clearScores(b, obj);
            return;
        }

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < guns.size(); i++) {
            int slot = guns.get(i);
            Gun g = registry.gunOf(p.getInventory().getItem(slot));
            String name = g == null ? "?" : stripColor(g.name());
            boolean sel = slot == held;
            // Scoreboard lines must be unique strings; the leading marker + a per-index invisible
            // suffix guarantees uniqueness even if two guns share a name.
            String line = (sel ? "§e§l▸ " : "§7  ") + name + pad(i);
            lines.add(line);
        }

        clearScores(b, obj);
        // top line = highest score; render slot 1 at the top
        int score = lines.size();
        for (String line : lines) obj.getScore(line).setScore(score--);
        if (obj.getDisplaySlot() != DisplaySlot.SIDEBAR) obj.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    /** Gun hotbar slots (0-8), lowest first, capped at the max weapon slots. */
    private List<Integer> gunSlots(Player p) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 9 && out.size() < MAX_SLOTS; i++)
            if (registry.gunOf(p.getInventory().getItem(i)) != null) out.add(i);
        return out;
    }

    /** Copy every nametag-hiding team from the MAIN scoreboard onto this board so IdCards' hidden
     *  nametags stay hidden for a player who is viewing our board instead of the main one. */
    private void mirrorNametagTeams(Scoreboard b) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team src : main.getTeams()) {
            if (src.getOption(Team.Option.NAME_TAG_VISIBILITY) == Team.OptionStatus.ALWAYS) continue;
            Team dst = b.getTeam(src.getName());
            if (dst == null) dst = b.registerNewTeam(src.getName());
            dst.setOption(Team.Option.NAME_TAG_VISIBILITY, src.getOption(Team.Option.NAME_TAG_VISIBILITY));
            for (String e : src.getEntries()) if (!dst.hasEntry(e)) dst.addEntry(e);
        }
    }

    private static void clearScores(Scoreboard b, Objective obj) {
        for (String entry : new ArrayList<>(b.getEntries())) {
            if (obj.getScore(entry).isScoreSet()) b.resetScores(entry);
        }
    }

    /** Per-index invisible suffix (colour codes render as nothing) to keep sidebar entries unique. */
    private static String pad(int i) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k <= i; k++) s.append('§').append("0123456789abcdef".charAt(k % 16));
        return s.toString();
    }

    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("[&§].", "");
    }

    /** Drop a player's board (on quit) - hand them back the main scoreboard. */
    public void remove(Player p) {
        boards.remove(p.getUniqueId());
        if (p.isOnline()) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /** On disable, return everyone to the main scoreboard so no one is left on a dangling board. */
    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers())
            if (boards.containsKey(p.getUniqueId()))
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        boards.clear();
    }
}
