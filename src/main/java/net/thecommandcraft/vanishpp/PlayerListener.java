package net.thecommandcraft.vanishpp;

// IMPORT THE CORRECT PAPER-SPECIFIC EVENT
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles the server list ping using the modern, Paper-specific event and method.
     * This uses event.getPlayerProfiles() which is the non-deprecated replacement
     * for event.getPlayerSample() in recent Paper versions.
     */
    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        if (!plugin.getConfigManager().hideFromServerList) {
            return;
        }

        // Use getPlayerProfiles(), the new non-deprecated method in the Paper API.
        // It returns a mutable List<PlayerProfile> that we can modify directly.
        event.getListedPlayers().removeIf(profile ->
                plugin.getUnmodifiableVanishedPlayers().contains(profile.id())
        );
    }

    // The rest of your listener class remains unchanged.

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();

        for (UUID vanishedUUID : plugin.getUnmodifiableVanishedPlayers()) {
            Player vanishedPlayer = Bukkit.getPlayer(vanishedUUID);
            if (vanishedPlayer != null && vanishedPlayer.isOnline()) {
                if (!joiningPlayer.hasPermission("vanishpp.see")) {
                    joiningPlayer.hidePlayer(plugin, vanishedPlayer);
                }
            }
        }

        if (plugin.isVanished(joiningPlayer)) {
            plugin.vanish(joiningPlayer);
            event.setJoinMessage(null);
            String silentJoinMessage = plugin.getConfigManager().silentJoinMessage
                    .replace("%player%", joiningPlayer.getName());
            Bukkit.broadcast(silentJoinMessage, "vanishpp.see");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        if (Bukkit.getScoreboardManager() != null && Bukkit.getScoreboardManager().getMainScoreboard().getTeam("Vanishpp_Vanished") != null) {
            Bukkit.getScoreboardManager().getMainScoreboard().getTeam("Vanishpp_Vanished").removeEntry(quittingPlayer.getName());
        }

        if (plugin.isVanished(quittingPlayer)) {
            event.setQuitMessage(null);
            String silentQuitMessage = plugin.getConfigManager().silentQuitMessage
                    .replace("%player%", quittingPlayer.getName());
            Bukkit.broadcast(silentQuitMessage, "vanishpp.see");
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
}