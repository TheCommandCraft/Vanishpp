package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.gui.ProxyConfigGUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VProxyGuiCommand implements CommandExecutor {

    private final Vanishpp plugin;
    private final ProxyConfigGUI gui;

    public VProxyGuiCommand(Vanishpp plugin, ProxyConfigGUI gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.config")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
            return true;
        }
        gui.open(player);
        return true;
    }
}
