package net.thecommandcraft.vanishpp.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.utils.LanguageManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

public class ConfigManager {
    private final Vanishpp plugin;
    private FileConfiguration config;
    private final int LATEST_CONFIG_VERSION = 6;
    private final LanguageManager languageManager;

    private boolean migratedThisBoot = false;
    private final List<String> migrationNotes = new ArrayList<>();

    // Messages
    public String vanishMessage, unvanishMessage, noPermissionMessage, playerNotFoundMessage;
    public String vanishedOtherMessage, unvanishedOtherMessage, silentChestBlocked;
    public String chatLockedMessage, chatSentMessage, noChatPendingMessage;
    public String vpermsReload, vpermsInvalidUsage, vpermsInvalidPermission, vpermsPermSet, vpermsPermRemoved,
            vpermsPermGetHas, vpermsPermGetDoesNotHave;
    public String silentJoinMessage, silentQuitMessage, staffVanishMessage, staffUnvanishMessage;
    public String fakeJoinMessage, fakeQuitMessage;

    // Appearance
    public String vanishTabPrefix, vanishNametagPrefix, actionBarText, vanishedPlayerFormat;
    public boolean actionBarEnabled, adjustServerListCount, hideFromServerList;

    // Effects
    public boolean hideRealQuit, hideRealJoin, broadcastFakeQuit, broadcastFakeJoin, disableBlockTriggering;
    public boolean hideDeathMessages, hideAdvancements, hideFromPluginList;

    // Invisibility Features
    public boolean enableNightVision, enableFly, disableMobTarget, disableHunger, silentChests, ignoreProjectiles;
    public boolean preventRaid, preventSculk, preventTrample, hideTabComplete, preventSleeping, preventEntityInteract;
    public boolean preventAccidentalChat, godMode, preventPotions, disableFlyOnUnvanish;

    // Appearance extras
    public boolean staffGlowEnabled;

    // Hooks & System
    public boolean voiceChatEnabled, voiceChatIsolate, layeredPermsEnabled, updateCheckerEnabled;
    public boolean simulateEssentialsMessages;
    public boolean staffNotifyEnabled;
    public int defaultVanishLevel, defaultSeeLevel, maxLevel;
    public String updateCheckerMode, updateCheckerId, language;
    public List<String> updateCheckerList;
    public Map<String, Boolean> defaultRules = new HashMap<>();

    public ConfigManager(Vanishpp plugin) {
        this.plugin = plugin;
        this.languageManager = new LanguageManager(plugin);
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public void load() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists())
            plugin.saveDefaultConfig();

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

    public void logMigrationChange(String note) {
        this.migrationNotes.add(note);
    }

    public void sendMigrationReport(Player player) {
        if (!migratedThisBoot)
            return;
        if (plugin.getStorageProvider().hasAcknowledged(player.getUniqueId(), "migration_v" + LATEST_CONFIG_VERSION))
            return;

        player.sendMessage(Component.text(" "));
        player.sendMessage(Component.text("⚠ Vanish++ Config Migrated", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(
                Component.text("Structure updated to v" + LATEST_CONFIG_VERSION + ". Custom settings preserved.",
                        NamedTextColor.YELLOW));
        for (String note : migrationNotes) {
            player.sendMessage(
                    Component.text(" • ", NamedTextColor.GOLD).append(Component.text(note, NamedTextColor.WHITE)));
        }
        player.sendMessage(Component.text("[CLICK TO HIDE PERMANENTLY]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/vack migration"))
                .hoverEvent(
                        HoverEvent.showText(Component.text("Stop seeing this message on join", NamedTextColor.GRAY))));
        player.sendMessage(Component.text(" "));
    }

    private void loadValues() {
        languageManager.load();

        vanishMessage = languageManager.getMessage("vanish.self");
        unvanishMessage = languageManager.getMessage("vanish.unvanish-self");
        noPermissionMessage = languageManager.getMessage("no-permission");
        playerNotFoundMessage = languageManager.getMessage("player-not-found");
        vanishedOtherMessage = languageManager.getMessage("vanish.others");
        unvanishedOtherMessage = languageManager.getMessage("vanish.unvanish-others");
        silentChestBlocked = languageManager.getMessage("silent-chest.blocked");
        chatLockedMessage = languageManager.getMessage("chat.locked");
        chatSentMessage = languageManager.getMessage("chat.sent");
        noChatPendingMessage = languageManager.getMessage("chat.no-pending");
        vpermsReload = languageManager.getMessage("vperms.reload");
        vpermsInvalidUsage = languageManager.getMessage("vperms.invalid-usage");
        vpermsInvalidPermission = languageManager.getMessage("vperms.invalid-permission");
        vpermsPermSet = languageManager.getMessage("vperms.perm-set");
        vpermsPermRemoved = languageManager.getMessage("vperms.perm-removed");
        vpermsPermGetHas = languageManager.getMessage("vperms.perm-get-has");
        vpermsPermGetDoesNotHave = languageManager.getMessage("vperms.perm-get-does-not-have");
        silentJoinMessage = languageManager.getMessage("staff.silent-join");
        silentQuitMessage = languageManager.getMessage("staff.silent-quit");
        fakeJoinMessage = config.getString("messages.fake-join", ""); // Fallback to config for these as they might be
                                                                      // empty intentionally
        fakeQuitMessage = config.getString("messages.fake-quit", "");

        staffNotifyEnabled = config.getBoolean("messages.staff-notify.enabled", true);
        staffVanishMessage = languageManager.getMessage("staff.notify-vanish");
        staffUnvanishMessage = languageManager.getMessage("staff.notify-unvanish");

        vanishTabPrefix = config.getString("vanish-appearance.tab-prefix", "&7[VANISHED] ");
        vanishNametagPrefix = config.getString("vanish-appearance.nametag-prefix", "");
        staffGlowEnabled = config.getBoolean("vanish-appearance.staff-glow", true);
        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled");
        actionBarText = languageManager.getMessage("appearance.action-bar");
        // Support both old key name and new shorter name (Issue #21)
        adjustServerListCount = config.contains("vanish-appearance.adjust-server-list")
                ? config.getBoolean("vanish-appearance.adjust-server-list")
                : config.getBoolean("vanish-appearance.adjust-server-list-count", true);
        vanishedPlayerFormat = languageManager.getMessage("appearance.vanished-player-format");
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
        hideRealQuit = config.getBoolean("vanish-effects.hide-real-quit-messages");
        hideRealJoin = config.getBoolean("vanish-effects.hide-real-join-messages");
        broadcastFakeQuit = config.getBoolean("vanish-effects.broadcast-fake-quit");
        broadcastFakeJoin = config.getBoolean("vanish-effects.broadcast-fake-join");
        disableBlockTriggering = config.getBoolean("vanish-effects.disable-block-triggering");
        hideDeathMessages = config.getBoolean("hide-announcements.death-messages");
        hideAdvancements = config.getBoolean("hide-announcements.advancements");
        hideFromPluginList = config.getBoolean("hide-announcements.hide-from-plugin-list", true);
        enableNightVision = config.getBoolean("invisibility-features.night-vision");
        enableFly = config.getBoolean("flight-control.vanish-enable-fly", true);
        disableFlyOnUnvanish = config.getBoolean("flight-control.unvanish-disable-fly", true);
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
        simulateEssentialsMessages = config.getBoolean("hooks.essentials.simulate-join-leave");
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

    public int getLatestVersion() {
        return LATEST_CONFIG_VERSION;
    }
}