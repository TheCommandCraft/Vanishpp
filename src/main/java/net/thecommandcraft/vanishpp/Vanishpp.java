package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    /**
     * Applies all vanish effects to a player. Called on join for vanished players.
     * @param player The player to apply effects to.
     */
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

    /**
     * Removes all vanish effects from a player.
     * @param player The player to remove effects from.
     */
    public void removeVanishEffects(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        player.setAffectsSpawning(true); // Always reset this state

        if (vanishTeam != null && vanishTeam.hasEntry(player.getName())) {
            vanishTeam.removeEntry(player.getName());
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }
    }

    /**
     * Called by the command to make a player vanish, including messages.
     * @param player The player to vanish.
     */
    public void vanish(Player player) {
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
    }

    /**
     * Called by the command to make a player unvanish, including messages.
     * @param player The player to unvanish.
     */
    public void unvanish(Player player) {
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