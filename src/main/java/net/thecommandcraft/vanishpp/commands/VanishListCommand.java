package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VanishListCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishListCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.list")) {
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
            return true;
        }

        List<String> localNames = new ArrayList<>();
        List<String> remoteNames = new ArrayList<>();
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                localNames.add(p.getName());
            } else if (plugin.getConfigManager().pluginMessagingEnabled) {
                // Player is vanished on another server in the Velocity network
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                remoteNames.add(name != null ? name : uuid.toString().substring(0, 8) + "…");
            }
        }

        var lm = plugin.getConfigManager().getLanguageManager();
        int total = localNames.size() + remoteNames.size();
        if (total == 0) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.no-vanished-online"));
        } else {
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("config.vanished-list-header").replace("%count%", String.valueOf(total)));
            if (!localNames.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "<gray>" + String.join(", ", localNames));
            }
            if (!remoteNames.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender,
                        "<dark_gray>(remote) <gray>" + String.join(", ", remoteNames));
            }
        }

        return true;
    }
}