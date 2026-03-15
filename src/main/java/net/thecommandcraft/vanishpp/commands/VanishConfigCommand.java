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
        var lm = plugin.getConfigManager().getLanguageManager();
        if (!plugin.getConfigManager().getConfig().contains(path)) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.invalid-path"));
            return true;
        }

        if (args.length == 1) {
            Object val = plugin.getConfigManager().getConfig().get(path);
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("config.current-value").replace("%path%", path).replace("%value%", String.valueOf(val)));
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
        plugin.getMessageManager().sendMessage(sender,
                lm.getMessage("config.updated").replace("%path%", path).replace("%value%", valInput));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            var cfg = plugin.getConfigManager().getConfig();
            List<String> keys = new ArrayList<>(cfg.getKeys(true));
            // Keep only leaf values (not section containers)
            keys.removeIf(k -> cfg.isConfigurationSection(k) || k.equals("config-version") || k.startsWith("data"));
            return StringUtil.copyPartialMatches(args[0], keys, new ArrayList<>());
        }
        if (args.length == 2) {
            var cfg = plugin.getConfigManager().getConfig();
            if (cfg.contains(args[0])) {
                Object current = cfg.get(args[0]);
                if (current instanceof Boolean) {
                    return StringUtil.copyPartialMatches(args[1], Arrays.asList("true", "false"), new ArrayList<>());
                } else if (current != null) {
                    // Suggest the current value so the user can see/edit it
                    return StringUtil.copyPartialMatches(args[1], List.of(current.toString()), new ArrayList<>());
                }
            }
            return Arrays.asList("true", "false");
        }
        return new ArrayList<>();
    }
}