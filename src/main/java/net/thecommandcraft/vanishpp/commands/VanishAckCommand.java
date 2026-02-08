package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class VanishAckCommand implements TabExecutor {

    private final Vanishpp plugin;

    public VanishAckCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        if (args.length == 0) return true;

        String action = args[0];

        if (action.equals("migration")) {
            plugin.getDataManager().getConfig().getStringList("acknowledged-notifications").add(p.getUniqueId().toString() + "_v" + plugin.getConfigManager().getLatestVersion());
            plugin.getDataManager().save();
            p.sendMessage(Component.text("Notification acknowledged.", NamedTextColor.GREEN));
        } else if (action.equals("disable_hiding")) {
             if (!p.hasPermission("vanishpp.config")) {
                 p.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                 return true;
             }
             plugin.getConfigManager().setAndSave("hide-announcements.hide-from-plugin-list", false);
             p.sendMessage(Component.text("Vanish++ is now VISIBLE in the plugin list.", NamedTextColor.GREEN));
        } else if (action.equals("acknowledge_hiding")) {
            plugin.getDataManager().getConfig().getStringList("acknowledged-notifications").add(p.getUniqueId().toString() + "_hiding_v" + plugin.getConfigManager().getLatestVersion());
            plugin.getDataManager().save();
            p.sendMessage(Component.text("Notification dismissed for this version.", NamedTextColor.GRAY)); 
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}