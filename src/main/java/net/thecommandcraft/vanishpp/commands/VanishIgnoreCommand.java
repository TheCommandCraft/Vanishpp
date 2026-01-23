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

public class VanishIgnoreCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishIgnoreCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Player target;

        if (args.length > 0) {
            if (!sender.hasPermission("vanishpp.ignorewarning.others")) {
                sender.sendMessage(Component.text("Permission denied.", NamedTextColor.RED));
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;
        }

        if (plugin.isWarningIgnored(target)) {
            plugin.setWarningIgnored(target, false);
            target.sendMessage(Component.text("You will now receive ProtocolLib warnings again.", NamedTextColor.GREEN));
            if (!target.equals(sender)) sender.sendMessage(Component.text(target.getName() + " will receive warnings.", NamedTextColor.GREEN));
        } else {
            plugin.setWarningIgnored(target, true);
            target.sendMessage(Component.text("You will no longer receive ProtocolLib warnings.", NamedTextColor.RED));
            if (!target.equals(sender)) sender.sendMessage(Component.text(target.getName() + " will ignore warnings.", NamedTextColor.RED));
        }
        return true;
    }
}