package net.thecommandcraft.vanishpp;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final Vanishpp plugin;
    private FileConfiguration config;

    // --- Configuration Variables ---
    // These are public so other classes can easily access them.
    // They are initialized in the load() method.
    public String vanishMessage;
    public String unvanishMessage;
    public String noPermissionMessage;

    public ConfigManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        // This ensures the config.yml is created from the template in your JAR
        plugin.saveDefaultConfig();

        // Get the config object
        config = plugin.getConfig();

        // It's good practice to set defaults in code as a fallback
        config.addDefault("messages.vanish", "&6You are now vanished.");
        config.addDefault("messages.unvanish", "&6You are no longer vanished.");
        config.addDefault("messages.no-permission", "&cYou do not have permission to use this command.");

        // We use copyDefaults(true) to write the defaults to the config.yml
        // if they aren't already there.
        config.options().copyDefaults(true);
        plugin.saveConfig();

        // Now, load the values into our variables, translating color codes.
        vanishMessage = translateColors(config.getString("messages.vanish"));
        unvanishMessage = translateColors(config.getString("messages.unvanish"));
        noPermissionMessage = translateColors(config.getString("messages.no-permission"));
    }

    public void save() {
        // We don't need to save anything back for this example,
        // but this is where the logic would go.
        // For example:
        // config.set("messages.vanish", reverseTranslateColors(vanishMessage));
        // plugin.saveConfig();
    }

    private String translateColors(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String reverseTranslateColors(String text) {
        if (text == null) return "";
        // ChatColor.COLOR_CHAR is the '§' symbol
        return text.replace(ChatColor.COLOR_CHAR, '&');
    }
}