package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VanishIgnoreCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishIgnoreCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        Player target;

        if (args.length > 0) {
            if (!sender.hasPermission("vanishpp.ignorewarning.others")) {
                plugin.getMessageManager().sendMessage(sender, "<red>Permission denied.");
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                plugin.getMessageManager().sendMessage(sender, "<red>Player not found.");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                plugin.getMessageManager().sendMessage(sender, "<red>Console must specify a player.");
                return true;
            }
            target = (Player) sender;
        }

        if (plugin.isWarningIgnored(target)) {
            plugin.setWarningIgnored(target, false);
            plugin.getMessageManager().sendMessage(target, "<green>You will now receive ProtocolLib warnings again.");
            if (!target.equals(sender))
                plugin.getMessageManager().sendMessage(sender,
                        "<green>" + target.getName() + " will receive warnings.");
        } else {
            plugin.setWarningIgnored(target, true);
            plugin.getMessageManager().sendMessage(target, "<red>You will no longer receive ProtocolLib warnings.");
            if (!target.equals(sender))
                plugin.getMessageManager().sendMessage(sender, "<red>" + target.getName() + " will ignore warnings.");
        }
        return true;
    }
}