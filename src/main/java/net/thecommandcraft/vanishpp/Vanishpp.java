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

import java.util.*;

public final class Vanishpp extends JavaPlugin {

    private Set<UUID> vanishedPlayers;
    private ConfigManager configManager;
    private PermissionManager permissionManager;
    private Team vanishTeam;
    private BukkitTask actionBarTask;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        configManager.load();

        this.permissionManager = new PermissionManager(this);
        permissionManager.load();

        this.vanishedPlayers = configManager.loadVanishedPlayers();
        getLogger().info("Loaded " + vanishedPlayers.size() + " vanished players from config.");

        setupTeams();
        this.getCommand("vanish").setExecutor(new VanishCommand(this));
        this.getCommand("vperms").setExecutor(new VpermsCommand(this));

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        startActionBarTask();
        getLogger().info("Vanish++ has been enabled!");
    }

    @Override
    public void onDisable() {
        if (actionBarTask != null && !actionBarTask.isCancelled()) actionBarTask.cancel();

        if (configManager != null) {
            getLogger().info("Making a final save of " + vanishedPlayers.size() + " vanished players to config...");
            configManager.save();
        }
        if (vanishTeam != null) vanishTeam.unregister();
    }

    private void setupTeams() {
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
        updateVanishVisibility(player);
        saveDataAsynchronously();
    }

    public void removeVanishEffects(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        player.setAffectsSpawning(true);
        if (vanishTeam.hasEntry(player.getName())) {
            vanishTeam.removeEntry(player.getName());
        }
        updateVanishVisibility(player);
        saveDataAsynchronously();
    }

    public void vanishPlayer(Player player, CommandSender executor) {
        applyVanishEffects(player);
        player.sendMessage(configManager.vanishMessage);
        if (configManager.fakeLeaveMessage) {
            Component quitMessage = Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, player.displayName());
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (!permissionManager.hasPermission(onlinePlayer, "vanishpp.see") && !onlinePlayer.equals(player)) {
                    onlinePlayer.sendMessage(quitMessage);
                }
            }
        }
        if (configManager.staffNotifyEnabled) {
            String notification = configManager.staffVanishMessage.replace("%player%", player.getName()).replace("%staff%", executor.getName());
            Bukkit.broadcast(Component.text(notification), "vanishpp.see");
        }
    }

    public void unvanishPlayer(Player player, CommandSender executor) {
        removeVanishEffects(player);
        player.sendMessage(configManager.unvanishMessage);
        if (configManager.fakeJoinMessage) {
            Component joinMessage = Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, player.displayName());
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (!permissionManager.hasPermission(onlinePlayer, "vanishpp.see") && !onlinePlayer.equals(player)) {
                    onlinePlayer.sendMessage(joinMessage);
                }
            }
        }
        if (configManager.staffNotifyEnabled) {
            String notification = configManager.staffUnvanishMessage.replace("%player%", player.getName()).replace("%staff%", executor.getName());
            Bukkit.broadcast(Component.text(notification), "vanishpp.see");
        }
    }

    public void updateVanishVisibility(Player subject) {
        boolean isVanished = isVanished(subject);
        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.equals(subject)) continue;
            boolean canSee = permissionManager.hasPermission(observer, "vanishpp.see");

            if (isVanished && !canSee) {
                observer.hidePlayer(this, subject);
            } else {
                observer.showPlayer(this, subject);
            }
        }
    }

    private void saveDataAsynchronously() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> configManager.save());
    }

    public boolean isVanished(Player player) { return vanishedPlayers.contains(player.getUniqueId()); }
    public ConfigManager getConfigManager() { return configManager; }
    public PermissionManager getPermissionManager() { return permissionManager; }
    public Set<UUID> getRawVanishedPlayers() { return Collections.unmodifiableSet(vanishedPlayers); }
    public Team getVanishTeam() { return vanishTeam; }
}