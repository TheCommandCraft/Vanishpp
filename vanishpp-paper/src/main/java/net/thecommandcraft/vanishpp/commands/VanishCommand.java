package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.utils.LanguageManager;
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

import java.util.*;

/**
 * /vanish [player|all|world|group] [--visible <list>] [<duration>] [reason...]
 *
 * Subcommands:
 *   (none)                toggle self
 *   <player>              toggle another player
 *   all                   bulk vanish all eligible online players
 *   world                 bulk vanish players in sender's world
 *   cancel [player]       cancel a timed vanish
 *   reason <text>         update vanish reason mid-session
 *
 * Flags (parsed before reason):
 *   --visible <p1>[,p2]   enter partial-vanish mode, visible to listed players
 *   --visible none        remove partial-vanish whitelist (full vanish)
 *   <duration>            e.g. 30s, 5m, 2h — auto-unvanish after this time
 */
public class VanishCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {

        LanguageManager lm = plugin.getConfigManager().getLanguageManager();

        // /vanish cancel [player]
        if (args.length >= 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender, lm.getMessage("console-specify"));
                return true;
            }
            Player target = player;
            if (args.length >= 2 && sender.hasPermission("vanishpp.vanish.others")) {
                Player t = Bukkit.getPlayer(args[1]);
                if (t == null) {
                    plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().playerNotFoundMessage);
                    return true;
                }
                target = t;
            }
            if (!plugin.isVanished(target)) {
                plugin.getMessageManager().sendMessage(sender, lm.getMessage("player-not-found"));
                return true;
            }
            plugin.cancelTimedVanish(target.getUniqueId());
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("timed-vanish.cancelled").replace("%player%", target.getName()));
            return true;
        }

        // /vanish reason <text>  — update reason mid-session
        if (args.length >= 2 && args[0].equalsIgnoreCase("reason")) {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender, lm.getMessage("console-specify"));
                return true;
            }
            if (!plugin.isVanished(player)) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
                return true;
            }
            String newReason = buildReason(args, 1);
            plugin.setVanishReason(player.getUniqueId(), newReason.isEmpty() ? null : newReason);
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("vanish.reason-updated").replace("%reason%", newReason));
            return true;
        }

        if (args.length == 0) {
            // Toggle self
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender, lm.getMessage("console-specify"));
                return true;
            }
            if (!sender.hasPermission("vanishpp.vanish")) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            toggleVanish(player, sender, null, -1, null);
            return true;
        }

        String first = args[0].toLowerCase();

        // Bulk: /vanish all
        if (first.equals("all")) {
            if (!sender.hasPermission("vanishpp.vanish.bulk")) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            String reason = buildReason(args, 1);
            int count = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!plugin.isVanished(p)) {
                    plugin.vanishPlayer(p, sender, reason.isEmpty() ? null : reason);
                    count++;
                }
            }
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("bulk.vanished-all").replace("%count%", String.valueOf(count)));
            return true;
        }

        // Bulk: /vanish world
        if (first.equals("world")) {
            if (!sender.hasPermission("vanishpp.vanish.bulk")) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            if (!(sender instanceof Player senderPlayer)) {
                plugin.getMessageManager().sendMessage(sender, lm.getMessage("console-specify"));
                return true;
            }
            String reason = buildReason(args, 1);
            World world = senderPlayer.getWorld();
            int count = 0;
            for (Player p : world.getPlayers()) {
                if (!plugin.isVanished(p)) {
                    plugin.vanishPlayer(p, sender, reason.isEmpty() ? null : reason);
                    count++;
                }
            }
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("bulk.vanished-world")
                            .replace("%count%", String.valueOf(count))
                            .replace("%world%", world.getName()));
            return true;
        }

        // Targeting a specific player: /vanish <player> [flags] [duration] [reason...]
        if (!sender.hasPermission("vanishpp.vanish.others")) {
            // Could be targeting self with flags/duration
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender, lm.getMessage("console-specify"));
                return true;
            }
            if (!sender.hasPermission("vanishpp.vanish")) {
                plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
                return true;
            }
            // Parse flags + duration from args[0..]
            ParsedArgs parsed = parseArgs(args, 0);
            toggleVanish(player, sender, parsed.reason, parsed.durationSeconds, parsed.visibleList);
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            // Could be a flag like --visible
            if (sender instanceof Player player && sender.hasPermission("vanishpp.vanish")) {
                ParsedArgs parsed = parseArgs(args, 0);
                toggleVanish(player, sender, parsed.reason, parsed.durationSeconds, parsed.visibleList);
                return true;
            }
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().playerNotFoundMessage);
            return true;
        }

        ParsedArgs parsed = parseArgs(args, 1);
        toggleVanish(target, sender, parsed.reason, parsed.durationSeconds, parsed.visibleList);
        return true;
    }

    // ── Argument parsing ─────────────────────────────────────────────────────

    private static class ParsedArgs {
        String reason;
        int durationSeconds = -1;
        Set<UUID> visibleList = null;
    }

    /**
     * Parses flags (--visible), an optional duration token (e.g. "30s", "5m"), and a trailing reason
     * from args starting at {@code start}.
     */
    private ParsedArgs parseArgs(String[] args, int start) {
        ParsedArgs out = new ParsedArgs();
        List<String> remainder = new ArrayList<>();
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase("--visible") && i + 1 < args.length) {
                String list = args[++i];
                if (list.equalsIgnoreCase("none")) {
                    out.visibleList = new HashSet<>(); // empty = remove partial vanish
                } else {
                    out.visibleList = new HashSet<>();
                    for (String name : list.split(",")) {
                        Player p = Bukkit.getPlayer(name.trim());
                        if (p != null) out.visibleList.add(p.getUniqueId());
                    }
                }
                continue;
            }
            // Try parse as duration (only if not consumed yet and looks like a duration)
            if (out.durationSeconds < 0) {
                int dur = parseDuration(token);
                if (dur > 0) {
                    out.durationSeconds = dur;
                    continue;
                }
            }
            remainder.add(token);
        }
        if (!remainder.isEmpty()) {
            out.reason = String.join(" ", remainder).trim();
        }
        return out;
    }

    /** Parses "30s", "5m", "2h", "1d" into seconds. Returns -1 if not a duration token. */
    public static int parseDuration(String token) {
        if (token == null || token.length() < 2) return -1;
        char unit = Character.toLowerCase(token.charAt(token.length() - 1));
        if (unit != 's' && unit != 'm' && unit != 'h' && unit != 'd') return -1;
        try {
            int value = Integer.parseInt(token.substring(0, token.length() - 1));
            if (value <= 0) return -1;
            return switch (unit) {
                case 's' -> value;
                case 'm' -> value * 60;
                case 'h' -> value * 3600;
                case 'd' -> value * 86400;
                default  -> -1;
            };
        } catch (NumberFormatException e) {
            return -1;
        }
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

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("cancel");
            completions.add("reason");
            if (sender.hasPermission("vanishpp.vanish.bulk")) {
                completions.add("all");
                completions.add("world");
            }
            if (sender.hasPermission("vanishpp.vanish.others")) {
                for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            }
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender.hasPermission("vanishpp.vanish.others")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }
        return new ArrayList<>();
    }

    // ── Core toggle logic ─────────────────────────────────────────────────────

    private void toggleVanish(Player target, CommandSender executor, String reason,
                               int durationSeconds, Set<UUID> visibleList) {
        LanguageManager lm = plugin.getConfigManager().getLanguageManager();

        if (plugin.isVanished(target)) {
            // ── Unvanish ──
            plugin.cancelTimedVanish(target.getUniqueId());
            plugin.clearPartialVisibility(target.getUniqueId());
            plugin.unvanishPlayer(target, executor);
            if (!target.equals(executor)) {
                plugin.getMessageManager().sendMessage(executor,
                        plugin.getConfigManager().unvanishedOtherMessage.replace("%player%", target.getName()));
            }
        } else {
            // ── Vanish ──
            // Rate limit check (only for the player toggling themselves)
            if (target.equals(executor) && plugin.checkAndRecordToggleRateLimit(target)) {
                return;
            }

            // Anti-combat check
            int combatCooldown = plugin.getCombatCooldownRemaining(target);
            if (combatCooldown > 0) {
                String type = plugin.getCombatCooldownType(target);
                String msgKey = "pvp".equals(type) ? "combat.pvp-cooldown" : "combat.pve-cooldown";
                plugin.getMessageManager().sendMessage(executor,
                        lm.getMessage(msgKey).replace("%seconds%", String.valueOf(combatCooldown)));
                return;
            }

            // Apply vanish
            if (reason != null && !reason.isEmpty()) {
                plugin.vanishPlayer(target, executor, reason);
            } else {
                plugin.vanishPlayer(target, executor);
            }

            // Apply partial visibility whitelist
            if (visibleList != null) {
                if (visibleList.isEmpty()) {
                    plugin.clearPartialVisibility(target.getUniqueId());
                } else {
                    plugin.setPartialVisibility(target, visibleList);
                }
            }

            // Schedule timed unvanish
            if (durationSeconds > 0 && plugin.getConfigManager().timedVanishEnabled) {
                plugin.scheduleTimedVanish(target, durationSeconds);
            }

            if (!target.equals(executor)) {
                plugin.getMessageManager().sendMessage(executor,
                        plugin.getConfigManager().vanishedOtherMessage.replace("%player%", target.getName()));
            }
        }
    }
}
