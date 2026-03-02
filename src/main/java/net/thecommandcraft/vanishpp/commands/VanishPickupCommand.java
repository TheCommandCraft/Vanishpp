package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VanishPickupCommand implements CommandExecutor {
    private final Vanishpp plugin;

    public VanishPickupCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        Player target;

        if (args.length > 0) {
            if (!sender.hasPermission("vanishpp.pickup.others")) {
                sender.sendMessage(
                        Component.text("You do not have permission to toggle pickup for others.", NamedTextColor.RED));
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                plugin.getMessageManager().sendMessage(sender, "Player not found.");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                plugin.getMessageManager().sendMessage(sender, "Console must specify a player.");
                return true;
            }
            target = (Player) sender;
        }

        RuleManager rules = plugin.getRuleManager();
        boolean current = rules.getRule(target, RuleManager.CAN_PICKUP_ITEMS);
        boolean newValue = !current;

        rules.setRule(target, RuleManager.CAN_PICKUP_ITEMS, newValue);

        String msg = newValue
                ? plugin.getLanguageManager().getMessage("pickup.enabled")
                : plugin.getLanguageManager().getMessage("pickup.disabled");

        plugin.getMessageManager().sendMessage(target, msg);
        if (!target.equals(sender)) {
            plugin.getMessageManager().sendMessage(sender, "Toggled pickup for " + target.getName() + ": " + newValue);
        }

        return true;
    }
}