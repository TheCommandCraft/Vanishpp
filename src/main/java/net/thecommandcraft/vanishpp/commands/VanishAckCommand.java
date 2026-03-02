package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
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
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player))
            return true;
        Player p = (Player) sender;

        if (args.length == 0)
            return true;

        String action = args[0];

        if (action.equals("migration")) {
            plugin.getStorageProvider().addAcknowledgement(p.getUniqueId(),
                    "migration_v" + plugin.getConfigManager().getLatestVersion());
            plugin.getMessageManager().sendMessage(p, "<green>Notification acknowledged.");
        } else if (action.equals("disable_hiding")) {
            if (!p.hasPermission("vanishpp.config")) {
                plugin.getMessageManager().sendMessage(p, "<red>No permission.");
                return true;
            }
            plugin.getConfigManager().setAndSave("hide-announcements.hide-from-plugin-list", false);
            plugin.getMessageManager().sendMessage(p, "<green>Vanish++ is now VISIBLE in the plugin list.");
        } else if (action.equals("acknowledge_hiding")) {
            plugin.getStorageProvider().addAcknowledgement(p.getUniqueId(),
                    "hiding_v" + plugin.getConfigManager().getLatestVersion());
            plugin.getMessageManager().sendMessage(p, "<gray>Notification dismissed for this version.");
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}