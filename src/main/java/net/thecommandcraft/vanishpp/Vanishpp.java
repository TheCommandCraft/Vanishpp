package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public final class Vanishpp extends JavaPlugin {

    private Set<UUID> vanishedPlayers;
    private ConfigManager configManager;
    private Team vanishTeam;
    private BukkitTask actionBarTask;

    @Override
    public void onEnable() {
        // Config Manager
        this.configManager = new ConfigManager(this);
        configManager.load();

        // Load vanished players from the config
        this.vanishedPlayers = configManager.loadVanishedPlayers();
        getLogger().info("Loaded " + vanishedPlayers.size() + " vanished players from config.");

        // Setup Scoreboard Team for prefixes
        setupVanishTeam();

        // Register command and listener
        this.getCommand("vanish").setExecutor(new VanishCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Start ActionBar task
        startActionBarTask();

        getLogger().info("Vanish++ has been enabled!");
    }

    @Override
    public void onDisable() {
        // Stop ActionBar task
        if (actionBarTask != null && !actionBarTask.isCancelled()) {
            actionBarTask.cancel();
        }

        // Save the config one last time
        if (configManager != null) {
            getLogger().info("Saving " + vanishedPlayers.size() + " vanished players to config...");
            configManager.save();
        }

        // Clean up the vanish team
        if (vanishTeam != null) {
            vanishTeam.unregister();
        }
    }

    private void setupVanishTeam() {
        Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        this.vanishTeam = mainScoreboard.getTeam("Vanishpp_Vanished");

        if (this.vanishTeam == null) {
            this.vanishTeam = mainScoreboard.registerNewTeam("Vanishpp_Vanished");
        }

        vanishTeam.setPrefix(Component.text(configManager.vanishPrefix));
        vanishTeam.setCanSeeFriendlyInvisibles(false);
    }

    private void startActionBarTask() {
        if (!configManager.actionBarEnabled || configManager.actionBarText.isEmpty()) {
            return;
        }

        Component actionBarComponent = Component.text(configManager.actionBarText);
        this.actionBarTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (UUID uuid : vanishedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.sendActionBar(actionBarComponent);
                }
            }
        }, 0L, 20L); // Run every second
    }

    public void applyVanishEffects(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        vanishTeam.addEntry(player.getName());

        if (configManager.disableBlockTriggering) {
            player.setAffectsSpawning(false);
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.hasPermission("vanishpp.see") && !onlinePlayer.equals(player)) {
                onlinePlayer.hidePlayer(this, player);
            }
        }
    }

    public void removeVanishEffects(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        player.setAffectsSpawning(true);

        if (vanishTeam != null && vanishTeam.hasEntry(player.getName())) {
            vanishTeam.removeEntry(player.getName());
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }
    }

    public void vanish(Player player, CommandSender executor) {
        applyVanishEffects(player);
        player.sendMessage(configManager.vanishMessage);

        if (configManager.fakeLeaveMessage) {
            Component quitMessage = Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, player.displayName());
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (!onlinePlayer.hasPermission("vanishpp.see") && !onlinePlayer.equals(player)) {
                    onlinePlayer.sendMessage(quitMessage);
                }
            }
        }

        if (configManager.staffNotifyEnabled) {
            String notification = configManager.staffVanishMessage
                    .replace("%player%", player.getName())
                    .replace("%staff%", executor.getName());
            Bukkit.broadcast(Component.text(notification), "vanishpp.see");
        }
    }

    public void unvanish(Player player, CommandSender executor) {
        removeVanishEffects(player);
        player.sendMessage(configManager.unvanishMessage);

        if (configManager.fakeJoinMessage) {
            Component joinMessage = Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, player.displayName());
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (!onlinePlayer.hasPermission("vanishpp.see") && !onlinePlayer.equals(player)) {
                    onlinePlayer.sendMessage(joinMessage);
                }
            }
        }

        if (configManager.staffNotifyEnabled) {
            String notification = configManager.staffUnvanishMessage
                    .replace("%player%", player.getName())
                    .replace("%staff%", executor.getName());
            Bukkit.broadcast(Component.text(notification), "vanishpp.see");
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public Set<UUID> getUnmodifiableVanishedPlayers() {
        return Collections.unmodifiableSet(vanishedPlayers);
    }

    public Set<UUID> getRawVanishedPlayers() {
        return vanishedPlayers;
    }

    public Team getVanishTeam() {
        return vanishTeam;
    }
}