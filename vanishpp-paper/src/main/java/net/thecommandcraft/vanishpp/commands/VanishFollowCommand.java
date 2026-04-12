package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /vfollow <player>  — lock spectator camera onto a target player
 * /vfollow stop      — release the lock
 *
 * While following, the action bar shows the target's coordinates, health, and gamemode.
 * Requires vanishpp.spectator permission and that the sender is currently vanished.
 */
public class VanishFollowCommand implements CommandExecutor, TabCompleter, Listener {

    private final Vanishpp plugin;

    public VanishFollowCommand(Vanishpp plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("console-specify"));
            return true;
        }
        if (!plugin.getPermissionManager().hasPermission(player, "vanishpp.spectator")) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            stopFollow(player);
            return true;
        }

        if (!plugin.isVanished(player)) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("vfollow.must-be-vanished"));
            return true;
        }
        if (args.length == 0) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("vfollow.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || target.equals(player)) {
            plugin.getMessageManager().sendMessage(player, plugin.getConfigManager().playerNotFoundMessage);
            return true;
        }

        // Enter spectator if not already
        if (player.getGameMode() != GameMode.SPECTATOR) {
            plugin.spectateOriginalGamemodes.put(player.getUniqueId(), player.getGameMode());
            player.setGameMode(GameMode.SPECTATOR);
        }

        plugin.spectateFollowTargets.put(player.getUniqueId(), target.getUniqueId());
        player.teleport(target.getLocation());

        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("vfollow.started")
                        .replace("%player%", target.getName()));
        return true;
    }

    private void stopFollow(Player player) {
        UUID removed = plugin.spectateFollowTargets.remove(player.getUniqueId());
        if (removed == null) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("vfollow.not-following"));
            return;
        }
        // Restore gamemode
        GameMode gm = plugin.spectateOriginalGamemodes.remove(player.getUniqueId());
        if (gm != null) player.setGameMode(gm);
        player.sendActionBar(Component.empty());
        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("vfollow.stopped"));
    }

    // ── Follow tick — update action bar overlay every second ─────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        updateFollowOverlay(event.getPlayer());
    }

    // Called every time a followed player moves; not always a PlayerMoveEvent source
    private void updateFollowOverlay(Player follower) {
        UUID targetUuid = plugin.spectateFollowTargets.get(follower.getUniqueId());
        if (targetUuid == null) return;
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            stopFollow(follower);
            return;
        }
        // Teleport follower to target (spectator camera follows)
        if (!follower.getWorld().equals(target.getWorld())) {
            follower.teleport(target.getLocation());
        }
        // Action bar overlay
        String overlay = String.format("§7Following: §e%s §8| §7XYZ: §f%.1f, %.1f, %.1f §8| §7HP: §c%.1f §8| §7%s",
                target.getName(),
                target.getLocation().getX(),
                target.getLocation().getY(),
                target.getLocation().getZ(),
                target.getHealth(),
                target.getGameMode().name());
        follower.sendActionBar(plugin.getMessageManager().parse(overlay, target));
    }

    @EventHandler
    public void onTargetQuit(PlayerQuitEvent event) {
        UUID quitter = event.getPlayer().getUniqueId();
        // Anyone following this quitter should stop
        for (UUID followerUuid : new ArrayList<>(plugin.spectateFollowTargets.keySet())) {
            if (quitter.equals(plugin.spectateFollowTargets.get(followerUuid))) {
                Player follower = Bukkit.getPlayer(followerUuid);
                if (follower != null) {
                    plugin.spectateFollowTargets.remove(followerUuid);
                    plugin.getMessageManager().sendMessage(follower,
                            plugin.getConfigManager().getLanguageManager()
                                    .getMessage("vfollow.target-disconnected")
                                    .replace("%player%", event.getPlayer().getName()));
                }
            }
        }
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
