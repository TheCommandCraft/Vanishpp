package net.thecommandcraft.vanishpp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class Vanishpp extends JavaPlugin {

    // No longer final, it will be loaded from the config in onEnable
    private Set<UUID> vanishedPlayers;
    private ConfigManager configManager;

    @Override
    public void onEnable() {
        // Config Manager
        this.configManager = new ConfigManager(this);
        configManager.load();

        // Load vanished players from the config
        this.vanishedPlayers = configManager.loadVanishedPlayers();
        getLogger().info("Loaded " + vanishedPlayers.size() + " vanished players from config.");

        // Register command
        this.getCommand("vanish").setExecutor(new VanishCommand(this));

        // You'll need this in the next step to handle players logging in
        // getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getLogger().info("Vanish++ has been enabled!");
    }

    @Override
    public void onDisable() {
        // It's good practice to save one last time on shutdown.
        if (configManager != null && vanishedPlayers != null) {
            configManager.saveVanishedPlayers(vanishedPlayers);
        }

        // The rest of your onDisable is fine, it makes everyone visible for a clean server stop
        for (UUID uuid : vanishedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    onlinePlayer.showPlayer(this, player);
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

    public void vanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        // Save the updated list to the config
        configManager.saveVanishedPlayers(vanishedPlayers);

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.hasPermission("vanishpp.see") && !onlinePlayer.equals(player)) {
                onlinePlayer.hidePlayer(this, player);
            }
        }
        // Use the message from the config
        player.sendMessage(configManager.vanishMessage);
    }

    public void unvanish(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        // Save the updated list to the config
        configManager.saveVanishedPlayers(vanishedPlayers);

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }
        // Use the message from the config
        player.sendMessage(configManager.unvanishMessage);
    }
}