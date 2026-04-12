package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * /vincognito [fakename|off]  — toggle incognito mode for yourself
 * /vincognito <player> [fakename|off]  — toggle for another player (requires vanishpp.incognito.others)
 *
 * In incognito mode the player's chat messages show a fake name instead of the real one.
 * The fake name is chosen from incognito.fake-names in config.yml, or randomly assigned.
 */
public class IncognitoCommand implements CommandExecutor, TabCompleter {

    private static final Random RANDOM = new Random();

    private final Vanishpp plugin;

    public IncognitoCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.incognito")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        Player target;
        String requestedName = null;

        if (args.length == 0) {
            // Self toggle
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
                return true;
            }
            target = player;
        } else if (args.length == 1) {
            // Could be a player name OR a fake name / "off"
            Player online = Bukkit.getPlayer(args[0]);
            if (online != null && !online.equals(sender)) {
                // Treat as targeting another player
                if (!sender.hasPermission("vanishpp.incognito.others")) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().noPermissionMessage);
                    return true;
                }
                target = online;
            } else {
                // Treat as a fake name / "off" for self
                if (!(sender instanceof Player player)) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
                    return true;
                }
                target = player;
                requestedName = args[0];
            }
        } else {
            // /vincognito <player> <fakename|off>
            if (!sender.hasPermission("vanishpp.incognito.others")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().playerNotFoundMessage);
                return true;
            }
            requestedName = args[1];
        }

        var lm = plugin.getConfigManager().getLanguageManager();

        // Explicit disable
        if ("off".equalsIgnoreCase(requestedName)) {
            if (plugin.incognitoNames.remove(target.getUniqueId()) != null) {
                plugin.getMessageManager().sendMessage(sender,
                        lm.getMessage("incognito.disabled")
                                .replace("%player%", target.getName()));
            } else {
                plugin.getMessageManager().sendMessage(sender,
                        lm.getMessage("incognito.not-active")
                                .replace("%player%", target.getName()));
            }
            return true;
        }

        // Toggle off if already active
        if (requestedName == null && plugin.incognitoNames.containsKey(target.getUniqueId())) {
            plugin.incognitoNames.remove(target.getUniqueId());
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("incognito.disabled")
                            .replace("%player%", target.getName()));
            return true;
        }

        // Choose a fake name
        String fakeName;
        if (requestedName != null) {
            fakeName = requestedName;
        } else {
            fakeName = pickRandomName(target.getName());
        }

        // Validate length (Minecraft name limit = 16 chars)
        if (fakeName.length() > 16 || fakeName.isBlank()) {
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("incognito.invalid-name"));
            return true;
        }

        plugin.incognitoNames.put(target.getUniqueId(), fakeName);
        plugin.getMessageManager().sendMessage(sender,
                lm.getMessage("incognito.enabled")
                        .replace("%player%", target.getName())
                        .replace("%fakename%", fakeName));
        return true;
    }

    private String pickRandomName(String realName) {
        List<String> pool = plugin.getConfigManager().incognitoFakeNames;
        if (pool == null || pool.isEmpty()) {
            // Fall back to a generic placeholder
            return "Player" + RANDOM.nextInt(9999);
        }
        // Filter out the real name to avoid handing the player their own name
        List<String> filtered = new ArrayList<>();
        for (String n : pool) {
            if (!n.equalsIgnoreCase(realName)) filtered.add(n);
        }
        if (filtered.isEmpty()) return pool.get(RANDOM.nextInt(pool.size()));
        return filtered.get(RANDOM.nextInt(filtered.size()));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            // self options: off + fake names
            completions.add("off");
            List<String> pool = plugin.getConfigManager().incognitoFakeNames;
            if (pool != null) completions.addAll(pool);
            // other-player options (if they have permission)
            if (sender.hasPermission("vanishpp.incognito.others")) {
                for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            }
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        if (args.length == 2) {
            List<String> completions = new ArrayList<>();
            completions.add("off");
            List<String> pool = plugin.getConfigManager().incognitoFakeNames;
            if (pool != null) completions.addAll(pool);
            return StringUtil.copyPartialMatches(args[1], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
