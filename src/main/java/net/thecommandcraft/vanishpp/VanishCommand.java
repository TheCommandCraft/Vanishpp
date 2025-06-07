package net.thecommandcraft.vanishpp;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VanishCommand implements CommandExecutor {

    private final Vanishpp plugin;
    private final ConfigManager configManager;

    public VanishCommand(Vanishpp plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            // Self-vanish
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players. Use /vanish <player> to target another player.");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("vanishpp.vanish")) {
                player.sendMessage(configManager.noPermissionMessage);
                return true;
            }
            toggleVanish(player, sender);
        } else {
            // Vanish others
            if (!sender.hasPermission("vanishpp.vanish.others")) {
                sender.sendMessage(configManager.noPermissionMessage);
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(configManager.playerNotFoundMessage);
                return true;
            }
            toggleVanish(target, sender);
        }

        return true;
    }

    private void toggleVanish(Player target, CommandSender executor) {
        if (plugin.isVanished(target)) {
            plugin.unvanish(target, executor);
            if (!target.equals(executor)) {
                executor.sendMessage(configManager.unvanishedOtherMessage.replace("%player%", target.getName()));
            }
        } else {
            plugin.vanish(target, executor);
            if (!target.equals(executor)) {
                executor.sendMessage(configManager.vanishedOtherMessage.replace("%player%", target.getName()));
            }
        }
    }
}