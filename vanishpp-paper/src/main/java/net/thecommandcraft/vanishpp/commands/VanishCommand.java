package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.utils.LanguageManager;
import net.thecommandcraft.vanishpp.utils.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.World;
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

/**
 * /vanish [player|all|world|group] [reason...]
 *
 * - No args:           toggle self
 * - player:            toggle a specific player (requires vanishpp.vanish.others)
 * - all:               vanish/unvanish all online players (requires vanishpp.vanish.bulk)
 * - world:             vanish/unvanish all players in the sender's world (requires vanishpp.vanish.bulk)
 * - version / info:    show the running version and update status (requires vanishpp.vanish), also /vinfo
 * - Reason support:    any trailing tokens after the player name become the vanish reason
 */
public class VanishCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {

        if (args.length == 0) {
            // Toggle self
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
                return true;
            }
            if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
                return true;
            }
            toggleVanish(player, sender, null);
            return true;
        }

        String first = args[0].toLowerCase();

        // Info: /vanish version, /vanish info
        if (first.equals("version") || first.equals("info")) {
            if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
                return true;
            }
            sendVersionInfo(sender);
            return true;
        }

        // Bulk: /vanish all
        if (first.equals("all")) {
            if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish.bulk")) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            String reason = buildReason(args, 1);
            vanishBulk(new ArrayList<>(Bukkit.getOnlinePlayers()), sender, reason,
                    plugin.getConfigManager().getLanguageManager().getMessage("bulk.vanished-all"));
            return true;
        }

        // Bulk: /vanish world
        if (first.equals("world")) {
            if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish.bulk")) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            if (!(sender instanceof Player senderPlayer)) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
                return true;
            }
            String reason = buildReason(args, 1);
            World world = senderPlayer.getWorld();
            vanishBulk(new ArrayList<>(world.getPlayers()), sender, reason,
                    plugin.getConfigManager().getLanguageManager().getMessage("bulk.vanished-world")
                            .replace("%world%", world.getName()));
            return true;
        }

        // Targeting a specific player: /vanish <player> [reason...]
        if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish.others")) {
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !plugin.getPermissionManager().canSee(sender, target)) {
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().playerNotFoundMessage);
            return true;
        }

        String reason = buildReason(args, 1);
        toggleVanish(target, sender, reason.isEmpty() ? null : reason);
        return true;
    }

    /** Version, update status and where to find help. Also used by /vhelp. */
    public static void sendVersionInfo(Vanishpp plugin, CommandSender sender) {
        LanguageManager lm = plugin.getConfigManager().getLanguageManager();
        plugin.getMessageManager().sendMessage(sender, lm.getMessage("version.header")
                .replace("%version%", plugin.getDescription().getVersion())
                .replace("%authors%", String.join(", ", plugin.getDescription().getAuthors())));

        UpdateChecker checker = plugin.getUpdateChecker();
        Boolean checked = checker != null ? checker.getLastCheckSucceeded() : null;
        if (checker == null || !plugin.getConfigManager().updateCheckerEnabled) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("version.update-check-disabled"));
        } else if (checker.isUpdateAvailable()) {
            String latest = checker.getLatestVersion();
            sender.sendMessage(plugin.getMessageManager()
                    .parse(lm.getMessage("version.update-available").replace("%version%", latest), null)
                    .clickEvent(ClickEvent.openUrl(checker.getDownloadUrl()))
                    .hoverEvent(HoverEvent.showText(plugin.getMessageManager()
                            .parse(lm.getMessage("update.hover").replace("%version%", latest), null))));
        } else if (checked == null) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("version.update-checking"));
        } else if (!checked) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("version.update-check-failed"));
        } else {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("version.up-to-date"));
        }
    }

    private void sendVersionInfo(CommandSender sender) {
        sendVersionInfo(plugin, sender);
        LanguageManager lm = plugin.getConfigManager().getLanguageManager();
        plugin.getMessageManager().sendMessage(sender, lm.getMessage("version.modrinth"));
        plugin.getMessageManager().sendMessage(sender, lm.getMessage("version.help-hint"));
    }

    /** Joins args[start..] into a reason string. Returns empty string if none. */
    private String buildReason(String[] args, int start) {
        if (args.length <= start) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish")) {
                completions.add("version");
                completions.add("info");
            }
            if (plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish.bulk")) {
                completions.add("all");
                completions.add("world");
            }
            if (plugin.getPermissionManager().hasPermission(sender, "vanishpp.vanish.others")) {
                for (Player p : plugin.getPermissionManager().visiblePlayers(sender)) completions.add(p.getName());
            }
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }

    private void toggleVanish(Player target, CommandSender executor, String reason) {
        boolean hidden = !plugin.isVanished(target);
        plugin.requestVanish(target, executor, hidden, reason, accepted -> {
            if (!accepted || target.equals(executor)) return;
            String template = hidden ? plugin.getConfigManager().vanishedOtherMessage : plugin.getConfigManager().unvanishedOtherMessage;
            String message = template.replace("%player%", target.getName());
            plugin.getVanishScheduler().runSender(executor, () -> plugin.getMessageManager().sendMessage(executor, message));
        });
    }

    private void vanishBulk(List<Player> players, CommandSender sender, String reason, String template) {
        players.removeIf(plugin::isVanished);
        var remaining = new java.util.concurrent.atomic.AtomicInteger(players.size());
        var acceptedCount = new java.util.concurrent.atomic.AtomicInteger();
        Runnable report = () -> plugin.getVanishScheduler().runSender(sender, () ->
                plugin.getMessageManager().sendMessage(sender, template.replace("%count%", String.valueOf(acceptedCount.get()))));
        if (players.isEmpty()) { report.run(); return; }
        for (Player target : players) {
            plugin.requestVanish(target, sender, true, reason.isEmpty() ? null : reason, accepted -> {
                if (accepted) acceptedCount.incrementAndGet();
                if (remaining.decrementAndGet() == 0) report.run();
            });
        }
    }
}
