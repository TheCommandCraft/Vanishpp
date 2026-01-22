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
    public String vanishTabPrefix;
    public String vanishNametagPrefix;
    public String actionBarText;
    public String vanishedPlayerFormat;
    public boolean actionBarEnabled;
    public boolean adjustServerListCount;

    // Core Effects Logic
    public boolean hideFromServerList;
    public boolean hideRealQuit;
    public boolean hideRealJoin;
    public boolean broadcastFakeQuit;
    public boolean broadcastFakeJoin;
    public boolean disableBlockTriggering;

    public boolean hideDeathMessages, hideAdvancements;

    public boolean enableNightVision, enableFly, disableMobTarget, disableHunger, silentChests, ignoreProjectiles;
    public boolean preventRaid, preventSculk, preventTrample, hideTabComplete, preventSleeping, preventEntityInteract;
    public boolean preventAccidentalChat;

    public boolean voiceChatEnabled, voiceChatIsolate;
    public boolean layeredPermsEnabled;
    public int defaultVanishLevel, defaultSeeLevel, maxLevel;

    // Update Checker
    public boolean updateCheckerEnabled;
    public String updateCheckerId;
    public String updateCheckerMode;
    public List<String> updateCheckerList;

    public Map<String, Boolean> defaultRules = new HashMap<>();

    public ConfigManager(Vanishpp plugin) { this.plugin = plugin; }

    public FileConfiguration getConfig() { return config; }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // Load Messages
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
        chatSentMessage = format(config.getString("messages.chat-sent", "&aMessage sent."));
        noChatPendingMessage = format(config.getString("messages.no-chat-pending", "&cNo pending message."));

        vpermsReload = format(config.getString("messages.vperms.reload"));
        vpermsInvalidUsage = format(config.getString("messages.vperms.invalid-usage"));
        vpermsInvalidPermission = format(config.getString("messages.vperms.invalid-permission"));
        vpermsPermSet = format(config.getString("messages.vperms.perm-set"));
        vpermsPermRemoved = format(config.getString("messages.vperms.perm-removed"));
        vpermsPermGetHas = format(config.getString("messages.vperms.perm-get-has"));
        vpermsPermGetDoesNotHave = format(config.getString("messages.vperms.perm-get-does-not-have"));

        silentJoinMessage = format(config.getString("messages.silent-join"));
        silentQuitMessage = format(config.getString("messages.silent-quit"));
        staffNotifyEnabled = config.getBoolean("messages.staff-notify.enabled");
        staffVanishMessage = format(config.getString("messages.staff-notify.on-vanish"));
        staffUnvanishMessage = format(config.getString("messages.staff-notify.on-unvanish"));

        // Appearance
        vanishTabPrefix = format(config.getString("vanish-appearance.tab-prefix", "&7[VANISHED] "));
        vanishNametagPrefix = format(config.getString("vanish-appearance.nametag-prefix", "&7[VANISHED] "));

        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled");
        actionBarText = format(config.getString("vanish-appearance.action-bar.text"));
        adjustServerListCount = config.getBoolean("vanish-appearance.adjust-server-list-count", true);
        vanishedPlayerFormat = format(config.getString("chat-format.vanished-player-format"));

        // Effects
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
        hideRealQuit = config.getBoolean("vanish-effects.hide-real-quit-messages", true);
        hideRealJoin = config.getBoolean("vanish-effects.hide-real-join-messages", true);
        broadcastFakeQuit = config.getBoolean("vanish-effects.broadcast-fake-quit", false);
        broadcastFakeJoin = config.getBoolean("vanish-effects.broadcast-fake-join", false);
        disableBlockTriggering = config.getBoolean("vanish-effects.disable-block-triggering");

        hideDeathMessages = config.getBoolean("hide-announcements.death-messages");
        hideAdvancements = config.getBoolean("hide-announcements.advancements");

        // Features
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

        // Update Checker
        updateCheckerEnabled = config.getBoolean("update-checker.enabled", true);
        updateCheckerId = config.getString("update-checker.modrinth-id", "vanishpp");
        updateCheckerMode = config.getString("update-checker.notify-mode", "PERMISSION");
        updateCheckerList = config.getStringList("update-checker.notify-list");

        // Default Rules Section
        ConfigurationSection rulesSection = config.getConfigurationSection("default-rules");
        defaultRules.clear();
        if (rulesSection != null) {
            for (String key : rulesSection.getKeys(false)) {
                defaultRules.put(key, rulesSection.getBoolean(key));
            }
        }
    }

    public void save() { plugin.saveConfig(); }
    private String format(String m) { return m == null ? "" : m.replace("&", "§"); }
}