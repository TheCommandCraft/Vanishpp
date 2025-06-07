package net.thecommandcraft.vanishpp;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ConfigManager {

    private final Vanishpp plugin;
    private FileConfiguration config;

    // Message and format variables
    public String vanishMessage;
    public String unvanishMessage;
    public String noPermissionMessage;
    public String silentJoinMessage;
    public String silentQuitMessage;
    public String vanishPrefix;
    public String vanishedChatFormat;
    public boolean hideFromServerList;

    public ConfigManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();

        // --- Set Defaults ---
        config.addDefault("messages.vanish", "&6You are now vanished.");
        config.addDefault("messages.unvanish", "&6You are no longer vanished.");
        config.addDefault("messages.no-permission", "&cYou do not have permission to use this command.");
        config.addDefault("messages.silent-join", "&8[&7+&8] &7%player% has silently joined.");
        config.addDefault("messages.silent-quit", "&8[&7-&8] &7%player% has silently left.");
        config.addDefault("vanish-appearance.prefix", "&7[VANISHED] ");
        config.addDefault("chat-format.vanished-player-format", "%prefix%&7%player%: %message%");
        config.addDefault("vanish-effects.hide-from-server-list", true);
        config.addDefault("data.vanished-players", new HashSet<String>());

        config.options().copyDefaults(true);
        plugin.saveConfig();

        // --- Load Values ---
        vanishMessage = translateColors(config.getString("messages.vanish"));
        unvanishMessage = translateColors(config.getString("messages.unvanish"));
        noPermissionMessage = translateColors(config.getString("messages.no-permission"));
        silentJoinMessage = translateColors(config.getString("messages.silent-join"));
        silentQuitMessage = translateColors(config.getString("messages.silent-quit"));
        vanishPrefix = translateColors(config.getString("vanish-appearance.prefix"));
        vanishedChatFormat = translateColors(config.getString("chat-format.vanished-player-format"));
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
    }

    public void save() {
        saveVanishedPlayers(plugin.getRawVanishedPlayers());
    }

    public Set<UUID> loadVanishedPlayers() {
        List<String> vanishedUUIDsAsStrings = config.getStringList("data.vanished-players");
        Set<UUID> vanishedUUIDs = new HashSet<>();
        for (String uuidString : vanishedUUIDsAsStrings) {
            try {
                vanishedUUIDs.add(UUID.fromString(uuidString));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Could not parse UUID from config: " + uuidString);
            }
        }
        return vanishedUUIDs;
    }

    private void saveVanishedPlayers(Set<UUID> vanishedPlayers) {
        List<String> vanishedUUIDsAsStrings = vanishedPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());

        config.set("data.vanished-players", vanishedUUIDsAsStrings);
        plugin.saveConfig();
    }

    private String translateColors(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}