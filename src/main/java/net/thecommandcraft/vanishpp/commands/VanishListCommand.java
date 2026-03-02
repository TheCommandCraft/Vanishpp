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

        List<String> names = new ArrayList<>();
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                names.add(p.getName());
            }
        }

        if (names.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "<yellow>There are no vanished players online.");
        } else {
            plugin.getMessageManager().sendMessage(sender, "<gold>Vanished Players (" + names.size() + "):");
            plugin.getMessageManager().sendMessage(sender, "<gray>" + String.join(", ", names));
        }

        return true;
    }
}