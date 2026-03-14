package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

public class VanishCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        Player target;

        // Case 1: Targeting another player (or Console usage)
        if (args.length > 0) {
            if (!sender.hasPermission("vanishpp.vanish.others")) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
                return true;
            }

            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().playerNotFoundMessage);
                return true;
            }
        }
        // Case 2: Toggling self
        else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(
                        Component.text("Console must specify a player: /vanish <player>", NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;

            if (!sender.hasPermission("vanishpp.vanish")) {
                // Stay stealthy — pretend the command doesn't exist
                sender.sendMessage(Component.text("Unknown command. Type \"/help\" for help.", NamedTextColor.RED));
                return true;
            }
        }

        // Toggle Logic
        toggleVanish(target, sender);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("vanishpp.vanish.others")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return StringUtil.copyPartialMatches(args[0], names, new ArrayList<>());
        }
        return new ArrayList<>();
    }

    private void toggleVanish(Player target, CommandSender executor) {
        if (plugin.isVanished(target)) {
            // Unvanish
            plugin.unvanishPlayer(target, executor);

            // Notify executor if they are not the target
            if (!target.equals(executor)) {
                String msg = plugin.getConfigManager().unvanishedOtherMessage.replace("%player%", target.getName());
                plugin.getMessageManager().sendMessage(executor, msg);
            }
        } else {
            // Vanish
            plugin.vanishPlayer(target, executor);

            // Notify executor if they are not the target
            if (!target.equals(executor)) {
                String msg = plugin.getConfigManager().vanishedOtherMessage.replace("%player%", target.getName());
                plugin.getMessageManager().sendMessage(executor, msg);
            }
        }
    }
}