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

    public record GhostState(GameMode gameMode, Location location) {}

    private Set<UUID> vanishedPlayers;
    private Map<UUID, GhostState> ghostedPlayers;
    private ConfigManager configManager;
    private Team vanishTeam;
    private Team ghostTeam;
    private BukkitTask actionBarTask;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        configManager.load();
        this.vanishedPlayers = configManager.loadVanishedPlayers();
        this.ghostedPlayers = configManager.loadGhostStates();
        getLogger().info("Loaded " + vanishedPlayers.size() + " vanished and " + ghostedPlayers.size() + " ghosted players from config.");

        setupTeams();
        this.getCommand("vanish").setExecutor(new VanishCommand(this));
        this.getCommand("ghost").setExecutor(new GhostCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        startActionBarTask();
        getLogger().info("Vanish++ has been enabled!");
    }

    @Override
    public void onDisable() {
        if (actionBarTask != null && !actionBarTask.isCancelled()) actionBarTask.cancel();

        if (configManager != null) {
            getLogger().info("Saving " + vanishedPlayers.size() + " vanished and " + ghostedPlayers.size() + " ghosted players to config...");
            configManager.save();
        }
        if (vanishTeam != null) vanishTeam.unregister();
        if (ghostTeam != null) ghostTeam.unregister();
    }

    private void setupTeams() {
        Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        this.vanishTeam = mainScoreboard.getTeam("Vanishpp_Vanished");
        if (this.vanishTeam == null) this.vanishTeam = mainScoreboard.registerNewTeam("Vanishpp_Vanished");
        vanishTeam.prefix(Component.text(configManager.vanishPrefix));
        vanishTeam.setCanSeeFriendlyInvisibles(false);

        this.ghostTeam = mainScoreboard.getTeam("Vanishpp_Ghosted");
        if (this.ghostTeam == null) this.ghostTeam = mainScoreboard.registerNewTeam("Vanishpp_Ghosted");

        // --- TAB LIST FIX ---
        // This is a more forceful method to ensure the name is styled correctly.
        // The prefix sets the color to white right before the name is rendered.
        ghostTeam.prefix(Component.text("", NamedTextColor.WHITE));
        ghostTeam.color(NamedTextColor.WHITE); // Set color as well for robustness.
        ghostTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
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

    public void applyVanishEffects(Player player, CommandSender executor) {
        vanishedPlayers.add(player.getUniqueId());
        vanishTeam.addEntry(player.getName());
        if (configManager.disableBlockTriggering) player.setAffectsSpawning(false);
        updateVanishVisibility(player);
        player.sendMessage(configManager.vanishMessage);
    }

    public void removeVanishEffects(Player player, CommandSender executor) {
        vanishedPlayers.remove(player.getUniqueId());
        player.setAffectsSpawning(true);
        if (vanishTeam.hasEntry(player.getName())) vanishTeam.removeEntry(player.getName());
        updateVanishVisibility(player);
        player.sendMessage(configManager.unvanishMessage);
    }

    // --- GHOST MODE REWORK ---

    /**
     * Enters ghost mode for the first time, saving the player's original state.
     * This is called by the /ghost command.
     */
    public void enterGhostMode(Player player) {
        // This is the key to fixing the state bug: we only save the state ONCE.
        ghostedPlayers.put(player.getUniqueId(), new GhostState(player.getGameMode(), player.getLocation()));
        player.sendMessage(configManager.ghostOnMessage);
        applyGhostEffects(player);
    }

    /**
     * Re-applies the ghost mode effects to a player who was already ghosted (e.g., on login).
     * This method DOES NOT save the player's state, preventing the overwrite bug.
     */
    public void reapplyGhostMode(Player player) {
        applyGhostEffects(player);
    }

    /**
     * A helper method that applies the visual effects of ghost mode.
     */
    private void applyGhostEffects(Player player) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }
        player.setGameMode(GameMode.SPECTATOR);
        ghostTeam.addEntry(player.getName());
    }

    /**
     * Exits ghost mode, restoring the player's original state.
     */
    public void exitGhostMode(Player player, boolean sendMessages) {
        GhostState originalState = ghostedPlayers.remove(player.getUniqueId());

        if (ghostTeam.hasEntry(player.getName())) {
            ghostTeam.removeEntry(player.getName());
        }

        if (originalState != null) {
            if(configManager.ghostTeleportBack) player.teleport(originalState.location());
            player.setGameMode(originalState.gameMode());
        } else {
            player.setGameMode(Bukkit.getDefaultGameMode());
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }

        if (sendMessages) player.sendMessage(configManager.ghostOffMessage);
    }

    public void updateVanishVisibility(Player subject) {
        boolean isVanished = isVanished(subject);

        if (isGhosted(subject)) {
            for (Player observer : Bukkit.getOnlinePlayers()) {
                observer.showPlayer(this, subject);
            }
            return;
        }

        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.equals(subject)) continue;
            boolean canSee = observer.hasPermission("vanishpp.see");

            if (isVanished && !canSee) {
                observer.hidePlayer(this, subject);
            } else {
                observer.showPlayer(this, subject);
            }
        }
    }

    public boolean isVanished(Player player) { return vanishedPlayers.contains(player.getUniqueId()); }
    public boolean isGhosted(Player player) { return ghostedPlayers.containsKey(player.getUniqueId()); }
    public ConfigManager getConfigManager() { return configManager; }
    public Set<UUID> getRawVanishedPlayers() { return Collections.unmodifiableSet(vanishedPlayers); }
    public Map<UUID, GhostState> getRawGhostStates() { return Collections.unmodifiableMap(ghostedPlayers); }
    public Team getVanishTeam() { return vanishTeam; }
    public Team getGhostTeam() { return ghostTeam; }
}