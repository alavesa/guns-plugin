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

    @Override
    public void onEnable() {
        registry = new GunRegistry(this);
        registry.load();
        getServer().getPluginManager().registerEvents(new ShootListener(this, registry), this);
        getServer().getPluginManager().registerEvents(new GrenadeListener(this, registry), this);
        getLogger().info("Guns enabled - guns: " + registry.ids() + ", grenades: " + registry.grenadeIds());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        try {
            switch (args[0].toLowerCase()) {
                case "list" -> {
                    sender.sendMessage(Component.text("Guns: " + String.join(", ", registry.ids())
                        + " | Grenades: " + String.join(", ", registry.grenadeIds()), NamedTextColor.GOLD));
                    return true;
                }
                case "give" -> {
                    if (args.length < 2) return usage(sender);
                    Gun gun = registry.get(args[1]);
                    Grenade grenade = gun == null ? registry.getGrenade(args[1]) : null;
                    if (gun == null && grenade == null) return error(sender, "Unknown gun/grenade: " + args[1]);
                    Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                        : (sender instanceof Player p ? p : null);
                    if (target == null) return error(sender, "Player not found.");
                    target.getInventory().addItem(gun != null ? registry.buildItem(gun) : registry.buildGrenadeItem(grenade));
                    sender.sendMessage(Component.text("Gave " + args[1].toLowerCase() + " to " + target.getName(), NamedTextColor.GOLD));
                    return true;
                }
                case "create" -> {
                    if (!sender.hasPermission("guns.admin")) return error(sender, "No permission.");
                    if (args.length < 2) return usage(sender);
                    boolean grenade = args.length >= 3 && args[2].equalsIgnoreCase("grenade");
                    if (!registry.create(args[1], grenade)) {
                        return error(sender, "'" + args[1] + "' already exists.");
                    }
                    sender.sendMessage(Component.text("Created " + (grenade ? "grenade" : "gun") + " '"
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
                case "reload" -> {
                    if (!sender.hasPermission("guns.admin")) return error(sender, "No permission.");
                    registry.load();
                    sender.sendMessage(Component.text("guns.yml reloaded - " + registry.ids().size() + " gun(s).", NamedTextColor.GOLD));
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
            case 1 -> filter(Stream.of("list", "give", "create", "edit", "reload"), args[0]);
            case 2 -> args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("edit")
                ? filter(Stream.concat(registry.ids().stream(), registry.grenadeIds().stream()), args[1])
                : List.of();
            case 3 -> {
                if (args[0].equalsIgnoreCase("edit")) {
                    yield registry.getGrenade(args[1]) != null
                        ? filter(GunRegistry.GRENADE_EDITABLE.stream(), args[2])
                        : filter(GunRegistry.GUN_EDITABLE.stream(), args[2]);
                }
                if (args[0].equalsIgnoreCase("create")) yield filter(Stream.of("gun", "grenade"), args[2]);
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
            "/guns list | give <id> [player] | create <id> [gun|grenade] | edit <id> <stat> <value> | reload",
            NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Gun stats: " + String.join(", ", GunRegistry.GUN_EDITABLE), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Grenade stats: " + String.join(", ", GunRegistry.GRENADE_EDITABLE), NamedTextColor.GRAY));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
