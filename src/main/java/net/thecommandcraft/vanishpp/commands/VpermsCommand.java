package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.config.ConfigManager;
import net.thecommandcraft.vanishpp.config.PermissionManager;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

public class VpermsCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;
    private final PermissionManager permissionManager;
    private final ConfigManager configManager;

    private static final List<String> VALID_PERMISSIONS = List.of(
            "vanishpp.vanish",
            "vanishpp.see",
            "vanishpp.vanish.others"
    );

    private static final List<String> ACTIONS = List.of("set", "remove", "get");

    public VpermsCommand(Vanishpp plugin) {
        this.plugin = plugin;
        this.permissionManager = plugin.getPermissionManager();
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.manageperms")) {
            sender.sendMessage(configManager.noPermissionMessage);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            permissionManager.load();
            sender.sendMessage(configManager.vpermsReload);
            return true;
        }

        if (args.length != 3) {
            sender.sendMessage(configManager.vpermsInvalidUsage);
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(configManager.playerNotFoundMessage);
            return true;
        }

        String permission = args[1].toLowerCase();
        if (!VALID_PERMISSIONS.contains(permission)) {
            sender.sendMessage(configManager.vpermsInvalidPermission);
            return true;
        }

        String action = args[2].toLowerCase();
        UUID targetUUID = target.getUniqueId();

        switch (action) {
            case "set":
                permissionManager.addPermission(targetUUID, permission);
                sender.sendMessage(configManager.vpermsPermSet
                        .replace("%perm%", permission)
                        .replace("%player%", target.getName()));
                break;
            case "remove":
                permissionManager.removePermission(targetUUID, permission);
                sender.sendMessage(configManager.vpermsPermRemoved
                        .replace("%perm%", permission)
                        .replace("%player%", target.getName()));
                break;
            case "get":
                boolean hasPerm = permissionManager.hasPermission(targetUUID, permission);
                String message = hasPerm ? configManager.vpermsPermGetHas : configManager.vpermsPermGetDoesNotHave;
                sender.sendMessage(message
                        .replace("%perm%", permission)
                        .replace("%player%", target.getName()));
                break;
            default:
                sender.sendMessage(configManager.vpermsInvalidUsage);
                break;
        }

        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            for(Player p : Bukkit.getOnlinePlayers()){
                suggestions.add(p.getName());
            }
            suggestions.add("reload");
            StringUtil.copyPartialMatches(args[0], suggestions, completions);
        } else if (args.length == 2) {
            StringUtil.copyPartialMatches(args[1], VALID_PERMISSIONS, completions);
        } else if (args.length == 3) {
            StringUtil.copyPartialMatches(args[2], ACTIONS, completions);
        }
        return completions;
    }
}