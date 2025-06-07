package net.thecommandcraft.vanishpp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.Iterator;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerListPing(ServerListPingEvent event) {
        if (!plugin.getConfigManager().hideFromServerList) {
            return;
        }

        Iterator<Player> iterator = event.iterator();
        while (iterator.hasNext()) {
            Player player = iterator.next();
            if (plugin.isVanished(player)) {
                iterator.remove();
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();

        // Hide already-vanished players from the player who just joined
        for (UUID vanishedUUID : plugin.getUnmodifiableVanishedPlayers()) {
            Player vanishedPlayer = Bukkit.getPlayer(vanishedUUID);
            if (vanishedPlayer != null) {
                if (!joiningPlayer.hasPermission("vanishpp.see")) {
                    joiningPlayer.hidePlayer(plugin, vanishedPlayer);
                }
            }
        }

        // Check if the joining player themselves should be vanished
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
        // Safety check to remove player from team on quit, preventing ghost prefixes.
        Bukkit.getScoreboardManager().getMainScoreboard().getTeam("Vanishpp_Vanished").removeEntry(quittingPlayer.getName());

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

            // Use the deprecated setFormat for older server compatibility, but log it for modern ones
            event.setFormat(format);

            // Clear the original recipients and only send the message to staff
            event.getRecipients().removeIf(recipient -> !recipient.hasPermission("vanishpp.see"));
        }
    }
}