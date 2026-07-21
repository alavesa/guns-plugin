package fi.alavesa.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public final class GunsPlugin extends JavaPlugin {

    private GunRegistry registry;
    private static GunRegistry REGISTRY;   // static handle for cross-plugin damage lookups

    /** The configured damage of the gun this item is, or -1 if it isn't a gun.
     *  Other plugins (Terminal's CCTV bodies) call this by reflection to charge
     *  the real weapon damage instead of a bare-hand melee value. */
    public static double gunDamageOf(org.bukkit.inventory.ItemStack item) {
        if (REGISTRY == null || item == null) return -1;
        Gun gun = REGISTRY.gunOf(item);
        return gun == null ? -1 : gun.damage();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        registry = new GunRegistry(this);
        REGISTRY = registry;
        registry.load();
        AmmoBar ammoBar = new AmmoBar();
        ShootListener shootListener = new ShootListener(this, registry, ammoBar);
        getServer().getPluginManager().registerEvents(shootListener, this);
        getServer().getScheduler().runTaskTimer(this, shootListener::bulletTick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, shootListener::tickReticle, 1L, 1L);
        getServer().getPluginManager().registerEvents(new GrenadeListener(this, registry), this);

        // Ammo boss bar: shown while a gun is held, hidden otherwise. Polling every 5 ticks
        // keeps it correct across item switches/pickups; shots and reloads update it instantly.
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (var player : getServer().getOnlinePlayers()) {
                var held = player.getInventory().getItemInMainHand();
                Gun gun = registry.gunOf(held);
                if (gun != null) {
                    ammoBar.update(player, gun, registry.ammoOf(held), registry.fireModeOf(held, gun),
                        shootListener.reserveRounds(player, gun));
                } else {
                    ammoBar.hide(player);
                }
            }
        }, 20L, 5L);

        getLogger().info("Guns enabled - guns: " + registry.ids() + ", grenades: " + registry.grenadeIds()
            + ", mags: " + registry.magIds());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        try {
            switch (args[0].toLowerCase()) {
                case "list" -> {
                    sender.sendMessage(Component.text("Guns: " + String.join(", ", registry.ids())
                        + " | Grenades: " + String.join(", ", registry.grenadeIds())
                        + " | Mags: " + String.join(", ", registry.magIds()), NamedTextColor.GOLD));
                    return true;
                }
                case "models" -> {
                    sender.sendMessage(Component.text("Gun model files to author in the resource pack "
                        + "(assets/guns/models/item/<name>.json):", NamedTextColor.GOLD));
                    for (String id : registry.ids()) {
                        Gun g = registry.get(id);
                        if (g == null) continue;
                        String base = g.model();
                        if (g.isSpyglass()) {
                            sender.sendMessage(Component.text("  " + id + " -> ", NamedTextColor.GRAY)
                                .append(Component.text(base, NamedTextColor.AQUA))
                                .append(Component.text("   (spyglass sniper: no _emptymag / _aim)",
                                    NamedTextColor.DARK_GRAY)));
                        } else {
                            sender.sendMessage(Component.text("  " + id + " -> ", NamedTextColor.GRAY)
                                .append(Component.text(base, NamedTextColor.AQUA))
                                .append(Component.text("  /  ", NamedTextColor.DARK_GRAY))
                                .append(Component.text(base + "_emptymag", NamedTextColor.YELLOW))
                                .append(Component.text("  /  ", NamedTextColor.DARK_GRAY))
                                .append(Component.text(base + "_aim", NamedTextColor.GREEN)));
                        }
                    }
                    sender.sendMessage(Component.text("Legend: ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("base", NamedTextColor.AQUA))
                        .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("empty-mag (out of ammo)", NamedTextColor.YELLOW))
                        .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("aim (ironsights)", NamedTextColor.GREEN)));
                    return true;
                }
                case "give" -> {
                    if (!sender.hasPermission("guns.give")) return error(sender, "No permission.");
                    if (args.length < 2) return usage(sender);
                    Gun gun = registry.get(args[1]);
                    Grenade grenade = gun == null ? registry.getGrenade(args[1]) : null;
                    Mag mag = gun == null && grenade == null ? registry.getMag(args[1]) : null;
                    if (gun == null && grenade == null && mag == null) {
                        return error(sender, "Unknown gun/grenade/mag: " + args[1]);
                    }
                    boolean targetingOther = args.length >= 3;
                    if (targetingOther && !sender.hasPermission("guns.admin")) {
                        return error(sender, "You can only give guns to yourself.");
                    }
                    Player target = targetingOther ? Bukkit.getPlayerExact(args[2])
                        : (sender instanceof Player p ? p : null);
                    if (target == null) return error(sender, "Player not found.");
                    target.getInventory().addItem(gun != null ? registry.buildItem(gun)
                        : grenade != null ? registry.buildGrenadeItem(grenade)
                        : registry.buildMagItem(mag));
                    sender.sendMessage(Component.text("Gave " + args[1].toLowerCase() + " to " + target.getName(), NamedTextColor.GOLD));
                    return true;
                }
                case "create" -> {
                    if (!sender.hasPermission("guns.admin")) return error(sender, "No permission.");
                    if (args.length < 2) return usage(sender);
                    String type = args.length >= 3 ? args[2].toLowerCase() : "gun";
                    if (!type.equals("gun") && !type.equals("grenade") && !type.equals("mag")) {
                        return error(sender, "Unknown type '" + args[2] + "' - use gun, grenade or mag.");
                    }
                    if (!registry.create(args[1], type)) {
                        return error(sender, "'" + args[1] + "' already exists.");
                    }
                    sender.sendMessage(Component.text("Created " + type + " '"
                        + args[1].toLowerCase() + "' with default stats. Tune it with /guns edit "
                        + args[1].toLowerCase() + " <stat> <value> and get it with /guns give "
                        + args[1].toLowerCase(), NamedTextColor.GOLD));
                    return true;
                }
                case "edit" -> {
                    if (!sender.hasPermission("guns.admin")) return error(sender, "No permission.");
                    if (args.length < 4) return usage(sender);
                    String problem = registry.edit(args[1], args[2], String.join(" ", List.of(args).subList(3, args.length)));
                    if (problem != null) return error(sender, problem);
                    sender.sendMessage(Component.text("Updated " + args[1] + " " + args[2] + " = " + args[3]
                        + (args[2].equalsIgnoreCase("name") || args[2].equalsIgnoreCase("model")
                            ? " (re-run /guns give to see it on the item)" : ""), NamedTextColor.GOLD));
                    return true;
                }
                case "remove" -> {
                    if (!sender.hasPermission("guns.admin")) return error(sender, "No permission.");
                    if (args.length < 2) return usage(sender);
                    String problem = registry.remove(args[1]);
                    if (problem != null) return error(sender, problem);
                    sender.sendMessage(Component.text("Removed '" + args[1].toLowerCase()
                        + "' from guns.yml. Already-given items of it are now inert.", NamedTextColor.GOLD));
                    return true;
                }
                case "reload" -> {
                    if (!sender.hasPermission("guns.admin")) return error(sender, "No permission.");
                    registry.load();
                    sender.sendMessage(Component.text("guns.yml reloaded - " + registry.ids().size()
                        + " gun(s), " + registry.magIds().size() + " mag(s).", NamedTextColor.GOLD));
                    return true;
                }
                case "firemode", "mode" -> {
                    if (!(sender instanceof org.bukkit.entity.Player player)) return error(sender, "Players only.");
                    org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
                    Gun gun = registry.gunOf(held);
                    if (gun == null) return error(sender, "Hold a gun to switch its fire mode.");
                    var modes = gun.modes();
                    if (modes.size() < 2) {
                        return error(sender, "This gun has only one fire mode (" + modes.get(0).toUpperCase() + ").");
                    }
                    String current = registry.fireModeOf(held, gun);
                    String next = modes.get((modes.indexOf(current) + 1) % modes.size());
                    registry.setFireMode(held, next);
                    player.getInventory().setItemInMainHand(held);
                    sender.sendMessage(Component.text("Fire mode: " + next.toUpperCase(), NamedTextColor.GOLD));
                    player.playSound(player.getLocation(), "minecraft:block.lever.click", 0.7f, 1.4f);
                    return true;
                }
                default -> { return usage(sender); }
            }
        } catch (IOException e) {
            getLogger().severe("Could not save guns.yml: " + e.getMessage());
            return error(sender, "Saving guns.yml failed - see console.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> filter(Stream.of("list", "models", "give", "create", "edit", "remove", "reload", "firemode"), args[0]);
            case 2 -> {
                if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("edit")) {
                    yield filter(Stream.of(registry.ids(), registry.grenadeIds(), registry.magIds())
                        .flatMap(java.util.Collection::stream), args[1]);
                }
                if (args[0].equalsIgnoreCase("remove")) { // guns and mags only
                    yield filter(Stream.of(registry.ids(), registry.magIds())
                        .flatMap(java.util.Collection::stream), args[1]);
                }
                yield List.of();
            }
            case 3 -> {
                if (args[0].equalsIgnoreCase("edit")) {
                    yield registry.getGrenade(args[1]) != null
                        ? filter(GunRegistry.GRENADE_EDITABLE.stream(), args[2])
                        : registry.getMag(args[1]) != null
                            ? filter(GunRegistry.MAG_EDITABLE.stream(), args[2])
                            : filter(GunRegistry.GUN_EDITABLE.stream(), args[2]);
                }
                if (args[0].equalsIgnoreCase("create")) yield filter(Stream.of("gun", "grenade", "mag"), args[2]);
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.startsWith(prefix.toLowerCase())).sorted().toList();
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/guns list | give <id> [player] | create <id> [gun|grenade|mag] | edit <id> <stat> <value> | remove <gun|mag id> | reload",
            NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Gun stats: " + String.join(", ", GunRegistry.GUN_EDITABLE), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Grenade stats: " + String.join(", ", GunRegistry.GRENADE_EDITABLE), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Mag stats: " + String.join(", ", GunRegistry.MAG_EDITABLE), NamedTextColor.GRAY));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
