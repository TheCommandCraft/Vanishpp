package net.thecommandcraft.vanishpp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public final class Vanishpp extends JavaPlugin {

    private Set<UUID> vanishedPlayers;
    private ConfigManager configManager;
    private Team vanishTeam;

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

        getLogger().info("Vanish++ has been enabled!");
    }

    @Override
    public void onDisable() {
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

        vanishTeam.setPrefix(configManager.vanishPrefix);
        vanishTeam.setCanSeeFriendlyInvisibles(false);
    }

    public void vanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        // No save call here - saving is handled onDisable

        vanishTeam.addEntry(player.getName());

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.hasPermission("vanishpp.see") && !onlinePlayer.equals(player)) {
                onlinePlayer.hidePlayer(this, player);
            }
        }
        player.sendMessage(configManager.vanishMessage);
    }

    public void unvanish(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        // No save call here - saving is handled onDisable

        if (vanishTeam != null && vanishTeam.hasEntry(player.getName())) {
            vanishTeam.removeEntry(player.getName());
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }
        player.sendMessage(configManager.unvanishMessage);
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
}