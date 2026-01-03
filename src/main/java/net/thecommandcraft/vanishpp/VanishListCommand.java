package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.list")) {
            sender.sendMessage(Component.text(plugin.getConfigManager().noPermissionMessage, NamedTextColor.RED));
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
            sender.sendMessage(Component.text("There are no vanished players online.", NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text("Vanished Players (" + names.size() + "):", NamedTextColor.GOLD));
            sender.sendMessage(Component.text(String.join(", ", names), NamedTextColor.GRAY));
        }

        return true;
    }
}