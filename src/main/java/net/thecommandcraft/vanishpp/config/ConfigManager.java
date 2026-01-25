package net.thecommandcraft.vanishpp.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

public class ConfigManager {
    private final Vanishpp plugin;
    private FileConfiguration config;
    private final int LATEST_CONFIG_VERSION = 3;

    private boolean migratedThisBoot = false;
    private final List<String> migrationNotes = new ArrayList<>();

    // Messages
    public String vanishMessage, unvanishMessage, noPermissionMessage, playerNotFoundMessage;
    public String vanishedOtherMessage, unvanishedOtherMessage, silentChestBlocked;
    public String pickupEnabledMessage, pickupDisabledMessage, chatLockedMessage, chatSentMessage, noChatPendingMessage;
    public String vpermsReload, vpermsInvalidUsage, vpermsInvalidPermission, vpermsPermSet, vpermsPermRemoved, vpermsPermGetHas, vpermsPermGetDoesNotHave;
    public String silentJoinMessage, silentQuitMessage, staffVanishMessage, staffUnvanishMessage;

    // Appearance
    public String vanishTabPrefix, vanishNametagPrefix, actionBarText, vanishedPlayerFormat;
    public boolean actionBarEnabled, adjustServerListCount, hideFromServerList;

    // Effects
    public boolean hideRealQuit, hideRealJoin, broadcastFakeQuit, broadcastFakeJoin, disableBlockTriggering;
    public boolean hideDeathMessages, hideAdvancements;

    // Invisibility Features
    public boolean enableNightVision, enableFly, disableMobTarget, disableHunger, silentChests, ignoreProjectiles;
    public boolean preventRaid, preventSculk, preventTrample, hideTabComplete, preventSleeping, preventEntityInteract;
    public boolean preventAccidentalChat, godMode, preventPotions;

    // Hooks & System
    public boolean voiceChatEnabled, voiceChatIsolate, layeredPermsEnabled, updateCheckerEnabled;
    public boolean staffNotifyEnabled; // FIX: Added missing field
    public int defaultVanishLevel, defaultSeeLevel, maxLevel;
    public String updateCheckerMode, updateCheckerId;
    public List<String> updateCheckerList;
    public Map<String, Boolean> defaultRules = new HashMap<>();

    public ConfigManager(Vanishpp plugin) { this.plugin = plugin; }
    public FileConfiguration getConfig() { return config; }

    public void load() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) plugin.saveDefaultConfig();

        YamlConfiguration initialLoad = YamlConfiguration.loadConfiguration(configFile);
        int currentVersion = initialLoad.getInt("config-version", 1);

        if (currentVersion < LATEST_CONFIG_VERSION) {
            new MigrationManager(plugin, this).runMigration(configFile, currentVersion, LATEST_CONFIG_VERSION);
            this.migratedThisBoot = true;
        }

        this.config = YamlConfiguration.loadConfiguration(configFile);
        loadValues();
    }

    public void setAndSave(String path, Object value) {
        config.set(path, value);
        try {
            config.save(new File(plugin.getDataFolder(), "config.yml"));
            loadValues();
        } catch (Exception e) {
            plugin.getLogger().severe("Could not save config after live edit!");
        }
    }

    public void logMigrationChange(String note) { this.migrationNotes.add(note); }

    public void sendMigrationReport(Player player) {
        if (!migratedThisBoot) return;
        List<String> hidden = plugin.getDataManager().getConfig().getStringList("acknowledged-notifications");
        if (hidden.contains(player.getUniqueId().toString() + "_v" + LATEST_CONFIG_VERSION)) return;

        player.sendMessage(Component.text(" "));
        player.sendMessage(Component.text("⚠ Vanish++ Config Migrated", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Structure updated to v" + LATEST_CONFIG_VERSION + ". Custom settings preserved.", NamedTextColor.YELLOW));
        for (String note : migrationNotes) {
            player.sendMessage(Component.text(" • ", NamedTextColor.GOLD).append(Component.text(note, NamedTextColor.WHITE)));
        }
        player.sendMessage(Component.text("[CLICK TO HIDE PERMANENTLY]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vack migration"))
                .hoverEvent(HoverEvent.showText(Component.text("Stop seeing this message on join", NamedTextColor.GRAY))));
        player.sendMessage(Component.text(" "));
    }

    private void loadValues() {
        vanishMessage = format(config.getString("messages.vanish"));
        unvanishMessage = format(config.getString("messages.unvanish"));
        noPermissionMessage = format(config.getString("messages.no-permission"));
        playerNotFoundMessage = format(config.getString("messages.player-not-found"));
        vanishedOtherMessage = format(config.getString("messages.vanished-other"));
        unvanishedOtherMessage = format(config.getString("messages.unvanished-other"));
        silentChestBlocked = format(config.getString("messages.silent-chest-blocked"));
        pickupEnabledMessage = format(config.getString("messages.pickup-enabled"));
        pickupDisabledMessage = format(config.getString("messages.pickup-disabled"));
        chatLockedMessage = format(config.getString("messages.chat-locked"));
        chatSentMessage = format(config.getString("messages.chat-sent"));
        noChatPendingMessage = format(config.getString("messages.no-chat-pending"));
        vpermsReload = format(config.getString("messages.vperms.reload"));
        vpermsInvalidUsage = format(config.getString("messages.vperms.invalid-usage"));
        vpermsInvalidPermission = format(config.getString("messages.vperms.invalid-permission"));
        vpermsPermSet = format(config.getString("messages.vperms.perm-set"));
        vpermsPermRemoved = format(config.getString("messages.vperms.perm-removed"));
        vpermsPermGetHas = format(config.getString("messages.vperms.perm-get-has"));
        vpermsPermGetDoesNotHave = format(config.getString("messages.vperms.perm-get-does-not-have"));
        silentJoinMessage = format(config.getString("messages.silent-join"));
        silentQuitMessage = format(config.getString("messages.silent-quit"));

        staffNotifyEnabled = config.getBoolean("messages.staff-notify.enabled", true); // FIX
        staffVanishMessage = format(config.getString("messages.staff-notify.on-vanish"));
        staffUnvanishMessage = format(config.getString("messages.staff-notify.on-unvanish"));

        vanishTabPrefix = format(config.getString("vanish-appearance.tab-prefix"));
        vanishNametagPrefix = format(config.getString("vanish-appearance.nametag-prefix"));
        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled");
        actionBarText = format(config.getString("vanish-appearance.action-bar.text"));
        adjustServerListCount = config.getBoolean("vanish-appearance.adjust-server-list-count");
        vanishedPlayerFormat = format(config.getString("chat-format.vanished-player-format"));
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
        hideRealQuit = config.getBoolean("vanish-effects.hide-real-quit-messages");
        hideRealJoin = config.getBoolean("vanish-effects.hide-real-join-messages");
        broadcastFakeQuit = config.getBoolean("vanish-effects.broadcast-fake-quit");
        broadcastFakeJoin = config.getBoolean("vanish-effects.broadcast-fake-join");
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
        preventEntityInteract = config.getBoolean("invisibility-features.prevent-entity-interact");
        preventAccidentalChat = config.getBoolean("invisibility-features.prevent-accidental-chat");
        godMode = config.getBoolean("invisibility-features.god-mode", true);
        preventPotions = config.getBoolean("invisibility-features.prevent-potion-effects", true);
        voiceChatEnabled = config.getBoolean("hooks.simple-voice-chat.enabled");
        voiceChatIsolate = config.getBoolean("hooks.simple-voice-chat.isolate-vanished-players");
        layeredPermsEnabled = config.getBoolean("permissions.layered-permissions-enabled");
        defaultVanishLevel = config.getInt("permissions.default-vanish-level");
        defaultSeeLevel = config.getInt("permissions.default-see-level");
        maxLevel = config.getInt("permissions.max-level");
        updateCheckerEnabled = config.getBoolean("update-checker.enabled");
        updateCheckerId = config.getString("update-checker.modrinth-id", "vanishpp");
        updateCheckerMode = config.getString("update-checker.notify-mode");
        updateCheckerList = config.getStringList("update-checker.notify-list");

        ConfigurationSection rulesSection = config.getConfigurationSection("default-rules");
        if (rulesSection != null) {
            defaultRules.clear();
            for (String key : rulesSection.getKeys(false)) {
                defaultRules.put(key, rulesSection.getBoolean(key));
            }
        }
    }

    public int getLatestVersion() { return LATEST_CONFIG_VERSION; }
    private String format(String m) { return m == null ? "" : m.replace("&", "§"); }
}