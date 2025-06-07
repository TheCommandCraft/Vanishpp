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
    public String playerNotFoundMessage;
    public String vanishedOtherMessage;
    public String unvanishedOtherMessage;
    public String silentJoinMessage;
    public String silentQuitMessage;
    public String vanishPrefix;
    public String vanishedChatFormat;
    public String actionBarText;
    public String staffVanishMessage;
    public String staffUnvanishMessage;

    // Feature toggles
    public boolean hideFromServerList;
    public boolean fakeLeaveMessage;
    public boolean fakeJoinMessage;
    public boolean disableBlockTriggering;
    public boolean hideDeathMessages;
    public boolean hideAdvancements;
    public boolean actionBarEnabled;
    public boolean staffNotifyEnabled;

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
        config.addDefault("messages.player-not-found", "&cPlayer not found.");
        config.addDefault("messages.vanished-other", "&6You have vanished %player%.");
        config.addDefault("messages.unvanished-other", "&6You have unvanished %player%.");
        config.addDefault("messages.silent-join", "&8[&7+&8] &7%player% has silently joined.");
        config.addDefault("messages.silent-quit", "&8[&7-&8] &7%player% has silently left.");
        config.addDefault("messages.staff-notify.enabled", true);
        config.addDefault("messages.staff-notify.on-vanish", "&e[Staff] %player% has vanished (by %staff%).");
        config.addDefault("messages.staff-notify.on-unvanish", "&e[Staff] %player% has unvanished (by %staff%).");

        config.addDefault("vanish-appearance.prefix", "&7[VANISHED] ");
        config.addDefault("vanish-appearance.action-bar.enabled", true);
        config.addDefault("vanish-appearance.action-bar.text", "&bYou are currently VANISHED");

        config.addDefault("chat-format.vanished-player-format", "%prefix%&7%player%: %message%");

        config.addDefault("vanish-effects.hide-from-server-list", true);
        config.addDefault("vanish-effects.fake-leave-message", true);
        config.addDefault("vanish-effects.fake-join-message", true);
        config.addDefault("vanish-effects.disable-block-triggering", true);

        config.addDefault("hide-announcements.death-messages", true);
        config.addDefault("hide-announcements.advancements", true);

        config.addDefault("data.vanished-players", new HashSet<String>());

        config.options().copyDefaults(true);
        plugin.saveConfig();

        // --- Load Values ---
        vanishMessage = translateColors(config.getString("messages.vanish"));
        unvanishMessage = translateColors(config.getString("messages.unvanish"));
        noPermissionMessage = translateColors(config.getString("messages.no-permission"));
        playerNotFoundMessage = translateColors(config.getString("messages.player-not-found"));
        vanishedOtherMessage = translateColors(config.getString("messages.vanished-other"));
        unvanishedOtherMessage = translateColors(config.getString("messages.unvanished-other"));
        silentJoinMessage = translateColors(config.getString("messages.silent-join"));
        silentQuitMessage = translateColors(config.getString("messages.silent-quit"));
        staffVanishMessage = translateColors(config.getString("messages.staff-notify.on-vanish"));
        staffUnvanishMessage = translateColors(config.getString("messages.staff-notify.on-unvanish"));

        vanishPrefix = translateColors(config.getString("vanish-appearance.prefix"));
        actionBarText = translateColors(config.getString("vanish-appearance.action-bar.text"));

        vanishedChatFormat = translateColors(config.getString("chat-format.vanished-player-format"));

        staffNotifyEnabled = config.getBoolean("messages.staff-notify.enabled");
        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled");

        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
        fakeLeaveMessage = config.getBoolean("vanish-effects.fake-leave-message");
        fakeJoinMessage = config.getBoolean("vanish-effects.fake-join-message");
        disableBlockTriggering = config.getBoolean("vanish-effects.disable-block-triggering");

        hideDeathMessages = config.getBoolean("hide-announcements.death-messages");
        hideAdvancements = config.getBoolean("hide-announcements.advancements");
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