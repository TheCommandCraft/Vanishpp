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
        if (!plugin.getConfigManager().hideFromServerList) {
            return;
        }

        event.getListedPlayers().removeIf(profile ->
                plugin.getUnmodifiableVanishedPlayers().contains(profile.id())
        );

        int onlineVanishedCount = 0;
        for (UUID uuid : plugin.getUnmodifiableVanishedPlayers()) {
            if (Bukkit.getPlayer(uuid) != null) {
                onlineVanishedCount++;
            }
        }
        event.setNumPlayers(event.getNumPlayers() - onlineVanishedCount);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();
        Team vanishTeam = plugin.getVanishTeam();

        // Hide other vanished players from the one joining
        for (UUID vanishedUUID : plugin.getUnmodifiableVanishedPlayers()) {
            Player vanishedPlayer = Bukkit.getPlayer(vanishedUUID);
            if (vanishedPlayer != null && vanishedPlayer.isOnline()) {
                if (!joiningPlayer.hasPermission("vanishpp.see")) {
                    joiningPlayer.hidePlayer(plugin, vanishedPlayer);
                }
            }
        }

        // Handle the state of the joining player
        if (plugin.isVanished(joiningPlayer)) {
            // Player is supposed to be vanished, re-apply effects silently
            plugin.applyVanishEffects(joiningPlayer);
            event.joinMessage(null);
            String silentJoinMessage = plugin.getConfigManager().silentJoinMessage
                    .replace("%player%", joiningPlayer.getName());
            Bukkit.broadcast(Component.text(silentJoinMessage), "vanishpp.see");
        } else {
            // Player is NOT supposed to be vanished. Defensively remove them from the
            // team in case their state was changed while they were offline.
            if (vanishTeam != null && vanishTeam.hasEntry(joiningPlayer.getName())) {
                vanishTeam.removeEntry(joiningPlayer.getName());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        Team vanishTeam = plugin.getVanishTeam();
        if (vanishTeam != null && vanishTeam.hasEntry(quittingPlayer.getName())) {
            vanishTeam.removeEntry(quittingPlayer.getName());
        }

        if (plugin.isVanished(quittingPlayer)) {
            event.quitMessage(null);
            String silentQuitMessage = plugin.getConfigManager().silentQuitMessage
                    .replace("%player%", quittingPlayer.getName());
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