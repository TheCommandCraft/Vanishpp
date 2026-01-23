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
    public VanishPickupCommand(Vanishpp plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Player target;

        if (args.length > 0) {
            if (!sender.hasPermission("vanishpp.pickup.others")) {
                sender.sendMessage(Component.text("You do not have permission to toggle pickup for others.", NamedTextColor.RED));
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

        RuleManager rules = plugin.getRuleManager();
        boolean current = rules.getRule(target, RuleManager.CAN_PICKUP_ITEMS);
        boolean newValue = !current;

        rules.setRule(target, RuleManager.CAN_PICKUP_ITEMS, newValue);

        Component msg = newValue
                ? Component.text(plugin.getConfigManager().pickupEnabledMessage, NamedTextColor.GREEN)
                : Component.text(plugin.getConfigManager().pickupDisabledMessage, NamedTextColor.RED);

        target.sendMessage(msg);
        if (!target.equals(sender)) {
            sender.sendMessage(Component.text("Toggled pickup for " + target.getName() + ": " + newValue, NamedTextColor.GOLD));
        }

        return true;
    }
}