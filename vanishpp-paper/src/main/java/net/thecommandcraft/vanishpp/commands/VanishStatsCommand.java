package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.storage.VanishStats;
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
import java.util.UUID;

/**
 * /vstats [player]
 * Shows total vanish time, session count, and longest session for self or a target.
 */
public class VanishStatsCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishStatsCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.stats")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        final UUID targetUuid;
        final String targetName;

        if (args.length >= 1) {
            // Viewing another player's stats
            if (!sender.hasPermission("vanishpp.stats.others")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            Player online = Bukkit.getPlayer(args[0]);
            if (online != null) {
                targetUuid = online.getUniqueId();
                targetName = online.getName();
            } else {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().playerNotFoundMessage);
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
                return true;
            }
            targetUuid = player.getUniqueId();
            targetName = player.getName();
        }

        plugin.getVanishScheduler().runAsync(() -> {
            VanishStats stats = plugin.getStorageProvider().getStats(targetUuid);

            // Add in-progress session time if currently vanished
            long currentSessionMs = 0;
            Long startTime = plugin.vanishStartTimes.get(targetUuid);
            if (startTime != null && plugin.isVanished(Bukkit.getPlayer(targetUuid))) {
                currentSessionMs = System.currentTimeMillis() - startTime;
            }
            final long totalMs = stats.getTotalVanishTimeMs() + currentSessionMs;
            final VanishStats finalStats = new VanishStats(totalMs, stats.getVanishCount(), stats.getLongestSessionMs());
            final String name = targetName;

            plugin.getVanishScheduler().runGlobal(() -> {
                var lm = plugin.getConfigManager().getLanguageManager();
                plugin.getMessageManager().sendMessage(sender,
                        lm.getMessage("stats.header").replace("%player%", name));
                plugin.getMessageManager().sendMessage(sender,
                        lm.getMessage("stats.total-time").replace("%time%", finalStats.formatTotal()));
                plugin.getMessageManager().sendMessage(sender,
                        lm.getMessage("stats.session-count").replace("%count%", String.valueOf(finalStats.getVanishCount())));
                plugin.getMessageManager().sendMessage(sender,
                        lm.getMessage("stats.longest-session").replace("%time%", finalStats.formatLongest()));
            });
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("vanishpp.stats.others")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return StringUtil.copyPartialMatches(args[0], names, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
