package net.thecommandcraft.vanishpp;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.scoreboard.Team;

import java.util.Set;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final Vanishpp plugin;
    private final PermissionManager permissionManager;

    public PlayerListener(Vanishpp plugin) {
        this.plugin = plugin;
        this.permissionManager = plugin.getPermissionManager();
    }

    /**
     * Advanced Arrow Stealth: Makes arrows pass through vanished players seamlessly
     * by temporarily increasing their pierce level.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();

        // Check if a vanished player was hit by an arrow
        if (victim instanceof Player hitPlayer && damager instanceof Arrow arrow) {
            if (plugin.isVanished(hitPlayer)) {
                // Prevent the damage
                event.setCancelled(true);

                // Temporarily increase the arrow's pierce level to make it pass through.
                int originalPierceLevel = arrow.getPierceLevel();
                arrow.setPierceLevel(originalPierceLevel + 1);

                // Schedule a task to reset the pierce level on the next tick,
                // after the game has processed the pass-through.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!arrow.isDead()) {
                        arrow.setPierceLevel(originalPierceLevel);
                    }
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        handleOpCommand(event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        handleOpCommand(event.getCommand());
    }

    private void handleOpCommand(String command) {
        String lowerCaseCommand = command.toLowerCase().trim();
        if (lowerCaseCommand.startsWith("/")) {
            lowerCaseCommand = lowerCaseCommand.substring(1);
        }
        String[] parts = lowerCaseCommand.split("\\s+");
        if (parts.length < 2) return;
        String commandLabel = parts[0];
        if (commandLabel.equals("op") || commandLabel.equals("deop")) {
            Player target = Bukkit.getPlayer(parts[1]);
            if (target != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        plugin.updateVanishVisibility(onlinePlayer);
                    }
                });
            }
        }
    }

    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        if (!plugin.getConfigManager().hideFromServerList) return;
        Set<UUID> vanishedPlayers = plugin.getRawVanishedPlayers();
        event.getListedPlayers().removeIf(profile -> vanishedPlayers.contains(profile.id()));
        int onlineVanishedCount = 0;
        for (UUID uuid : vanishedPlayers) {
            if (Bukkit.getPlayer(uuid) != null) {
                onlineVanishedCount++;
            }
        }
        event.setNumPlayers(event.getNumPlayers() - onlineVanishedCount);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();

        if (plugin.isVanished(joiningPlayer)) {
            plugin.applyVanishEffects(joiningPlayer);
            event.joinMessage(null);
            String silentJoinMessage = plugin.getConfigManager().silentJoinMessage.replace("%player%", joiningPlayer.getName());
            Bukkit.broadcast(Component.text(silentJoinMessage), "vanishpp.see");
        }
        else {
            Team vanishTeam = plugin.getVanishTeam();
            if (vanishTeam.hasEntry(joiningPlayer.getName())) {
                vanishTeam.removeEntry(joiningPlayer.getName());
            }
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (plugin.isVanished(onlinePlayer)) {
                plugin.updateVanishVisibility(onlinePlayer);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();

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
            event.getRecipients().removeIf(recipient -> !permissionManager.hasPermission(recipient, "vanishpp.see"));
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
        if (plugin.isVanished(event.getEntity())) {
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
        if (plugin.isVanished(event.getPlayer())) {
            Component advancementMessage = event.message();
            event.message(null);
            if (advancementMessage != null) {
                Bukkit.getConsoleSender().sendMessage(advancementMessage);
                Bukkit.broadcast(advancementMessage, "vanishpp.see");
            }
        }
    }
}