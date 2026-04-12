package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.gui.AdminDashboardGUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /vadmin — opens the Admin Dashboard GUI.
 * Requires vanishpp.admin permission.
 */
public class VanishAdminCommand implements CommandExecutor {

    private final Vanishpp plugin;
    private final AdminDashboardGUI dashboard;

    public VanishAdminCommand(Vanishpp plugin) {
        this.plugin = plugin;
        this.dashboard = new AdminDashboardGUI(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
            return true;
        }
        if (!player.hasPermission("vanishpp.admin")) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }
        dashboard.open(player);
        return true;
    }
}
