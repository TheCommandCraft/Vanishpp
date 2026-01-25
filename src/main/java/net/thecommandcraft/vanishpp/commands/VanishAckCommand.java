package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class VanishAckCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishAckCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) return true;

        if (args[0].equalsIgnoreCase("migration")) {
            List<String> acknowledged = plugin.getDataManager().getConfig().getStringList("acknowledged-notifications");
            String entry = player.getUniqueId().toString() + "_v" + plugin.getConfigManager().getLatestVersion();

            if (!acknowledged.contains(entry)) {
                acknowledged.add(entry);
                plugin.getDataManager().getConfig().set("acknowledged-notifications", acknowledged);
                plugin.getDataManager().save();
                player.sendMessage(Component.text("Migration report hidden. You won't see this again for v" + plugin.getConfigManager().getLatestVersion(), NamedTextColor.GREEN));
            }
        }
        return true;
    }
}