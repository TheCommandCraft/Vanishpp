package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
 * /vspec <player>  — teleport to target in spectator while vanished
 * /vspec stop      — return to saved origin location + gamemode
 *
 * Requires: vanishpp.spectator + vanishpp.vanish
 */
public class VanishSpectateCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishSpectateCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
            return true;
        }
        if (!plugin.getPermissionManager().hasPermission(player, "vanishpp.spectator")
                || !plugin.getPermissionManager().hasPermission(player, "vanishpp.vanish")) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        // /vspec stop
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            stopSpec(player);
            return true;
        }

        // /vspec <player>
        if (args.length == 0) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("vspec.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || target.equals(player)) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().playerNotFoundMessage);
            return true;
        }

        // Auto-vanish if not already vanished
        if (!plugin.isVanished(player)) {
            plugin.vanishPlayer(player, player);
        }

        // Save origin
        plugin.spectateOrigins.put(player.getUniqueId(), player.getLocation().clone());
        plugin.spectateOriginalGamemodes.put(player.getUniqueId(), player.getGameMode());

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(target.getLocation());
        plugin.spectateFollowTargets.put(player.getUniqueId(), target.getUniqueId());

        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("vspec.started")
                        .replace("%player%", target.getName()));
        return true;
    }

    private void stopSpec(Player player) {
        UUID uuid = player.getUniqueId();
        Location origin = plugin.spectateOrigins.remove(uuid);
        GameMode gm = plugin.spectateOriginalGamemodes.remove(uuid);
        plugin.spectateFollowTargets.remove(uuid);

        if (origin == null) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("vspec.not-spectating"));
            return;
        }

        player.setGameMode(gm != null ? gm : GameMode.SURVIVAL);
        player.teleport(origin);
        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("vspec.stopped"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("stop");
            for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
