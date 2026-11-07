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
        if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.history")) {
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

        // Bukkit.getPlayer() is main-thread-only (and Folia-unsafe off the region thread
        // entirely) - resolve the online-player UUID here, synchronously, before
        // dispatching to runAsync. Only the offline playerNameCache lookup (a plain
        // ConcurrentHashMap read) is safe to do from the async block itself.
        final UUID onlineUuid = finalPlayerName != null ? resolveOnlineUUID(finalPlayerName) : null;

        plugin.getVanishScheduler().runAsync(() -> {
            List<VanishHistoryEntry> entries;
            String header;

            if (finalPlayerName != null) {
                UUID targetUuid = onlineUuid != null ? onlineUuid
                        : plugin.getStorageProvider().resolveHistoryPlayer(finalPlayerName);
                if (targetUuid == null) {
                    plugin.getVanishScheduler().runSender(sender, () ->
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

            plugin.getVanishScheduler().runSender(sender, () -> {
                if (onlineUuid != null && !plugin.getPermissionManager().canSee(sender, onlineUuid)) {
                    plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().playerNotFoundMessage);
                    return;
                }
                var visibleEntries = finalEntries.stream()
                        .filter(entry -> plugin.getPermissionManager().canSee(sender, entry.getPlayerUuid())).toList();
                sender.sendMessage(plugin.getMessageManager().parse(finalHeader, null));
                if (visibleEntries.isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("history.no-entries"));
                    return;
                }
                for (VanishHistoryEntry e : visibleEntries) {
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
                if (visibleEntries.size() == PER_PAGE) {
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

    /** Main-thread-only - must be called before dispatching to an async task, never from one. */
    private UUID resolveOnlineUUID(String name) {
        Player online = Bukkit.getPlayer(name);
        return online != null ? online.getUniqueId() : null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.history")) return new ArrayList<>();
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (Player p : plugin.getPermissionManager().visiblePlayers(sender)) completions.add(p.getName());
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
