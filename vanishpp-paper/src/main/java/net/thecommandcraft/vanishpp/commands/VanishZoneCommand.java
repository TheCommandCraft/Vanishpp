package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.zone.VanishZone;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * /vzone create <name> [radius] [--deny]  — create a no-vanish zone at your location
 * /vzone delete <name>                    — remove a zone
 * /vzone list                             — list all zones
 * /vzone reload                           — reload zones from disk
 *
 * --deny = force-unvanish (default: true)
 * Requires vanishpp.zone permission.
 */
public class VanishZoneCommand implements CommandExecutor, TabCompleter {

    private static final double DEFAULT_RADIUS = 32.0;

    private final Vanishpp plugin;

    public VanishZoneCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.zone")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        var lm = plugin.getConfigManager().getLanguageManager();

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    plugin.getMessageManager().sendMessage(sender,
                            lm.getMessage("console-specify"));
                    return true;
                }
                if (args.length < 2) {
                    plugin.getMessageManager().sendMessage(sender,
                            lm.getMessage("zone.create-usage"));
                    return true;
                }
                String name = args[1];
                double radius = DEFAULT_RADIUS;
                if (args.length >= 3) {
                    try {
                        radius = Double.parseDouble(args[2]);
                        if (radius <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        plugin.getMessageManager().sendMessage(sender,
                                lm.getMessage("zone.invalid-radius"));
                        return true;
                    }
                }
                boolean created = plugin.getVanishZoneManager()
                        .createZone(name, player.getLocation(), radius, true);
                if (!created) {
                    plugin.getMessageManager().sendMessage(sender,
                            lm.getMessage("zone.already-exists").replace("%name%", name));
                } else {
                    plugin.getMessageManager().sendMessage(sender,
                            lm.getMessage("zone.created")
                                    .replace("%name%", name)
                                    .replace("%radius%", String.valueOf((int) radius)));
                }
            }
            case "delete" -> {
                if (args.length < 2) {
                    plugin.getMessageManager().sendMessage(sender, lm.getMessage("zone.delete-usage"));
                    return true;
                }
                String name = args[1];
                boolean deleted = plugin.getVanishZoneManager().deleteZone(name);
                if (deleted) {
                    plugin.getMessageManager().sendMessage(sender,
                            lm.getMessage("zone.deleted").replace("%name%", name));
                } else {
                    plugin.getMessageManager().sendMessage(sender,
                            lm.getMessage("zone.not-found").replace("%name%", name));
                }
            }
            case "list" -> {
                var zones = plugin.getVanishZoneManager().getAllZones();
                if (zones.isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender, lm.getMessage("zone.none"));
                    return true;
                }
                plugin.getMessageManager().sendMessage(sender,
                        lm.getMessage("zone.list-header").replace("%count%", String.valueOf(zones.size())));
                for (VanishZone z : zones) {
                    plugin.getMessageManager().sendMessage(sender,
                            lm.getMessage("zone.list-entry")
                                    .replace("%name%", z.getName())
                                    .replace("%world%", z.getWorldName())
                                    .replace("%radius%", String.valueOf((int) z.getRadius()))
                                    .replace("%x%", String.valueOf((int) z.getX()))
                                    .replace("%z%", String.valueOf((int) z.getZ())));
                }
            }
            case "reload" -> {
                plugin.getVanishZoneManager().load();
                plugin.getMessageManager().sendMessage(sender, lm.getMessage("zone.reloaded"));
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender,
                plugin.getConfigManager().getLanguageManager().getMessage("zone.usage"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0],
                    List.of("create", "delete", "list", "reload"), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            List<String> names = new ArrayList<>();
            for (VanishZone z : plugin.getVanishZoneManager().getAllZones()) names.add(z.getName());
            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
