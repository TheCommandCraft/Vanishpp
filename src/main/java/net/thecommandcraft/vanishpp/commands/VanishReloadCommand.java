package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class VanishReloadCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishReloadCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.reload")) {
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
            return true;
        }

        plugin.reloadPluginConfig();
        plugin.getMessageManager().sendMessage(sender,
                plugin.getConfigManager().getLanguageManager().getMessage("config.reloaded"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
