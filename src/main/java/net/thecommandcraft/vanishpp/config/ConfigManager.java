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
    public boolean simulateEssentialsMessages;
    public boolean staffNotifyEnabled;
    public boolean scoreboardEnabled, scoreboardAutoShow;
    public int defaultVanishLevel, defaultSeeLevel, maxLevel;
    public String updateCheckerMode, updateCheckerId, language;
    public List<String> updateCheckerList;
    public Map<String, Boolean> defaultRules = new HashMap<>();

    // ── New feature flags ────────────────────────────────────────────────────

    // Anti-combat vanish
    public boolean antiCombatVanishEnabled;
    public int antiCombatPvpSeconds;
    public int antiCombatPveSeconds;

    // Vanish rate-limit
    public boolean rateLimitEnabled;
    public int rateLimitSeconds;

    // Timed vanish
    public boolean timedVanishEnabled;

    // Staff sounds
    public boolean staffSoundsEnabled;
    public String staffSoundVanish;
    public float staffSoundVanishVolume;
    public float staffSoundVanishPitch;
    public String staffSoundUnvanish;
    public float staffSoundUnvanishVolume;
    public float staffSoundUnvanishPitch;

    // Bossbar
    public boolean bossbarEnabled;
    public String bossbarTitle;
    public String bossbarColor;
    public String bossbarStyle;

    // Vanish history
    public boolean historyEnabled;
    public int historyRetentionDays;

    // Per-world rule defaults
    public Map<String, Map<String, Boolean>> worldRules = new HashMap<>();

    // Webhook
    public boolean webhookEnabled;
    public List<String> webhookUrls;
    public String webhookPayloadTemplate;
    public String webhookAuthHeader;

    // WorldGuard integration
    public boolean worldGuardEnabled;

    // Auto-vanish on AFK
    public boolean afkAutoVanishEnabled;
    public int afkAutoVanishSeconds;

    // Vanish wand
    public boolean wandEnabled;
    public String wandMaterial;
    public String wandDisplayName;

    // Incognito mode
    public List<String> incognitoFakeNames;

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

        scoreboardEnabled = config.getBoolean("scoreboard.enabled", true);
        scoreboardAutoShow = config.getBoolean("scoreboard.auto-show-on-vanish", true);

        ConfigurationSection rulesSection = config.getConfigurationSection("default-rules");
        if (rulesSection != null) {
            defaultRules.clear();
            for (String key : rulesSection.getKeys(false)) {
                defaultRules.put(key, rulesSection.getBoolean(key));
            }
        }

        // ── New features ─────────────────────────────────────────────────────
        antiCombatVanishEnabled = config.getBoolean("anti-combat-vanish.enabled", true);
        antiCombatPvpSeconds    = config.getInt("anti-combat-vanish.pvp-cooldown-seconds", 10);
        antiCombatPveSeconds    = config.getInt("anti-combat-vanish.pve-cooldown-seconds", 5);

        rateLimitEnabled  = config.getBoolean("vanish-rate-limit.enabled", true);
        rateLimitSeconds  = config.getInt("vanish-rate-limit.cooldown-seconds", 2);

        timedVanishEnabled = config.getBoolean("timed-vanish.enabled", true);

        staffSoundsEnabled        = config.getBoolean("staff-sounds.enabled", true);
        staffSoundVanish          = config.getString("staff-sounds.vanish.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        staffSoundVanishVolume    = (float) config.getDouble("staff-sounds.vanish.volume", 1.0);
        staffSoundVanishPitch     = (float) config.getDouble("staff-sounds.vanish.pitch", 1.2f);
        staffSoundUnvanish        = config.getString("staff-sounds.unvanish.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        staffSoundUnvanishVolume  = (float) config.getDouble("staff-sounds.unvanish.volume", 1.0);
        staffSoundUnvanishPitch   = (float) config.getDouble("staff-sounds.unvanish.pitch", 0.8f);

        bossbarEnabled = config.getBoolean("bossbar.enabled", false);
        bossbarTitle   = config.getString("bossbar.title", "&bYou are VANISHED");
        bossbarColor   = config.getString("bossbar.color", "BLUE");
        bossbarStyle   = config.getString("bossbar.style", "SOLID");

        historyEnabled       = config.getBoolean("vanish-history.enabled", true);
        historyRetentionDays = config.getInt("vanish-history.retention-days", 30);

        worldRules.clear();
        ConfigurationSection worldSection = config.getConfigurationSection("world-rules");
        if (worldSection != null) {
            for (String worldName : worldSection.getKeys(false)) {
                ConfigurationSection ws = worldSection.getConfigurationSection(worldName);
                if (ws == null) continue;
                Map<String, Boolean> wRules = new HashMap<>();
                for (String ruleKey : ws.getKeys(false)) wRules.put(ruleKey, ws.getBoolean(ruleKey));
                worldRules.put(worldName, wRules);
            }
        }

        webhookEnabled         = config.getBoolean("webhook.enabled", false);
        webhookUrls            = config.getStringList("webhook.urls");
        webhookPayloadTemplate = config.getString("webhook.payload-template",
                "{\"text\":\"{player} {action} on {server}\"}");
        webhookAuthHeader      = config.getString("webhook.authorization", "");

        worldGuardEnabled = config.getBoolean("worldguard.enabled", true);

        afkAutoVanishEnabled = config.getBoolean("afk-auto-vanish.enabled", false);
        afkAutoVanishSeconds = config.getInt("afk-auto-vanish.idle-seconds", 300);

        wandEnabled     = config.getBoolean("vanish-wand.enabled", true);
        wandMaterial    = config.getString("vanish-wand.material", "BLAZE_ROD");
        wandDisplayName = config.getString("vanish-wand.display-name", "&6Vanish Wand");

        incognitoFakeNames = config.getStringList("incognito.fake-names");
        if (incognitoFakeNames.isEmpty()) {
            incognitoFakeNames = List.of("Steve","Alex","Notch","Herobrine","Player");
        }
    }

    public int getLatestVersion() {
        return LATEST_CONFIG_VERSION;
    }
}