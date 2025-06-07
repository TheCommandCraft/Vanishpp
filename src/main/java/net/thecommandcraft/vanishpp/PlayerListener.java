package net.thecommandcraft.vanishpp;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /**
     * When a player runs a command, check if it's /op or /deop and update visibility.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        handleOpCommand(event.getMessage());
    }

    /**
     * When the console runs a command, check if it's op or deop and update visibility.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        handleOpCommand(event.getCommand());
    }

    /**
     * Helper method to parse op/deop commands and trigger a visibility update.
     * @param command The full command string.
     */
    private void handleOpCommand(String command) {
        String lowerCaseCommand = command.toLowerCase().trim();
        // Remove leading slash if present
        if (lowerCaseCommand.startsWith("/")) {
            lowerCaseCommand = lowerCaseCommand.substring(1);
        }

        String[] parts = lowerCaseCommand.split("\\s+");
        if (parts.length < 2) return;

        String commandLabel = parts[0];
        if (commandLabel.equals("op") || commandLabel.equals("deop")) {
            String playerName = parts[1];
            Player target = Bukkit.getPlayer(playerName);

            // If the player is online, schedule a delayed task to update visibility.
            // The delay gives the server a moment to apply the permission change.
            if (target != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Update visibility for all players, as the target's ability to see has changed.
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        plugin.updatePlayerVisibility(onlinePlayer);
                    }
                });
            }
        }
    }

    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        if (!plugin.getConfigManager().hideFromServerList) return;

        Set<UUID> hiddenPlayers = new HashSet<>(plugin.getRawVanishedPlayers());
        hiddenPlayers.addAll(plugin.getRawGhostStates().keySet());

        event.getListedPlayers().removeIf(profile -> hiddenPlayers.contains(profile.id()));

        int onlineHiddenCount = 0;
        for (UUID uuid : hiddenPlayers) {
            if (Bukkit.getPlayer(uuid) != null) {
                onlineHiddenCount++;
            }
        }
        event.setNumPlayers(event.getNumPlayers() - onlineHiddenCount);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();

        // Re-apply persistent ghost mode first
        if (plugin.isGhosted(joiningPlayer)) {
            plugin.enterGhostMode(joiningPlayer);
        }

        // Re-apply persistent vanish mode
        if (plugin.isVanished(joiningPlayer)) {
            // Use the player as the executor since there isn't one on join
            plugin.applyVanishEffects(joiningPlayer, joiningPlayer);
            event.joinMessage(null);
            String silentJoinMessage = plugin.getConfigManager().silentJoinMessage.replace("%player%", joiningPlayer.getName());
            Bukkit.broadcast(Component.text(silentJoinMessage), "vanishpp.see");
        } else {
            // Defensively remove from vanish team if they aren't supposed to be vanished
            if (plugin.getVanishTeam().hasEntry(joiningPlayer.getName())) {
                plugin.getVanishTeam().removeEntry(joiningPlayer.getName());
            }
        }

        // Update visibility for all players now that a new player is online
        Bukkit.getOnlinePlayers().forEach(plugin::updatePlayerVisibility);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();

        // Do not call exitGhostMode here as we want the state to persist.
        // Just remove from the scoreboard team to prevent issues.
        if (plugin.isGhosted(quittingPlayer)) {
            if (plugin.getGhostTeam().hasEntry(quittingPlayer.getName())) {
                plugin.getGhostTeam().removeEntry(quittingPlayer.getName());
            }
        }
        if (plugin.isVanished(quittingPlayer)) {
            if (plugin.getVanishTeam().hasEntry(quittingPlayer.getName())) {
                plugin.getVanishTeam().removeEntry(quittingPlayer.getName());
            }
            event.quitMessage(null);
            String silentQuitMessage = plugin.getConfigManager().silentQuitMessage.replace("%player%", quittingPlayer.getName());
            Bukkit.broadcast(Component.text(silentQuitMessage), "vanishpp.see");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.isVanished(player)) {
            String format = plugin.getConfigManager().vanishedChatFormat
                    .replace("%prefix%", plugin.getConfigManager().vanishPrefix)
                    .replace("%player%", player.getDisplayName())
                    .replace("%message%", event.getMessage());
            event.setFormat(format);
            event.getRecipients().removeIf(recipient -> !recipient.hasPermission("vanishpp.see"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.getConfigManager().disableBlockTriggering) return;
        if (event.getAction() != Action.PHYSICAL) return;
        if (plugin.isVanished(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (plugin.isVanished(event.getPlayer()) && plugin.getConfigManager().hideDeathMessages) {
            Component deathMessage = event.deathMessage();
            event.deathMessage(null);
            if (deathMessage != null) {
                Bukkit.getConsoleSender().sendMessage(deathMessage);
                Bukkit.broadcast(deathMessage, "vanishpp.see");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (plugin.isVanished(event.getPlayer()) && plugin.getConfigManager().hideAdvancements) {
            Component advancementMessage = event.message();
            event.message(null);
            if (advancementMessage != null) {
                Bukkit.getConsoleSender().sendMessage(advancementMessage);
                Bukkit.broadcast(advancementMessage, "vanishpp.see");
            }
        }
    }
}