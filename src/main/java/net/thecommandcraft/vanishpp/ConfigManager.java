package net.thecommandcraft.vanishpp;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ConfigManager {

    private final Vanishpp plugin;
    private FileConfiguration config;
    private File configFile;

    // Messages
    public String vanishMessage, unvanishMessage, noPermissionMessage, playerNotFoundMessage,
            vanishedOtherMessage, unvanishedOtherMessage, silentJoinMessage, silentQuitMessage,
            staffVanishMessage, staffUnvanishMessage;

    // VPerms Messages
    public String vpermsInvalidUsage, vpermsInvalidPermission, vpermsPermSet,
            vpermsPermRemoved, vpermsPermGetHas, vpermsPermGetDoesNotHave, vpermsReload;

    // Formatting
    public String vanishPrefix, vanishedChatFormat, actionBarText;

    // Toggles
    public boolean hideFromServerList, fakeLeaveMessage, fakeJoinMessage, disableBlockTriggering,
            hideDeathMessages, hideAdvancements, actionBarEnabled, staffNotifyEnabled;

    public ConfigManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdir();
        }

        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        this.config = YamlConfiguration.loadConfiguration(configFile);

        config.addDefault("messages.vperms.invalid-usage", "&cUsage: /vperms <player> <permission> <set|remove|get>");
        config.addDefault("messages.vperms.invalid-permission", "&cInvalid permission. Use: vanishpp.vanish, vanishpp.see, or vanishpp.vanish.others");
        config.addDefault("messages.vperms.perm-set", "&aSet permission '%perm%' for %player%.");
        config.addDefault("messages.vperms.perm-removed", "&eRemoved permission '%perm%' from %player%.");
        config.addDefault("messages.vperms.perm-get-has", "&a%player% HAS the permission '%perm%'.");
        config.addDefault("messages.vperms.perm-get-does-not-have", "&c%player% DOES NOT HAVE the permission '%perm%'.");
        config.addDefault("messages.vperms.reload", "&aVanish++ permissions reloaded.");

        config.options().copyDefaults(true);
        plugin.saveDefaultConfig(); // Ensures defaults are written if missing
        try {
            config.save(configFile); // Save any new defaults like vperms messages
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Load all values
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

        vpermsInvalidUsage = translateColors(config.getString("messages.vperms.invalid-usage"));
        vpermsInvalidPermission = translateColors(config.getString("messages.vperms.invalid-permission"));
        vpermsPermSet = translateColors(config.getString("messages.vperms.perm-set"));
        vpermsPermRemoved = translateColors(config.getString("messages.vperms.perm-removed"));
        vpermsPermGetHas = translateColors(config.getString("messages.vperms.perm-get-has"));
        vpermsPermGetDoesNotHave = translateColors(config.getString("messages.vperms.perm-get-does-not-have"));
        vpermsReload = translateColors(config.getString("messages.vperms.reload"));

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

    public synchronized void save() {
        saveVanishedPlayersToMemory(plugin.getRawVanishedPlayers());
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save config.yml!");
            e.printStackTrace();
        }
    }

    public Set<UUID> loadVanishedPlayers() {
        // This needs to happen after config is loaded
        if (config == null) {
            this.config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        }
        return config.getStringList("data.vanished-players").stream()
                .map(uuidString -> {
                    try { return UUID.fromString(uuidString); }
                    catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Could not parse UUID from config: " + uuidString);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void saveVanishedPlayersToMemory(Set<UUID> vanishedPlayers) {
        List<String> uuids = vanishedPlayers.stream().map(UUID::toString).collect(Collectors.toList());
        config.set("data.vanished-players", uuids);
    }

    private String translateColors(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }
}