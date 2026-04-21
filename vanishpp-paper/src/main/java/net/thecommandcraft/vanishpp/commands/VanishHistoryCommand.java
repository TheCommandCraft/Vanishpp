package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.storage.VanishHistoryEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /vhistory [player] [page]
 * Displays the audit log of vanish/unvanish events.
 * Without a player arg, shows all events across all players.
 */
public class VanishHistoryCommand implements CommandExecutor, TabCompleter {

    private static final int PER_PAGE = 10;
    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Vanishpp plugin;

    public VanishHistoryCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.history")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        // Parse args: [player] [page]
        String playerName = null;
        int page = 1;

        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                playerName = args[0];
                if (args.length >= 2) {
                    try { page = Integer.parseInt(args[1]); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }

        // Fetch async, display on main thread
        final String finalPlayerName = playerName;
        final int finalPage = Math.max(1, page);

        plugin.getVanishScheduler().runAsync(() -> {
            List<VanishHistoryEntry> entries;
            String header;

            if (finalPlayerName != null) {
                // Find UUID by name (online first, then storage)
                UUID targetUuid = resolveUUID(finalPlayerName);
                if (targetUuid == null) {
                    plugin.getVanishScheduler().runGlobal(() ->
                            plugin.getMessageManager().sendMessage(sender,
                                    plugin.getConfigManager().playerNotFoundMessage));
                    return;
                }
                entries = plugin.getStorageProvider().getPlayerHistory(targetUuid, finalPage, PER_PAGE);
                header = "§6Vanish History — §e" + finalPlayerName + " §7(Page " + finalPage + ")";
            } else {
                entries = plugin.getStorageProvider().getAllHistory(finalPage, PER_PAGE);
                header = "§6Vanish History §7(Page " + finalPage + ")";
            }

            final List<VanishHistoryEntry> finalEntries = entries;
            final String finalHeader = header;

            plugin.getVanishScheduler().runGlobal(() -> {
                sender.sendMessage(plugin.getMessageManager().parse(finalHeader, null));
                if (finalEntries.isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("history.no-entries"));
                    return;
                }
                for (VanishHistoryEntry e : finalEntries) {
                    String color = e.getAction() == VanishHistoryEntry.Action.VANISH ? "§a" : "§c";
                    String action = e.getAction() == VanishHistoryEntry.Action.VANISH ? "▲ VANISH" : "▼ UNVANISH";
                    String ts = FMT.format(e.getTimestamp());
                    String reason = e.getReason() != null && !e.getReason().isEmpty()
                            ? " §7(" + e.getReason() + ")" : "";
                    String duration = e.getAction() == VanishHistoryEntry.Action.UNVANISH
                            ? " §8[" + e.formatDuration() + "]" : "";
                    sender.sendMessage(Component.text(
                            " §8[" + ts + "] " + color + action + " §7" + e.getPlayerName()
                                    + reason + duration));
                }
                if (finalEntries.size() == PER_PAGE) {
                    String nextCmd = finalPlayerName != null
                            ? "/vhistory " + finalPlayerName + " " + (finalPage + 1)
                            : "/vhistory " + (finalPage + 1);
                    sender.sendMessage(Component.text(
                            "§7→ Type §b" + nextCmd + " §7for next page"));
                }
            });
        });
        return true;
    }

    private UUID resolveUUID(String name) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) return online.getUniqueId();
        return plugin.playerNameCache.get(name.toLowerCase());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
