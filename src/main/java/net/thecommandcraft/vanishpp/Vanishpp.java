package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

public final class Vanishpp extends JavaPlugin {

    // Record class to store pre-ghost state cleanly
    private record PlayerState(GameMode gameMode, Location location) {}

    private Set<UUID> vanishedPlayers;
    private Map<UUID, PlayerState> ghostedPlayers;
    private ConfigManager configManager;
    private Team vanishTeam;
    private BukkitTask actionBarTask;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        configManager.load();
        this.vanishedPlayers = configManager.loadVanishedPlayers();
        this.ghostedPlayers = new HashMap<>();
        getLogger().info("Loaded " + vanishedPlayers.size() + " vanished players from config.");
        setupVanishTeam();
        this.getCommand("vanish").setExecutor(new VanishCommand(this));
        this.getCommand("ghost").setExecutor(new GhostCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        startActionBarTask();
        getLogger().info("Vanish++ has been enabled!");
    }

    @Override
    public void onDisable() {
        if (actionBarTask != null && !actionBarTask.isCancelled()) actionBarTask.cancel();

        // Safely exit ghost mode for any remaining players to prevent state issues on restart
        new ArrayList<>(ghostedPlayers.keySet()).forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) exitGhostMode(p);
        });

        if (configManager != null) {
            getLogger().info("Saving " + vanishedPlayers.size() + " vanished players to config...");
            configManager.save();
        }
        if (vanishTeam != null) vanishTeam.unregister();
    }

    private void setupVanishTeam() {
        Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        this.vanishTeam = mainScoreboard.getTeam("Vanishpp_Vanished");
        if (this.vanishTeam == null) this.vanishTeam = mainScoreboard.registerNewTeam("Vanishpp_Vanished");
        vanishTeam.prefix(Component.text(configManager.vanishPrefix));
        vanishTeam.setCanSeeFriendlyInvisibles(false);
    }

    private void startActionBarTask() {
        if (!configManager.actionBarEnabled || configManager.actionBarText.isEmpty()) return;
        Component actionBarComponent = Component.text(configManager.actionBarText);
        this.actionBarTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (UUID uuid : vanishedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) p.sendActionBar(actionBarComponent);
            }
        }, 0L, 20L);
    }

    public void applyVanishEffects(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        vanishTeam.addEntry(player.getName());
        if (configManager.disableBlockTriggering) player.setAffectsSpawning(false);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.hasPermission("vanishpp.see") && !onlinePlayer.equals(player)) {
                onlinePlayer.hidePlayer(this, player);
            }
        }
    }

    public void removeVanishEffects(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        player.setAffectsSpawning(true);
        if (vanishTeam != null && vanishTeam.hasEntry(player.getName())) vanishTeam.removeEntry(player.getName());
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
            String notification = configManager.staffVanishMessage.replace("%player%", player.getName()).replace("%staff%", executor.getName());
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
            String notification = configManager.staffUnvanishMessage.replace("%player%", player.getName()).replace("%staff%", executor.getName());
            Bukkit.broadcast(Component.text(notification), "vanishpp.see");
        }
    }

    public void enterGhostMode(Player player) {
        ghostedPlayers.put(player.getUniqueId(), new PlayerState(player.getGameMode(), player.getLocation()));
        player.setGameMode(GameMode.SPECTATOR);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.hasPermission("vanishpp.see")) {
                onlinePlayer.hidePlayer(this, player);
            }
        }
        player.sendMessage(configManager.ghostOnMessage);
    }

    public void exitGhostMode(Player player) {
        PlayerState originalState = ghostedPlayers.remove(player.getUniqueId());
        if (originalState != null) {
            player.teleport(originalState.location());
            player.setGameMode(originalState.gameMode());
        } else {
            player.setGameMode(Bukkit.getDefaultGameMode());
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }
        player.sendMessage(configManager.ghostOffMessage);
    }

    public boolean isGhosted(Player player) {
        return ghostedPlayers.containsKey(player.getUniqueId());
    }

    public ConfigManager getConfigManager() { return configManager; }
    public boolean isVanished(Player player) { return vanishedPlayers.contains(player.getUniqueId()); }
    public Set<UUID> getUnmodifiableVanishedPlayers() { return Collections.unmodifiableSet(vanishedPlayers); }
    public Set<UUID> getGhostedPlayerUUIDs() { return Collections.unmodifiableSet(ghostedPlayers.keySet()); }
    public Set<UUID> getRawVanishedPlayers() { return vanishedPlayers; }
    public Team getVanishTeam() { return vanishTeam; }
}