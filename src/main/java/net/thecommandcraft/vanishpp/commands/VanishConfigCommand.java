package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.Set;

public class VanishConfigCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
            "storage.type", "storage.mysql.", "storage.redis.", "storage.postgresql.",
            "permissions.layered-permissions-enabled");

    public VanishConfigCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    private boolean isSensitive(String path) {
        for (String prefix : SENSITIVE_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix))
                return true;
        }
        return false;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.config")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("config.usage"));
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
            String valStr = val != null ? String.valueOf(val) : "<not set>";
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("config.current-value").replace("%path%", path).replace("%value%", valStr));
            return true;
        }

        // Check for --confirm flag
        boolean confirmed = args[args.length - 1].equals("--confirm");
        String valInput = args[1];

        // Sensitive path protection
        if (isSensitive(path) && !confirmed) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.sensitive-warning")
                    .replace("%path%", path));
            Component confirmBtn = Component.text("[CONFIRM CHANGE]", NamedTextColor.RED, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/vconfig " + path + " " + valInput + " --confirm"))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Click to confirm changing " + path, NamedTextColor.GRAY)));
            sender.sendMessage(confirmBtn);
            return true;
        }

        // Auto-detect type
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
                    return StringUtil.copyPartialMatches(args[1], List.of(current.toString()), new ArrayList<>());
                }
            }
            return Arrays.asList("true", "false");
        }
        // Do NOT suggest --confirm in tab completion
        return new ArrayList<>();
    }
}
