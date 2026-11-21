package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.gui.ConfigGUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class VanishConfigCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;
    private final ConfigGUI configGUI;

    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
            "storage.type", "storage.mysql.", "storage.redis.", "storage.postgresql.",
            "permissions.layered-permissions-enabled");

    private static final Set<String> SECRET_PATHS = Set.of(
            "storage.mysql.password", "storage.postgresql.password", "storage.redis.password",
            "webhook.auth-header", "webhook.authorization", "webhook.urls");

    public VanishConfigCommand(Vanishpp plugin) {
        this.plugin = plugin;
        this.configGUI = new ConfigGUI(plugin);
    }

    /** Top-level keys that are internal and never offered for browsing. */
    private static boolean hiddenRootKey(String key) {
        return key.equals("config-version") || key.startsWith("data");
    }

    /**
     * Where the first {@code count} arguments point. Each argument is one level of the config tree
     * ({@code hooks simple-voice-chat enabled}); a dotted path in one argument ({@code hooks.simple-voice-chat.enabled})
     * still works, so older buttons and habits keep working. Returns null for a path that does not exist.
     */
    private record Target(String path, int next, boolean leaf) {}

    private static Target resolve(ConfigurationSection cfg, String[] args, int count) {
        String path = "";
        for (int i = 0; i < count; i++) {
            String candidate = path.isEmpty() ? args[i] : path + "." + args[i];
            if (args[i].isEmpty() || candidate.endsWith(".")) return null;
            if (cfg.isConfigurationSection(candidate)) { path = candidate; continue; }
            if (cfg.contains(candidate)) return new Target(candidate, i + 1, true);
            return null;
        }
        return new Target(path, count, false);
    }

    /** The keys directly inside a section; the root leaves out internal keys. */
    private static List<String> children(ConfigurationSection cfg, String path) {
        ConfigurationSection section = path.isEmpty() ? cfg : cfg.getConfigurationSection(path);
        if (section == null) return new ArrayList<>();
        List<String> keys = new ArrayList<>(section.getKeys(false));
        if (path.isEmpty()) keys.removeIf(VanishConfigCommand::hiddenRootKey);
        return keys;
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
        if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.config")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        // GUI subcommand
        if (args.length >= 1 && args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender, "§cOnly players can use the config GUI.");
                return true;
            }
            configGUI.open(player);
            return true;
        }

        if (args.length < 1) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("config.usage"));
            return true;
        }

        var lm = plugin.getConfigManager().getLanguageManager();
        var cfg = plugin.getConfigManager().getConfig();
        Target target = resolve(cfg, args, args.length);
        if (target == null || (!target.leaf() && target.path().isEmpty())) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.invalid-path"));
            return true;
        }
        String path = target.path();
        if (!target.leaf()) {
            // A section: list what is inside, so staff can browse one level at a time.
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.section-contents")
                    .replace("%path%", path.replace('.', ' '))
                    .replace("%keys%", String.join(", ", children(cfg, path))));
            return true;
        }

        if (target.next() == args.length) {
            Object val = plugin.getConfigManager().getConfig().get(path);
            String valStr = val != null ? String.valueOf(val) : "<not set>";
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("config.current-value").replace("%path%", path).replace("%value%", valStr));
            return true;
        }

        // Check for --confirm flag. The value is everything in between, so text values may contain spaces;
        // surrounding quotes are dropped ("&c[ADMIN] " keeps its trailing space).
        boolean confirmed = args[args.length - 1].equals("--confirm");
        int valueEnd = confirmed ? args.length - 1 : args.length;
        if (valueEnd <= target.next()) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.usage"));
            return true;
        }
        String valInput = String.join(" ", Arrays.copyOfRange(args, target.next(), valueEnd));
        if (valInput.length() >= 2 && valInput.startsWith("\"") && valInput.endsWith("\""))
            valInput = valInput.substring(1, valInput.length() - 1);

        // Sensitive path protection
        if (isSensitive(path) && !confirmed) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.sensitive-warning")
                    .replace("%path%", path));
            Component confirmBtn = plugin.getMessageManager().parse(
                    lm.getMessage("config.button-confirm-change"), null)
                    .clickEvent(ClickEvent.runCommand("/vconfig " + path + " "
                            + (valInput.contains(" ") ? "\"" + valInput + "\"" : valInput) + " --confirm"))
                    .hoverEvent(HoverEvent.showText(
                            plugin.getMessageManager().parse(
                                    lm.getMessage("config.button-confirm-change-hover")
                                            .replace("%path%", path), null)));
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

        // When proxy is active, forward the change to Velocity so all servers get it
        var bridge = plugin.getProxyBridge();
        if (bridge != null && bridge.isProxyDetected()) {
            bridge.sendConfigSync(java.util.Map.of(path, valInput));
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("config.proxy-synced"));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.config")) return new ArrayList<>();
        var cfg = plugin.getConfigManager().getConfig();
        String typed = args[args.length - 1];
        Target target = resolve(cfg, args, args.length - 1);
        if (target == null) return new ArrayList<>();
        if (!target.leaf()) {
            // One level of the tree at a time: hooks -> simple-voice-chat -> enabled.
            List<String> options = children(cfg, target.path());
            if (args.length == 1) options.add("gui");
            return StringUtil.copyPartialMatches(typed, options, new ArrayList<>());
        }
        if (target.next() != args.length - 1 || SECRET_PATHS.contains(target.path())) return new ArrayList<>();
        Object current = cfg.get(target.path());
        if (current instanceof Boolean)
            return StringUtil.copyPartialMatches(typed, Arrays.asList("true", "false"), new ArrayList<>());
        if (current != null)
            return StringUtil.copyPartialMatches(typed, List.of(current.toString()), new ArrayList<>());
        // Do NOT suggest --confirm in tab completion
        return new ArrayList<>();
    }
}
