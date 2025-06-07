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

    public String vanishMessage;
    public String unvanishMessage;
    public String noPermissionMessage;
    public String silentJoinMessage;
    public String silentQuitMessage;

    public ConfigManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();

        // Message defaults
        config.addDefault("messages.vanish", "&6You are now vanished.");
        config.addDefault("messages.unvanish", "&6You are no longer vanished.");
        config.addDefault("messages.no-permission", "&cYou do not have permission to use this command.");
        config.addDefault("messages.silent-join", "&8[&7-&8] &7%player% has silently joined.");
        config.addDefault("messages.silent-quit", "&8[&7-&8] &7%player% has silently left.");

        // Add a default for our new vanished players list (it will be an empty list by default)
        config.addDefault("data.vanished-players", new HashSet<String>());

        config.options().copyDefaults(true);
        plugin.saveConfig();

        // Load messages
        vanishMessage = translateColors(config.getString("messages.vanish"));
        unvanishMessage = translateColors(config.getString("messages.unvanish"));
        noPermissionMessage = translateColors(config.getString("messages.no-permission"));
        silentJoinMessage = translateColors(config.getString("messages.silent-join"));
        silentQuitMessage = translateColors(config.getString("messages.silent-quit"));
    }

    /**
     * The main save method. This will be called on plugin disable.
     * It saves all necessary data, like the vanished players list.
     */
    public void save() {
        // Get the current list of vanished players from the main plugin class and save it.
        saveVanishedPlayers(plugin.getVanishedPlayers());
    }

    /**
     * Loads the list of vanished player UUIDs from the config file.
     * @return A Set of UUIDs for players who should be vanished.
     */
    public Set<UUID> loadVanishedPlayers() {
        // Get the list of strings from the config
        List<String> vanishedUUIDsAsStrings = config.getStringList("data.vanished-players");
        Set<UUID> vanishedUUIDs = new HashSet<>();
        // Convert each string back into a UUID object
        for (String uuidString : vanishedUUIDsAsStrings) {
            try {
                vanishedUUIDs.add(UUID.fromString(uuidString));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Could not parse UUID from config: " + uuidString);
            }
        }
        return vanishedUUIDs;
    }

    /**
     * Saves the provided set of vanished player UUIDs to the config file.
     * @param vanishedPlayers The set of UUIDs to save.
     */
    public void saveVanishedPlayers(Set<UUID> vanishedPlayers) {
        // Convert the Set of UUIDs into a List of Strings
        List<String> vanishedUUIDsAsStrings = vanishedPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());

        // Set the value in the config and save the file
        config.set("data.vanished-players", vanishedUUIDsAsStrings);
        plugin.saveConfig();
    }

    private String translateColors(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}