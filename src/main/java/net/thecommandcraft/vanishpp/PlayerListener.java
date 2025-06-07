package net.thecommandcraft.vanishpp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();

        // 1. Hide already-vanished players from the player who just joined.
        // We loop through our list of vanished UUIDs.
        for (UUID vanishedUUID : plugin.getVanishedPlayers()) {
            Player vanishedPlayer = Bukkit.getPlayer(vanishedUUID);
            // If the vanished player is online, hide them from the joining player.
            if (vanishedPlayer != null) {
                joiningPlayer.hidePlayer(plugin, vanishedPlayer);
            }
        }

        // 2. Check if the joining player themselves should be vanished.
        if (plugin.isVanished(joiningPlayer)) {
            // Hide their join message.
            event.setJoinMessage(null);

            // Re-apply their vanish effect to hide them from all other online players.
            plugin.vanish(joiningPlayer); // We can just re-call vanish()

            // Send a silent join message to staff.
            String silentJoinMessage = plugin.getConfigManager().silentJoinMessage
                    .replace("%player%", joiningPlayer.getName());
            Bukkit.broadcast(silentJoinMessage, "vanishpp.see");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();

        // Check if the player who is quitting is vanished.
        if (plugin.isVanished(quittingPlayer)) {
            // Hide their quit message.
            event.setQuitMessage(null);

            // Send a silent quit message to staff.
            String silentQuitMessage = plugin.getConfigManager().silentQuitMessage
                    .replace("%player%", quittingPlayer.getName());
            Bukkit.broadcast(silentQuitMessage, "vanishpp.see");
        }
    }
}