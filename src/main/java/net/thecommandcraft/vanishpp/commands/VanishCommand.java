package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VanishCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Player target;

        // Case 1: Targeting another player (or Console usage)
        if (args.length > 0) {
            if (!sender.hasPermission("vanishpp.vanish.others")) {
                sender.sendMessage(Component.text(plugin.getConfigManager().noPermissionMessage, NamedTextColor.RED));
                return true;
            }

            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text(plugin.getConfigManager().playerNotFoundMessage, NamedTextColor.RED));
                return true;
            }
        }
        // Case 2: Toggling self
        else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Console must specify a player: /vanish <player>", NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;

            if (!sender.hasPermission("vanishpp.vanish")) {
                sender.sendMessage(Component.text(plugin.getConfigManager().noPermissionMessage, NamedTextColor.RED));
                return true;
            }
        }

        // Toggle Logic
        toggleVanish(target, sender);
        return true;
    }

    private void toggleVanish(Player target, CommandSender executor) {
        if (plugin.isVanished(target)) {
            // Unvanish
            plugin.unvanishPlayer(target, executor);

            // Notify executor if they are not the target
            if (!target.equals(executor)) {
                String msg = plugin.getConfigManager().unvanishedOtherMessage.replace("%player%", target.getName());
                executor.sendMessage(Component.text(msg, NamedTextColor.GREEN));
            }
        } else {
            // Vanish
            plugin.vanishPlayer(target, executor);

            // Notify executor if they are not the target
            if (!target.equals(executor)) {
                String msg = plugin.getConfigManager().vanishedOtherMessage.replace("%player%", target.getName());
                executor.sendMessage(Component.text(msg, NamedTextColor.GREEN));
            }
        }
    }
}