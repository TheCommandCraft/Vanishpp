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
import org.bukkit.scoreboard.Team;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        if (!plugin.getConfigManager().hideFromServerList) return;
        event.getListedPlayers().removeIf(profile -> plugin.getUnmodifiableVanishedPlayers().contains(profile.id()));
        int onlineVanishedCount = 0;
        for (UUID uuid : plugin.getUnmodifiableVanishedPlayers()) {
            if (Bukkit.getPlayer(uuid) != null) onlineVanishedCount++;
        }
        event.setNumPlayers(event.getNumPlayers() - onlineVanishedCount);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();
        Team vanishTeam = plugin.getVanishTeam();

        // Hide vanished players from the joining player
        for (UUID vanishedUUID : plugin.getUnmodifiableVanishedPlayers()) {
            Player vanishedPlayer = Bukkit.getPlayer(vanishedUUID);
            if (vanishedPlayer != null && vanishedPlayer.isOnline()) {
                if (!joiningPlayer.hasPermission("vanishpp.see")) {
                    joiningPlayer.hidePlayer(plugin, vanishedPlayer);
                }
            }
        }

        // Hide ghosted players from the joining player
        for (UUID ghostedUUID : plugin.getGhostedPlayerUUIDs()) {
            Player ghostedPlayer = Bukkit.getPlayer(ghostedUUID);
            if (ghostedPlayer != null && ghostedPlayer.isOnline()) {
                if (!joiningPlayer.hasPermission("vanishpp.see")) {
                    joiningPlayer.hidePlayer(plugin, ghostedPlayer);
                }
            }
        }

        if (plugin.isVanished(joiningPlayer)) {
            plugin.applyVanishEffects(joiningPlayer);
            event.joinMessage(null);
            String silentJoinMessage = plugin.getConfigManager().silentJoinMessage.replace("%player%", joiningPlayer.getName());
            Bukkit.broadcast(Component.text(silentJoinMessage), "vanishpp.see");
        } else {
            if (vanishTeam != null && vanishTeam.hasEntry(joiningPlayer.getName())) {
                vanishTeam.removeEntry(joiningPlayer.getName());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        if (plugin.isGhosted(quittingPlayer)) {
            plugin.exitGhostMode(quittingPlayer);
        }
        Team vanishTeam = plugin.getVanishTeam();
        if (vanishTeam != null && vanishTeam.hasEntry(quittingPlayer.getName())) {
            vanishTeam.removeEntry(quittingPlayer.getName());
        }
        if (plugin.isVanished(quittingPlayer)) {
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