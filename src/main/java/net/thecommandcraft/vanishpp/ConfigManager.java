package net.thecommandcraft.vanishpp;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public class ConfigManager {

    private final Vanishpp plugin;
    private FileConfiguration config;

    public String vanishMessage, unvanishMessage, noPermissionMessage, playerNotFoundMessage,
            vanishedOtherMessage, unvanishedOtherMessage, silentJoinMessage, silentQuitMessage,
            staffVanishMessage, staffUnvanishMessage;

    public String vanishPrefix, vanishedChatFormat, actionBarText;

    public boolean hideFromServerList, fakeLeaveMessage, fakeJoinMessage, disableBlockTriggering,
            hideDeathMessages, hideAdvancements, actionBarEnabled, staffNotifyEnabled;

    public ConfigManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
        config.options().copyDefaults(true);
        plugin.saveConfig();

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
        plugin.saveConfig();
    }

    public Set<UUID> loadVanishedPlayers() {
        return config.getStringList("data.vanished-players").stream()
                .map(uuidString -> {
                    try { return UUID.fromString(uuidString); }
                    catch (IllegalArgumentException e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void saveVanishedPlayers(Set<UUID> vanishedPlayers) {
        List<String> uuids = vanishedPlayers.stream().map(UUID::toString).collect(Collectors.toList());
        config.set("data.vanished-players", uuids);
    }

    private String translateColors(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }
}