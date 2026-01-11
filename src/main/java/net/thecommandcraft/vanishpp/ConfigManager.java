package net.thecommandcraft.vanishpp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

public class ConfigManager {
    private final Vanishpp plugin;
    private FileConfiguration config;

    // Messages
    public String vanishMessage, unvanishMessage, noPermissionMessage, playerNotFoundMessage;
    public String vanishedOtherMessage, unvanishedOtherMessage, silentChestBlocked;
    public String pickupEnabledMessage, pickupDisabledMessage;
    public String chatLockedMessage, chatSentMessage, noChatPendingMessage;
    public String vpermsInvalidUsage, vpermsInvalidPermission, vpermsPermSet, vpermsPermRemoved, vpermsPermGetHas, vpermsPermGetDoesNotHave, vpermsReload;
    public String silentJoinMessage, silentQuitMessage;
    public boolean staffNotifyEnabled;
    public String staffVanishMessage, staffUnvanishMessage;

    // Appearance
    public String vanishTabPrefix;      // For Tablist
    public String vanishNametagPrefix;  // For Scoreboard Team (Nameplate)

    public String actionBarText;
    public String vanishedPlayerFormat;
    public boolean actionBarEnabled, hideFromServerList, fakeLeaveMessage, fakeJoinMessage, disableBlockTriggering;

    // Broadcasts
    public boolean broadcastFakeQuit, broadcastFakeJoin;

    public boolean hideDeathMessages, hideAdvancements;

    public boolean enableNightVision, enableFly, disableMobTarget, disableHunger, silentChests, ignoreProjectiles;
    public boolean preventRaid, preventSculk, preventTrample, hideTabComplete, preventSleeping, preventEntityInteract;
    public boolean preventAccidentalChat;
    public boolean adjustServerListCount;

    public boolean voiceChatEnabled, voiceChatIsolate;
    public boolean layeredPermsEnabled;
    public int defaultVanishLevel, defaultSeeLevel, maxLevel;

    public Map<String, Boolean> defaultRules = new HashMap<>();

    public ConfigManager(Vanishpp plugin) { this.plugin = plugin; }

    public FileConfiguration getConfig() { return config; }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // Messages
        vanishMessage = format(config.getString("messages.vanish"));
        unvanishMessage = format(config.getString("messages.unvanish"));
        noPermissionMessage = format(config.getString("messages.no-permission"));
        playerNotFoundMessage = format(config.getString("messages.player-not-found"));
        vanishedOtherMessage = format(config.getString("messages.vanished-other"));
        unvanishedOtherMessage = format(config.getString("messages.unvanished-other"));
        silentChestBlocked = format(config.getString("messages.silent-chest-blocked"));
        pickupEnabledMessage = format(config.getString("messages.pickup-enabled", "&aPickup Enabled"));
        pickupDisabledMessage = format(config.getString("messages.pickup-disabled", "&cPickup Disabled"));

        chatLockedMessage = format(config.getString("messages.chat-locked", "&cConfirm chat with /vchat confirm"));
        chatSentMessage = format(config.getString("messages.chat-sent", "&aSent."));
        noChatPendingMessage = format(config.getString("messages.no-chat-pending", "&cNo pending message."));

        vpermsInvalidUsage = format(config.getString("messages.vperms.invalid-usage"));
        vpermsInvalidPermission = format(config.getString("messages.vperms.invalid-permission"));
        vpermsPermSet = format(config.getString("messages.vperms.perm-set"));
        vpermsPermRemoved = format(config.getString("messages.vperms.perm-removed"));
        vpermsPermGetHas = format(config.getString("messages.vperms.perm-get-has"));
        vpermsPermGetDoesNotHave = format(config.getString("messages.vperms.perm-get-does-not-have"));
        vpermsReload = format(config.getString("messages.vperms.reload"));

        silentJoinMessage = format(config.getString("messages.silent-join"));
        silentQuitMessage = format(config.getString("messages.silent-quit"));
        staffNotifyEnabled = config.getBoolean("messages.staff-notify.enabled");
        staffVanishMessage = format(config.getString("messages.staff-notify.on-vanish"));
        staffUnvanishMessage = format(config.getString("messages.staff-notify.on-unvanish"));

        // Appearance Settings
        // Legacy check
        if (config.contains("vanish-appearance.prefix")) {
            String legacy = format(config.getString("vanish-appearance.prefix"));
            vanishTabPrefix = legacy;
            // Legacy default to empty for nametag if splitting
            vanishNametagPrefix = "";
        } else {
            vanishTabPrefix = format(config.getString("vanish-appearance.tab-prefix", "&7[VANISHED] "));
            vanishNametagPrefix = format(config.getString("vanish-appearance.nametag-prefix", "")); // Default empty!
        }

        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled");
        actionBarText = format(config.getString("vanish-appearance.action-bar.text"));
        adjustServerListCount = config.getBoolean("vanish-appearance.adjust-server-list-count", true);
        vanishedPlayerFormat = format(config.getString("chat-format.vanished-player-format"));

        // Effects
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
        fakeLeaveMessage = config.getBoolean("vanish-effects.fake-leave-message");
        fakeJoinMessage = config.getBoolean("vanish-effects.fake-join-message");
        broadcastFakeQuit = config.getBoolean("vanish-effects.broadcast-fake-quit", true);
        broadcastFakeJoin = config.getBoolean("vanish-effects.broadcast-fake-join", true);
        disableBlockTriggering = config.getBoolean("vanish-effects.disable-block-triggering");

        hideDeathMessages = config.getBoolean("hide-announcements.death-messages");
        hideAdvancements = config.getBoolean("hide-announcements.advancements");

        enableNightVision = config.getBoolean("invisibility-features.night-vision");
        enableFly = config.getBoolean("invisibility-features.allow-flight");
        disableMobTarget = config.getBoolean("invisibility-features.disable-mob-targeting");
        disableHunger = config.getBoolean("invisibility-features.disable-hunger");
        silentChests = config.getBoolean("invisibility-features.silent-chests");
        ignoreProjectiles = config.getBoolean("invisibility-features.ignore-projectiles");
        preventRaid = config.getBoolean("invisibility-features.prevent-raid-trigger");
        preventSculk = config.getBoolean("invisibility-features.prevent-sculk-sensors");
        preventTrample = config.getBoolean("invisibility-features.prevent-trample");
        hideTabComplete = config.getBoolean("invisibility-features.hide-from-tab-complete");
        preventSleeping = config.getBoolean("invisibility-features.prevent-sleeping");
        preventEntityInteract = config.getBoolean("invisibility-features.prevent-entity-interact", true);
        preventAccidentalChat = config.getBoolean("invisibility-features.prevent-accidental-chat", true);

        voiceChatEnabled = config.getBoolean("hooks.simple-voice-chat.enabled");
        voiceChatIsolate = config.getBoolean("hooks.simple-voice-chat.isolate-vanished-players");

        layeredPermsEnabled = config.getBoolean("permissions.layered-permissions-enabled");
        defaultVanishLevel = config.getInt("permissions.default-vanish-level", 1);
        defaultSeeLevel = config.getInt("permissions.default-see-level", 1);
        maxLevel = config.getInt("permissions.max-level", 100);

        ConfigurationSection rulesSection = config.getConfigurationSection("default-rules");
        defaultRules.clear();
        if (rulesSection != null) {
            for (String key : rulesSection.getKeys(false)) {
                defaultRules.put(key, rulesSection.getBoolean(key));
            }
        }
    }

    public void save() {
        List<String> uuidStrings = plugin.getRawVanishedPlayers().stream().map(UUID::toString).toList();
        config.set("data.vanished-players", uuidStrings);
        List<String> ignoredStrings = plugin.getIgnoredWarningPlayers().stream().map(UUID::toString).toList();
        config.set("data.ignored-warnings", ignoredStrings);
        plugin.saveConfig();
    }

    public Set<UUID> loadVanishedPlayers() { return loadUuidSet("data.vanished-players"); }
    public Set<UUID> loadIgnoredWarningPlayers() { return loadUuidSet("data.ignored-warnings"); }
    public Set<UUID> loadPickupEnabledPlayers() { return new HashSet<>(); }

    private Set<UUID> loadUuidSet(String path) {
        List<String> list = config.getStringList(path);
        Set<UUID> set = new HashSet<>();
        for (String s : list) {
            try { set.add(UUID.fromString(s)); } catch (Exception ignored) {}
        }
        return set;
    }
    private String format(String m) { return m == null ? "" : m.replace("&", "§"); }
    public String format(String m, String def) { return m == null ? def : m.replace("&", "§"); }
}