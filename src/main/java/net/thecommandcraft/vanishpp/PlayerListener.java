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

import java.util.UUID;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles the server list ping to hide vanished players from the list and count.
     */
    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        if (!plugin.getConfigManager().hideFromServerList) {
            return;
        }

        // Hide from player list sample
        event.getListedPlayers().removeIf(profile ->
                plugin.getUnmodifiableVanishedPlayers().contains(profile.id())
        );

        // Hide from player count
        int onlineVanishedCount = 0;
        for (UUID uuid : plugin.getUnmodifiableVanishedPlayers()) {
            if (Bukkit.getPlayer(uuid) != null) { // Check if the vanished player is online
                onlineVanishedCount++;
            }
        }
        event.setNumPlayers(event.getNumPlayers() - onlineVanishedCount);
    }

    /**
     * Re-applies vanish status when a vanished player joins and hides other vanished
     * players from the joining player.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();

        // Hide already-vanished players from the new player
        for (UUID vanishedUUID : plugin.getUnmodifiableVanishedPlayers()) {
            Player vanishedPlayer = Bukkit.getPlayer(vanishedUUID);
            if (vanishedPlayer != null && vanishedPlayer.isOnline()) {
                if (!joiningPlayer.hasPermission("vanishpp.see")) {
                    joiningPlayer.hidePlayer(plugin, vanishedPlayer);
                }
            }
        }

        // If the joining player was vanished, re-apply their vanish state silently
        if (plugin.isVanished(joiningPlayer)) {
            plugin.applyVanishEffects(joiningPlayer); // Re-apply effects without fake messages
            event.joinMessage(null); // Hide the real join message
            String silentJoinMessage = plugin.getConfigManager().silentJoinMessage
                    .replace("%player%", joiningPlayer.getName());
            Bukkit.broadcast(Component.text(silentJoinMessage), "vanishpp.see");
        }
    }

    /**
     * Handles silent quit messages for vanished players.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        if (Bukkit.getScoreboardManager() != null && Bukkit.getScoreboardManager().getMainScoreboard().getTeam("Vanishpp_Vanished") != null) {
            Bukkit.getScoreboardManager().getMainScoreboard().getTeam("Vanishpp_Vanished").removeEntry(quittingPlayer.getName());
        }

        if (plugin.isVanished(quittingPlayer)) {
            event.quitMessage(null); // Hide the real quit message
            String silentQuitMessage = plugin.getConfigManager().silentQuitMessage
                    .replace("%player%", quittingPlayer.getName());
            Bukkit.broadcast(Component.text(silentQuitMessage), "vanishpp.see");
        }
    }

    /**
     * Formats chat for vanished players so only staff can see it.
     */
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

    /**
     * Prevents vanished players from triggering pressure plates and tripwires.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.getConfigManager().disableBlockTriggering) {
            return;
        }

        if (event.getAction() != Action.PHYSICAL) {
            return;
        }

        if (plugin.isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Hides death messages from vanished players from the public.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (plugin.isVanished(event.getPlayer()) && plugin.getConfigManager().hideDeathMessages) {
            Component deathMessage = event.deathMessage();
            event.deathMessage(null);
            if (deathMessage != null) {
                Bukkit.getConsoleSender().sendMessage(deathMessage);
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.hasPermission("vanishpp.see")) {
                        onlinePlayer.sendMessage(deathMessage);
                    }
                }
            }
        }
    }

    /**
     * Hides advancement messages from vanished players from the public.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (plugin.isVanished(event.getPlayer()) && plugin.getConfigManager().hideAdvancements) {
            Component advancementMessage = event.message();
            event.message(null);
            if (advancementMessage != null) {
                Bukkit.getConsoleSender().sendMessage(advancementMessage);
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.hasPermission("vanishpp.see")) {
                        onlinePlayer.sendMessage(advancementMessage);
                    }
                }
            }
        }
    }
}