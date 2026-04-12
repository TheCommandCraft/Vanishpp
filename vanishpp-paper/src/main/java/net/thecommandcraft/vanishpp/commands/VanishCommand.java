package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
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
            if (!sender.hasPermission("vanishpp.vanish")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
                return true;
            }
            toggleVanish(player, sender, null);
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
                    plugin.getConfigManager().getLanguageManager().getMessage("bulk.vanished-all")
                            .replace("%count%", String.valueOf(count)));
            return true;
        }

        // Bulk: /vanish world
        if (first.equals("world")) {
            if (!sender.hasPermission("vanishpp.vanish.bulk")) {
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
            int count = 0;
            for (Player p : world.getPlayers()) {
                if (!plugin.isVanished(p)) {
                    plugin.vanishPlayer(p, sender, reason.isEmpty() ? null : reason);
                    count++;
                }
            }
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("bulk.vanished-world")
                            .replace("%count%", String.valueOf(count))
                            .replace("%world%", world.getName()));
            return true;
        }

        // Targeting a specific player: /vanish <player> [reason...]
        if (!sender.hasPermission("vanishpp.vanish.others")) {
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().noPermissionMessage);
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().playerNotFoundMessage);
            return true;
        }

        String reason = buildReason(args, 1);
        toggleVanish(target, sender, reason.isEmpty() ? null : reason);
        return true;
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
            if (sender.hasPermission("vanishpp.vanish.bulk")) {
                completions.add("all");
                completions.add("world");
            }
            if (sender.hasPermission("vanishpp.vanish.others")) {
                for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            }
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }

    private void toggleVanish(Player target, CommandSender executor, String reason) {
        if (plugin.isVanished(target)) {
            // Unvanish
            plugin.unvanishPlayer(target, executor);

            // Notify executor if they are not the target
            if (!target.equals(executor)) {
                String msg = plugin.getConfigManager().unvanishedOtherMessage.replace("%player%", target.getName());
                plugin.getMessageManager().sendMessage(executor, msg);
            }
        } else {
            // Vanish (with optional reason)
            if (reason != null && !reason.isEmpty()) {
                plugin.vanishPlayer(target, executor, reason);
            } else {
                plugin.vanishPlayer(target, executor);
            }

            // Notify executor if they are not the target
            if (!target.equals(executor)) {
                String msg = plugin.getConfigManager().vanishedOtherMessage.replace("%player%", target.getName());
                plugin.getMessageManager().sendMessage(executor, msg);
            }
        }
    }
}
