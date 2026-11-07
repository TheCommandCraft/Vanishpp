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
    private final java.util.Map<UUID, UUID> requests = new java.util.concurrent.ConcurrentHashMap<>();

    public VanishFollowCommand(Vanishpp plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // The follower's own PlayerMoveEvent (onMove below) only updates the overlay when
        // the FOLLOWER moves — if the follower stands still while the target walks away,
        // nothing re-synced them at all (the old cross-world-only check in
        // updateFollowOverlay() never re-teleported within the same world). This periodic
        // sweep drives the camera-follow independently of the follower's own movement.
        plugin.getVanishScheduler().runTimerGlobal(this::followTick, 20L, 4L);
    }

    private void followTick() {
        for (UUID followerUuid : new ArrayList<>(plugin.spectateFollowTargets.keySet())) {
            Player follower = Bukkit.getPlayer(followerUuid);
            if (follower == null) continue;
            // Teleporting a player requires its owning region thread on Folia.
            plugin.getVanishScheduler().runEntity(follower, () -> updateFollowOverlay(follower), null);
        }
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
        if (target == null || target.equals(player) || !plugin.getPermissionManager().canSee(player, target)) {
            plugin.getMessageManager().sendMessage(player, plugin.getConfigManager().playerNotFoundMessage);
            return true;
        }

        // Enter spectator if not already. saveGamemodeForRestore() only writes if nothing is
        // already pending, so this is safe to call unconditionally even if the player is
        // already in spectator from /vspec or the double-shift toggle.
        plugin.saveGamemodeForRestore(player);
        player.setGameMode(GameMode.SPECTATOR);

        plugin.spectateFollowTargets.put(player.getUniqueId(), target.getUniqueId());
        updateFollowOverlay(player);

        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("vfollow.started")
                        .replace("%player%", target.getName()));
        return true;
    }

    private void stopFollow(Player player) {
        requests.remove(player.getUniqueId());
        UUID removed = plugin.spectateFollowTargets.remove(player.getUniqueId());
        if (removed == null) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("vfollow.not-following"));
            return;
        }
        plugin.restoreSavedGamemode(player);
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
        if (target == null || !plugin.getPermissionManager().canSee(follower, target)) {
            stopFollow(follower);
            return;
        }
        UUID request = UUID.randomUUID();
        requests.put(follower.getUniqueId(), request);
        plugin.getVanishScheduler().runEntityNow(target, () -> {
            if (!target.isOnline()) return;
            plugin.voiceAudience.publish(target);
            org.bukkit.Location location = target.getLocation().clone();
            String overlay = String.format("§7Following: §e%s §8| §7XYZ: §f%.1f, %.1f, %.1f §8| §7HP: §c%.1f §8| §7%s",
                    target.getName(), location.getX(), location.getY(), location.getZ(), target.getHealth(), target.getGameMode().name());
            plugin.getVanishScheduler().runEntityNow(follower, () -> {
                if (!follower.isOnline() || !plugin.isVanished(follower)
                        || !targetUuid.equals(plugin.spectateFollowTargets.get(follower.getUniqueId()))
                        || !java.util.Objects.equals(requests.get(follower.getUniqueId()), request)) return;
                if (!plugin.getPermissionManager().canSee(follower, target)) { stopFollow(follower); return; }
                follower.teleportAsync(location);
                follower.sendActionBar(plugin.getMessageManager().parse(overlay, follower));
            });
        });
    }

    @EventHandler
    public void onTargetQuit(PlayerQuitEvent event) {
        UUID quitter = event.getPlayer().getUniqueId();
        requests.remove(quitter);
        // Anyone following this quitter should stop and have their gamemode restored
        for (UUID followerUuid : new ArrayList<>(plugin.spectateFollowTargets.keySet())) {
            if (quitter.equals(plugin.spectateFollowTargets.get(followerUuid))) {
                Player follower = Bukkit.getPlayer(followerUuid);
                if (follower != null) {
                    plugin.getVanishScheduler().runEntityNow(follower, () -> {
                    if (!quitter.equals(plugin.spectateFollowTargets.get(followerUuid))) return;
                    plugin.spectateFollowTargets.remove(followerUuid);
                    plugin.restoreSavedGamemode(follower);
                    follower.sendActionBar(Component.empty());
                    plugin.getMessageManager().sendMessage(follower,
                            plugin.getConfigManager().getLanguageManager()
                                    .getMessage("vfollow.target-disconnected")
                                    .replace("%player%", event.getPlayer().getName()));
                    });
                }
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (!plugin.getPermissionManager().hasPermission(sender, "vanishpp.spectator")) return new ArrayList<>();
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("stop");
            for (Player p : plugin.getPermissionManager().visiblePlayers(sender)) completions.add(p.getName());
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}
