package net.thecommandcraft.vanishpp.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot;
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
    private final int LATEST_CONFIG_VERSION = 8;
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

    // Spectator mode
    public boolean vanishGamemodesEnabled;

    // Hooks & System
    public boolean voiceChatEnabled, voiceChatIsolate, layeredPermsEnabled, updateCheckerEnabled;
    public boolean updateCheckerShowBeta, updateCheckerShowAlpha;
    public boolean simulateEssentialsMessages;
    public boolean staffNotifyEnabled;
    public boolean scoreboardEnabled, scoreboardAutoShow;
    public int defaultVanishLevel, defaultSeeLevel, maxLevel;
    public String updateCheckerMode, updateCheckerId, language;
    public List<String> updateCheckerList;
    public Map<String, Boolean> defaultRules = new HashMap<>();

    // Vanish Wand
    public boolean wandEnabled;
    public String wandMaterial, wandDisplayName;

    // Incognito
    public List<String> incognitoFakeNames;

    // Webhooks
    public boolean webhookEnabled;
    public List<String> webhookUrls;
    public String webhookPayloadTemplate, webhookAuthHeader;

    // Bossbar
    public boolean bossbarEnabled;
    public String bossbarTitle, bossbarColor, bossbarStyle;

    // Rate limit
    public boolean rateLimitEnabled;
    public int rateLimitSeconds;

    // Anti-combat vanish
    public boolean combatVanishEnabled;
    public int combatPvpCooldown, combatPveCooldown;

    // Timed vanish
    public boolean timedVanishEnabled;

    // Staff sounds
    public boolean staffSoundsEnabled;
    public String staffSoundsVanishSound, staffSoundsUnvanishSound;
    public float staffSoundsVanishVolume, staffSoundsVanishPitch;
    public float staffSoundsUnvanishVolume, staffSoundsUnvanishPitch;
    public String staffSoundsSilentJoinSound, staffSoundsSilentQuitSound;
    public float staffSoundsSilentJoinVolume, staffSoundsSilentJoinPitch;
    public float staffSoundsSilentQuitVolume, staffSoundsSilentQuitPitch;

    // AFK auto-vanish
    public boolean afkAutoVanishEnabled;
    public int afkAutoVanishSeconds;

    // Per-world rule defaults
    public Map<String, Map<String, Boolean>> worldRules = new HashMap<>();

    // Proxy broadcast
    public boolean proxyBroadcastEnabled;
    public int proxyBroadcastMinLevel;

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
        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled", true);
        actionBarText = languageManager.getMessage("appearance.action-bar");
        // Support both old key name and new shorter name (Issue #21)
        adjustServerListCount = config.contains("vanish-appearance.adjust-server-list")
                ? config.getBoolean("vanish-appearance.adjust-server-list")
                : config.getBoolean("vanish-appearance.adjust-server-list-count", true);
        vanishedPlayerFormat = languageManager.getMessage("appearance.vanished-player-format");
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list", true);
        hideRealQuit = config.getBoolean("vanish-effects.hide-real-quit-messages", true);
        hideRealJoin = config.getBoolean("vanish-effects.hide-real-join-messages", true);
        broadcastFakeQuit = config.getBoolean("vanish-effects.broadcast-fake-quit", true);
        broadcastFakeJoin = config.getBoolean("vanish-effects.broadcast-fake-join", true);
        disableBlockTriggering = config.getBoolean("vanish-effects.disable-block-triggering", true);
        hideDeathMessages = config.getBoolean("hide-announcements.death-messages", true);
        hideAdvancements = config.getBoolean("hide-announcements.advancements", true);
        hideFromPluginList = config.getBoolean("hide-announcements.hide-from-plugin-list", true);
        enableNightVision = config.getBoolean("invisibility-features.night-vision", true);
        vanishGamemodesEnabled = config.getBoolean("vanish-gamemodes.enabled", true);
        enableFly = config.getBoolean("flight-control.vanish-enable-fly", true);
        disableFlyOnUnvanish = config.getBoolean("flight-control.unvanish-disable-fly", true);
        disableMobTarget = config.getBoolean("invisibility-features.disable-mob-targeting", true);
        disableHunger = config.getBoolean("invisibility-features.disable-hunger", true);
        silentChests = config.getBoolean("invisibility-features.silent-chests", true);
        ignoreProjectiles = config.getBoolean("invisibility-features.ignore-projectiles", true);
        preventRaid = config.getBoolean("invisibility-features.prevent-raid-trigger", true);
        preventSculk = config.getBoolean("invisibility-features.prevent-sculk-sensors", true);
        preventTrample = config.getBoolean("invisibility-features.prevent-trample", true);
        hideTabComplete = config.getBoolean("invisibility-features.hide-from-tab-complete", true);
        preventSleeping = config.getBoolean("invisibility-features.prevent-sleeping", true);
        preventEntityInteract = config.getBoolean("invisibility-features.prevent-entity-interact", true);
        preventAccidentalChat = config.getBoolean("invisibility-features.prevent-accidental-chat", true);
        godMode = config.getBoolean("invisibility-features.god-mode", true);
        preventPotions = config.getBoolean("invisibility-features.prevent-potion-effects", false);
        voiceChatEnabled = config.getBoolean("hooks.simple-voice-chat.enabled", true);
        voiceChatIsolate = config.getBoolean("hooks.simple-voice-chat.isolate-vanished-players", true);
        simulateEssentialsMessages = config.getBoolean("hooks.essentials.simulate-join-leave", false);
        layeredPermsEnabled = config.getBoolean("permissions.layered-permissions-enabled", true);
        defaultVanishLevel = config.getInt("permissions.default-vanish-level", 1);
        defaultSeeLevel = config.getInt("permissions.default-see-level", 1);
        maxLevel = config.getInt("permissions.max-level", 100);
        updateCheckerEnabled = config.getBoolean("update-checker.enabled", true);
        updateCheckerId = config.getString("update-checker.modrinth-id", "vanish++");
        updateCheckerMode = config.getString("update-checker.notify-mode", "PERMISSION");
        updateCheckerList = config.getStringList("update-checker.notify-list");
        updateCheckerShowBeta  = config.getBoolean("update-checker.show-beta",  true);
        updateCheckerShowAlpha = config.getBoolean("update-checker.show-alpha", true);

        scoreboardEnabled = config.getBoolean("scoreboard.enabled", true);
        scoreboardAutoShow = config.getBoolean("scoreboard.auto-show-on-vanish", true);

        ConfigurationSection rulesSection = config.getConfigurationSection("default-rules");
        if (rulesSection != null) {
            defaultRules.clear();
            for (String key : rulesSection.getKeys(false)) {
                defaultRules.put(key, rulesSection.getBoolean(key));
            }
        }

        // Vanish Wand
        wandEnabled = config.getBoolean("vanish-wand.enabled", true);
        wandMaterial = config.getString("vanish-wand.material", "BLAZE_ROD");
        wandDisplayName = config.getString("vanish-wand.display-name", "<gold><bold>Vanish Wand</bold></gold>");

        // Incognito fake names
        incognitoFakeNames = config.getStringList("incognito.fake-names");
        if (incognitoFakeNames.isEmpty()) {
            incognitoFakeNames = List.of("Steve", "Alex", "Notch", "Herobrine", "Player");
        }

        // Webhooks
        webhookEnabled = config.getBoolean("webhook.enabled", false);
        webhookUrls = config.getStringList("webhook.urls");
        webhookPayloadTemplate = config.getString("webhook.payload-template",
            "{\"player\":\"{player}\",\"action\":\"{action}\",\"reason\":\"{reason}\",\"server\":\"{server}\",\"timestamp\":\"{timestamp}\"}");
        webhookAuthHeader = config.getString("webhook.authorization", "");

        // Bossbar
        bossbarEnabled = config.getBoolean("bossbar.enabled", false);
        bossbarTitle = config.getString("bossbar.title", "<gold>✦ You are <red>Vanished</red></gold>");
        bossbarColor = config.getString("bossbar.color", "GOLD");
        bossbarStyle = config.getString("bossbar.style", "PROGRESS");

        // Rate limit
        rateLimitEnabled = config.getBoolean("vanish-rate-limit.enabled", false);
        rateLimitSeconds = config.getInt("vanish-rate-limit.seconds", 3);

        // Anti-combat vanish
        combatVanishEnabled = config.getBoolean("anti-combat-vanish.enabled", false);
        combatPvpCooldown = config.getInt("anti-combat-vanish.pvp-cooldown-seconds", 10);
        combatPveCooldown = config.getInt("anti-combat-vanish.pve-cooldown-seconds", 5);

        // Timed vanish
        timedVanishEnabled = config.getBoolean("timed-vanish.enabled", true);

        // Staff sounds
        staffSoundsEnabled = config.getBoolean("staff-sounds.enabled", false);
        staffSoundsVanishSound  = config.getString("staff-sounds.vanish.sound",  "BLOCK_NOTE_BLOCK_PLING");
        staffSoundsVanishVolume = (float) config.getDouble("staff-sounds.vanish.volume", 1.0);
        staffSoundsVanishPitch  = (float) config.getDouble("staff-sounds.vanish.pitch",  1.2);
        staffSoundsUnvanishSound  = config.getString("staff-sounds.unvanish.sound",  "BLOCK_NOTE_BLOCK_PLING");
        staffSoundsUnvanishVolume = (float) config.getDouble("staff-sounds.unvanish.volume", 1.0);
        staffSoundsUnvanishPitch  = (float) config.getDouble("staff-sounds.unvanish.pitch",  0.8);
        staffSoundsSilentJoinSound  = config.getString("staff-sounds.silent-join.sound",  "ENTITY_EXPERIENCE_ORB_PICKUP");
        staffSoundsSilentJoinVolume = (float) config.getDouble("staff-sounds.silent-join.volume", 0.6);
        staffSoundsSilentJoinPitch  = (float) config.getDouble("staff-sounds.silent-join.pitch",  1.3);
        staffSoundsSilentQuitSound  = config.getString("staff-sounds.silent-quit.sound",  "ENTITY_EXPERIENCE_ORB_PICKUP");
        staffSoundsSilentQuitVolume = (float) config.getDouble("staff-sounds.silent-quit.volume", 0.6);
        staffSoundsSilentQuitPitch  = (float) config.getDouble("staff-sounds.silent-quit.pitch",  0.7);

        // AFK auto-vanish
        afkAutoVanishEnabled  = config.getBoolean("afk-auto-vanish.enabled", false);
        afkAutoVanishSeconds  = config.getInt("afk-auto-vanish.seconds", 300);

        // Proxy broadcast
        proxyBroadcastEnabled  = config.getBoolean("proxy.broadcast-vanish-events", true);
        proxyBroadcastMinLevel = config.getInt("proxy.broadcast-min-level", 1);

        // Per-world rule defaults
        worldRules.clear();
        ConfigurationSection worldRulesSection = config.getConfigurationSection("world-rules");
        if (worldRulesSection != null) {
            for (String worldName : worldRulesSection.getKeys(false)) {
                ConfigurationSection ws = worldRulesSection.getConfigurationSection(worldName);
                if (ws == null) continue;
                Map<String, Boolean> overrides = new HashMap<>();
                for (String rule : ws.getKeys(false)) {
                    overrides.put(rule, ws.getBoolean(rule));
                }
                if (!overrides.isEmpty()) worldRules.put(worldName, overrides);
            }
        }
    }

    public int getLatestVersion() {
        return LATEST_CONFIG_VERSION;
    }

    /**
     * Returns all config keys whose current value differs from the bundled defaults.
     * Storage, proxy, and config-version keys are excluded — they're server-specific.
     * Used to show the proxy config mismatch warning and to sync changes to the proxy.
     */
    public Map<String, String> getNonDefaultValues() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return result;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in));
            for (String key : config.getKeys(true)) {
                if (config.isConfigurationSection(key)) continue;
                if (key.startsWith("storage.") || key.startsWith("proxy.")
                        || key.startsWith("redis.") || key.equals("config-version")) continue;
                Object current = config.get(key);
                Object def = defaults.get(key);
                if (def != null && !Objects.equals(current, def)) {
                    result.put(key, current != null ? current.toString() : "null");
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Applies a {@link ProxyConfigSnapshot} pushed by the Velocity proxy into this ConfigManager's
     * cached fields. Does NOT write to disk. Language strings are re-loaded from local lang files
     * (they are not part of the proxy config).
     *
     * <p>Called by {@code ProxyConfigCache.update()} whenever the proxy pushes a new config.</p>
     */
    public void loadFromProxySnapshot(ProxyConfigSnapshot s) {
        // Appearance
        vanishTabPrefix      = s.vanishTabPrefix;
        vanishNametagPrefix  = s.vanishNametagPrefix;
        actionBarEnabled     = s.actionBarEnabled;
        adjustServerListCount= s.adjustServerListCount;
        staffGlowEnabled     = s.staffGlowEnabled;
        hideFromServerList   = s.hideFromServerList;

        // Vanish Effects
        hideRealQuit         = s.hideRealQuit;
        hideRealJoin         = s.hideRealJoin;
        broadcastFakeQuit    = s.broadcastFakeQuit;
        broadcastFakeJoin    = s.broadcastFakeJoin;
        disableBlockTriggering = s.disableBlockTriggering;

        // Announcements
        hideDeathMessages    = s.hideDeathMessages;
        hideAdvancements     = s.hideAdvancements;
        hideFromPluginList   = s.hideFromPluginList;

        // Messages
        fakeJoinMessage      = s.fakeJoinMessage;
        fakeQuitMessage      = s.fakeQuitMessage;
        staffNotifyEnabled   = s.staffNotifyEnabled;
        simulateEssentialsMessages = s.simulateEssentialsMessages;

        // Invisibility Features
        enableNightVision    = s.enableNightVision;
        disableMobTarget     = s.disableMobTarget;
        disableHunger        = s.disableHunger;
        silentChests         = s.silentChests;
        ignoreProjectiles    = s.ignoreProjectiles;
        preventRaid          = s.preventRaid;
        preventSculk         = s.preventSculk;
        preventTrample       = s.preventTrample;
        hideTabComplete      = s.hideTabComplete;
        preventSleeping      = s.preventSleeping;
        preventEntityInteract= s.preventEntityInteract;
        preventAccidentalChat= s.preventAccidentalChat;
        godMode              = s.godMode;
        preventPotions       = s.preventPotions;

        // Gamemodes / Flight
        vanishGamemodesEnabled = s.vanishGamemodesEnabled;
        enableFly            = s.enableFly;
        disableFlyOnUnvanish = s.disableFlyOnUnvanish;

        // Hooks
        voiceChatEnabled     = s.voiceChatEnabled;
        voiceChatIsolate     = s.voiceChatIsolate;

        // Permissions / Layered
        layeredPermsEnabled  = s.layeredPermsEnabled;
        defaultVanishLevel   = s.defaultVanishLevel;
        defaultSeeLevel      = s.defaultSeeLevel;
        maxLevel             = s.maxLevel;

        // Update Checker
        updateCheckerEnabled  = s.updateCheckerEnabled;
        updateCheckerMode     = s.updateCheckerMode;
        updateCheckerId       = s.updateCheckerId;
        updateCheckerList     = s.updateCheckerList != null ? s.updateCheckerList : new java.util.ArrayList<>();
        updateCheckerShowBeta  = s.updateCheckerShowBeta;
        updateCheckerShowAlpha = s.updateCheckerShowAlpha;

        // Scoreboard
        scoreboardEnabled    = s.scoreboardEnabled;
        scoreboardAutoShow   = s.scoreboardAutoShow;

        // Default Rules
        if (s.defaultRules != null) {
            defaultRules.clear();
            defaultRules.putAll(s.defaultRules);
        }

        // Re-load language strings from local lang files (not part of proxy config)
        languageManager.load();
        vanishMessage        = languageManager.getMessage("vanish.self");
        unvanishMessage      = languageManager.getMessage("vanish.unvanish-self");
        noPermissionMessage  = languageManager.getMessage("no-permission");
        playerNotFoundMessage= languageManager.getMessage("player-not-found");
        vanishedOtherMessage = languageManager.getMessage("vanish.others");
        unvanishedOtherMessage = languageManager.getMessage("vanish.unvanish-others");
        silentChestBlocked   = languageManager.getMessage("silent-chest.blocked");
        chatLockedMessage    = languageManager.getMessage("chat.locked");
        chatSentMessage      = languageManager.getMessage("chat.sent");
        noChatPendingMessage = languageManager.getMessage("chat.no-pending");
        vpermsReload         = languageManager.getMessage("vperms.reload");
        vpermsInvalidUsage   = languageManager.getMessage("vperms.invalid-usage");
        vpermsInvalidPermission = languageManager.getMessage("vperms.invalid-permission");
        vpermsPermSet        = languageManager.getMessage("vperms.perm-set");
        vpermsPermRemoved    = languageManager.getMessage("vperms.perm-removed");
        vpermsPermGetHas     = languageManager.getMessage("vperms.perm-get-has");
        vpermsPermGetDoesNotHave = languageManager.getMessage("vperms.perm-get-does-not-have");
        silentJoinMessage    = languageManager.getMessage("staff.silent-join");
        silentQuitMessage    = languageManager.getMessage("staff.silent-quit");
        staffVanishMessage   = languageManager.getMessage("staff.notify-vanish");
        staffUnvanishMessage = languageManager.getMessage("staff.notify-unvanish");
        actionBarText        = languageManager.getMessage("appearance.action-bar");
        vanishedPlayerFormat = languageManager.getMessage("appearance.vanished-player-format");

        plugin.getLogger().info("[Proxy] ConfigManager updated from proxy snapshot.");
    }
}