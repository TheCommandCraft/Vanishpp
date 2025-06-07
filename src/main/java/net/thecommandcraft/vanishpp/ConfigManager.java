package net.thecommandcraft.vanishpp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public class ConfigManager {

    private final Vanishpp plugin;
    private FileConfiguration config;

    // Messages
    public String vanishMessage, unvanishMessage, noPermissionMessage, playerNotFoundMessage,
            vanishedOtherMessage, unvanishedOtherMessage, silentJoinMessage, silentQuitMessage,
            staffVanishMessage, staffUnvanishMessage, ghostOnMessage, ghostOffMessage,
            ghostFailGamemodeMessage, ghostFailVanishMessage, vanishFailGhostedMessage;

    // Formatting
    public String vanishPrefix, vanishedChatFormat, actionBarText;

    // Toggles
    public boolean hideFromServerList, fakeLeaveMessage, fakeJoinMessage, disableBlockTriggering,
            hideDeathMessages, hideAdvancements, actionBarEnabled, staffNotifyEnabled, ghostTeleportBack;

    public ConfigManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();

        config.options().copyDefaults(true);
        plugin.saveConfig();

        // --- Load Values ---
        vanishMessage = translateColors(config.getString("messages.vanish"));
        unvanishMessage = translateColors(config.getString("messages.unvanish"));
        noPermissionMessage = translateColors(config.getString("messages.no-permission"));
        playerNotFoundMessage = translateColors(config.getString("messages.player-not-found"));
        vanishedOtherMessage = translateColors(config.getString("messages.vanished-other"));
        unvanishedOtherMessage = translateColors(config.getString("messages.unvanished-other"));
        ghostOnMessage = translateColors(config.getString("messages.ghost-on"));
        ghostOffMessage = translateColors(config.getString("messages.ghost-off"));
        ghostFailGamemodeMessage = translateColors(config.getString("messages.ghost-fail-gamemode"));
        ghostFailVanishMessage = translateColors(config.getString("messages.ghost-fail-vanished"));
        vanishFailGhostedMessage = translateColors(config.getString("messages.vanish-fail-ghosted"));
        silentJoinMessage = translateColors(config.getString("messages.silent-join"));
        silentQuitMessage = translateColors(config.getString("messages.silent-quit"));
        staffVanishMessage = translateColors(config.getString("messages.staff-notify.on-vanish"));
        staffUnvanishMessage = translateColors(config.getString("messages.staff-notify.on-unvanish"));
        vanishPrefix = translateColors(config.getString("vanish-appearance.prefix"));
        actionBarText = translateColors(config.getString("vanish-appearance.action-bar.text"));
        vanishedChatFormat = translateColors(config.getString("chat-format.vanished-player-format"));
        staffNotifyEnabled = config.getBoolean("messages.staff-notify.enabled");
        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled");
        ghostTeleportBack = config.getBoolean("ghost-mode.teleport-back-on-exit");
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
        fakeLeaveMessage = config.getBoolean("vanish-effects.fake-leave-message");
        fakeJoinMessage = config.getBoolean("vanish-effects.fake-join-message");
        disableBlockTriggering = config.getBoolean("vanish-effects.disable-block-triggering");
        hideDeathMessages = config.getBoolean("hide-announcements.death-messages");
        hideAdvancements = config.getBoolean("hide-announcements.advancements");
    }

    public void save() {
        saveVanishedPlayers(plugin.getRawVanishedPlayers());
        saveGhostStates(plugin.getRawGhostStates());
        plugin.saveConfig();
    }

    public Set<UUID> loadVanishedPlayers() {
        return config.getStringList("data.vanished-players").stream()
                .map(uuidString -> {
                    try { return UUID.fromString(uuidString); }
                    catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Could not parse UUID from vanished-players: " + uuidString);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void saveVanishedPlayers(Set<UUID> vanishedPlayers) {
        List<String> uuids = vanishedPlayers.stream().map(UUID::toString).collect(Collectors.toList());
        config.set("data.vanished-players", uuids);
    }

    public Map<UUID, Vanishpp.GhostState> loadGhostStates() {
        Map<UUID, Vanishpp.GhostState> states = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("data.ghost-states");
        if (section == null) return states;

        for (String uuidString : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                ConfigurationSection stateSection = section.getConfigurationSection(uuidString);
                if (stateSection == null) continue;

                GameMode gameMode = GameMode.valueOf(stateSection.getString("gamemode", "SURVIVAL"));
                World world = Bukkit.getWorld(stateSection.getString("location.world", "world"));
                if (world == null) {
                    plugin.getLogger().warning("Could not find world for ghosted player's location: " + uuidString + ". Using default world spawn.");
                    world = Bukkit.getWorlds().get(0);
                }
                Location location = new Location(
                        world,
                        stateSection.getDouble("location.x"),
                        stateSection.getDouble("location.y"),
                        stateSection.getDouble("location.z"),
                        (float) stateSection.getDouble("location.yaw"),
                        (float) stateSection.getDouble("location.pitch")
                );
                states.put(uuid, new Vanishpp.GhostState(gameMode, location));
            } catch (Exception e) {
                plugin.getLogger().warning("Could not parse ghost state for " + uuidString + ": " + e.getMessage());
            }
        }
        return states;
    }

    private void saveGhostStates(Map<UUID, Vanishpp.GhostState> ghostStates) {
        // Create a fresh section to avoid leftover data
        config.createSection("data.ghost-states");
        ConfigurationSection section = config.getConfigurationSection("data.ghost-states");

        for (Map.Entry<UUID, Vanishpp.GhostState> entry : ghostStates.entrySet()) {
            String uuid = entry.getKey().toString();
            Vanishpp.GhostState state = entry.getValue();
            String path = uuid; // Relative to the new section
            section.set(path + ".gamemode", state.gameMode().name());
            section.set(path + ".location.world", state.location().getWorld().getName());
            section.set(path + ".location.x", state.location().getX());
            section.set(path + ".location.y", state.location().getY());
            section.set(path + ".location.z", state.location().getZ());
            section.set(path + ".location.yaw", state.location().getYaw());
            section.set(path + ".location.pitch", state.location().getPitch());
        }
    }

    private String translateColors(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }
}