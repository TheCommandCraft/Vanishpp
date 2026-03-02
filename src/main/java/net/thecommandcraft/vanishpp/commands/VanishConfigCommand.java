package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VanishConfigCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishConfigCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.config")) {
            plugin.getMessageManager().sendMessage(sender, "<red>No permission.");
            return true;
        }

        if (args.length < 1) {
            plugin.getMessageManager().sendMessage(sender, "<red>Usage: /vconfig <key> [value]");
            return true;
        }

        String path = args[0];
        if (!plugin.getConfig().contains(path)) {
            plugin.getMessageManager().sendMessage(sender, "<red>Invalid config path!");
            return true;
        }

        if (args.length == 1) {
            Object val = plugin.getConfig().get(path);
            plugin.getMessageManager().sendMessage(sender, "<gold>" + path + " is currently: <white>" + val.toString());
            return true;
        }

        // Auto-detect type
        String valInput = args[1];
        Object newValue;

        if (valInput.equalsIgnoreCase("true") || valInput.equalsIgnoreCase("false")) {
            newValue = Boolean.parseBoolean(valInput);
        } else if (valInput.matches("-?\\d+")) {
            newValue = Integer.parseInt(valInput);
        } else {
            newValue = valInput;
        }

        plugin.getConfigManager().setAndSave(path, newValue);
        plugin.getMessageManager().sendMessage(sender, "<green>Successfully updated " + path + " to " + valInput);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> keys = new ArrayList<>(plugin.getConfig().getKeys(true));
            keys.removeIf(k -> k.equals("config-version") || k.startsWith("data"));
            return StringUtil.copyPartialMatches(args[0], keys, new ArrayList<>());
        }
        if (args.length == 2) {
            return Arrays.asList("true", "false");
        }
        return new ArrayList<>();
    }
}