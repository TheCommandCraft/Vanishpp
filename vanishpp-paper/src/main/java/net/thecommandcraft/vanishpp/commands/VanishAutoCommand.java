package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
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
import java.util.UUID;

/**
 * /vautovanish [player] [on|off]
 * Toggles the "always join vanished" preference per player.
 * Persisted in storage — survives restarts and server switches.
 */
public class VanishAutoCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishAutoCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.vanish")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        Player target;
        Boolean newValue = null;

        if (args.length == 0) {
            // Toggle self
            if (!(sender instanceof Player)) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
                return true;
            }
            target = (Player) sender;
        } else if (args.length == 1) {
            // Either "on"/"off" for self, or a player name
            String arg = args[0].toLowerCase();
            if (arg.equals("on") || arg.equals("off")) {
                if (!(sender instanceof Player)) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
                    return true;
                }
                target = (Player) sender;
                newValue = arg.equals("on");
            } else {
                if (!sender.hasPermission("vanishpp.vanish.others")) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().noPermissionMessage);
                    return true;
                }
                target = Bukkit.getPlayer(arg);
                if (target == null) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().playerNotFoundMessage);
                    return true;
                }
            }
        } else {
            // /vautovanish <player> <on|off>
            if (!sender.hasPermission("vanishpp.vanish.others")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().playerNotFoundMessage);
                return true;
            }
            String val = args[1].toLowerCase();
            if (val.equals("on") || val.equals("off")) {
                newValue = val.equals("on");
            }
        }

        final Player finalTarget = target;
        final UUID uuid = target.getUniqueId();

        if (newValue == null) {
            // Toggle current
            plugin.getVanishScheduler().runAsync(() -> {
                boolean current = plugin.getStorageProvider().getAutoVanishOnJoin(uuid);
                plugin.getStorageProvider().setAutoVanishOnJoin(uuid, !current);
                boolean next = !current;
                plugin.getVanishScheduler().runGlobal(() -> {
                    String key = next ? "auto-vanish.enabled" : "auto-vanish.disabled";
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage(key)
                                    .replace("%player%", finalTarget.getName()));
                });
            });
        } else {
            final boolean finalValue = newValue;
            plugin.getVanishScheduler().runAsync(() -> {
                plugin.getStorageProvider().setAutoVanishOnJoin(uuid, finalValue);
                plugin.getVanishScheduler().runGlobal(() -> {
                    String key = finalValue ? "auto-vanish.enabled" : "auto-vanish.disabled";
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage(key)
                                    .replace("%player%", finalTarget.getName()));
                });
            });
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("on");
            completions.add("off");
            for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        if (args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], List.of("on", "off"), new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
