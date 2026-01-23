package net.thecommandcraft.vanishpp.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.function.Function;

public class ConfigManager {
    private final Vanishpp plugin;
    private FileConfiguration config;

    // Increment this ONLY when you change config.yml structure in resources
    private final int LATEST_CONFIG_VERSION = 2;

    private boolean migratedThisBoot = false;
    private final List<String> migrationNotes = new ArrayList<>();

    // --- Configuration Fields ---
    public String vanishMessage, unvanishMessage, noPermissionMessage, playerNotFoundMessage;
    public String vanishedOtherMessage, unvanishedOtherMessage, silentChestBlocked;
    public String pickupEnabledMessage, pickupDisabledMessage;
    public String chatLockedMessage, chatSentMessage, noChatPendingMessage;
    public String vpermsInvalidUsage, vpermsInvalidPermission, vpermsPermSet, vpermsPermRemoved, vpermsPermGetHas, vpermsPermGetDoesNotHave, vpermsReload;
    public String silentJoinMessage, silentQuitMessage;
    public boolean staffNotifyEnabled;
    public String staffVanishMessage, staffUnvanishMessage;
    public String vanishTabPrefix, vanishNametagPrefix, actionBarText, vanishedPlayerFormat;
    public boolean actionBarEnabled, adjustServerListCount, hideFromServerList;
    public boolean hideRealQuit, hideRealJoin, broadcastFakeQuit, broadcastFakeJoin, disableBlockTriggering;
    public boolean hideDeathMessages, hideAdvancements, enableNightVision, enableFly, disableMobTarget, disableHunger;
    public boolean silentChests, ignoreProjectiles, preventRaid, preventSculk, preventTrample, hideTabComplete;
    public boolean preventSleeping, preventEntityInteract, preventAccidentalChat;
    public boolean voiceChatEnabled, voiceChatIsolate, layeredPermsEnabled;
    public int defaultVanishLevel, defaultSeeLevel, maxLevel;
    public boolean updateCheckerEnabled;
    public String updateCheckerMode;
    public List<String> updateCheckerList;
    public Map<String, Boolean> defaultRules = new HashMap<>();

    public ConfigManager(Vanishpp plugin) { this.plugin = plugin; }

    public FileConfiguration getConfig() { return config; }

    public void load() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
        }

        YamlConfiguration checkConfig = YamlConfiguration.loadConfiguration(configFile);
        // If version is missing, it defaults to 1 (Legacy)
        int currentVersion = checkConfig.getInt("config-version", 1);

        if (currentVersion < LATEST_CONFIG_VERSION) {
            handleMigration(configFile, currentVersion);
        }

        // Final load into memory
        this.config = YamlConfiguration.loadConfiguration(configFile);
        loadValues();
    }

    private void handleMigration(File configFile, int oldVersion) {
        plugin.getLogger().info("Old config version detected (" + oldVersion + "). Starting migration...");
        this.migratedThisBoot = true;

        // 1. Load the existing (old) file
        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(configFile);

        // 2. Create a safety backup
        File backup = new File(plugin.getDataFolder(), "config_backup_v" + oldVersion + ".yml");
        try {
            oldConfig.save(backup);
            plugin.getLogger().info("Backup created: " + backup.getName());
        } catch (Exception e) {
            plugin.getLogger().severe("FAILED TO CREATE CONFIG BACKUP! Aborting migration for safety.");
            return;
        }

        // 3. Replace the local config.yml with the fresh one from resources
        // This brings in new comments and keys
        plugin.saveResource("config.yml", true);
        YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);

        // 4. Sequential Migration Logic
        // This moves values from oldConfig into newConfig based on version history
        switch (oldVersion) {
            case 1:
                // MIGRATION FROM 1.1.0/1.1.1 to 1.1.2/1.1.3

                // Prefix Migration
                migrateValue(oldConfig, newConfig, "vanish-appearance.prefix", "vanish-appearance.tab-prefix");
                logChange("Moved 'vanish-appearance.prefix' to 'tab-prefix'.");

                // Join/Quit Naming Refactor
                migrateValue(oldConfig, newConfig, "vanish-effects.fake-leave-message", "vanish-effects.hide-real-quit-messages");
                migrateValue(oldConfig, newConfig, "vanish-effects.fake-join-message", "vanish-effects.hide-real-join-messages");
                logChange("Renamed 'fake-leave/join-message' to 'hide-real-quit/join-messages' for clarity.");

                // Move block triggering to the new invisibility-features section if it was loose
                migrateValue(oldConfig, newConfig, "vanish-effects.disable-block-triggering", "vanish-effects.disable-block-triggering");

                // Ensure the new nametag prefix is empty by default for safety if it didn't exist
                if (!oldConfig.contains("vanish-appearance.nametag-prefix")) {
                    newConfig.set("vanish-appearance.nametag-prefix", "");
                }

                // Future Proofing Example: If you wanted to invert a 'mob_targeting' rule
                // migrateValue(oldConfig, newConfig, "old-ignore-mobs", "mob_targeting", (val) -> !(boolean)val);
        }

        // 5. Update the version key and save to disk
        newConfig.set("config-version", LATEST_CONFIG_VERSION);
        try {
            newConfig.save(configFile);
            plugin.getLogger().info("Config successfully migrated to v" + LATEST_CONFIG_VERSION);
        } catch (Exception e) {
            plugin.getLogger().severe("CRITICAL: Failed to save migrated config!");
            e.printStackTrace();
        }
    }

    /**
     * Migration Helper: Simply transfers a value if it exists.
     */
    private void migrateValue(FileConfiguration oldC, FileConfiguration newC, String oldPath, String newPath) {
        migrateValue(oldC, newC, oldPath, newPath, (val) -> val);
    }

    /**
     * Migration Helper: Transfers and transforms a value using a function.
     */
    private void migrateValue(FileConfiguration oldC, FileConfiguration newC, String oldPath, String newPath, Function<Object, Object> transform) {
        if (oldC.contains(oldPath)) {
            Object oldVal = oldC.get(oldPath);
            try {
                Object transformedVal = transform.apply(oldVal);
                newC.set(newPath, transformedVal);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not transform config key '" + oldPath + "'. Using raw value.");
                newC.set(newPath, oldVal);
            }
        }
    }

    private void logChange(String note) {
        this.migrationNotes.add(note);
    }

    /**
     * Notifies staff about the automatic config changes.
     */
    public void sendMigrationReport(Player player) {
        if (!migratedThisBoot) return;

        player.sendMessage(Component.text(" "));
        player.sendMessage(Component.text("⚠ Vanish++ Config Migration", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("The config was automatically updated to v" + LATEST_CONFIG_VERSION, NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Summary of changes:", NamedTextColor.GRAY));

        for (String note : migrationNotes) {
            player.sendMessage(Component.text(" • ", NamedTextColor.GOLD).append(Component.text(note, NamedTextColor.WHITE)));
        }
        player.sendMessage(Component.text("Check your config.yml to verify settings.", NamedTextColor.AQUA, TextDecoration.ITALIC));
        player.sendMessage(Component.text(" "));
    }

    /**
     * Standard loading of variables into memory.
     */
    private void loadValues() {
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
        staffNotifyEnabled = config.getBoolean("messages.staff-notify.enabled");
        staffVanishMessage = format(config.getString("messages.staff-notify.on-vanish"));
        staffUnvanishMessage = format(config.getString("messages.staff-notify.on-unvanish"));

        // Appearance
        vanishTabPrefix = format(config.getString("vanish-appearance.tab-prefix"));
        vanishNametagPrefix = format(config.getString("vanish-appearance.nametag-prefix"));
        actionBarEnabled = config.getBoolean("vanish-appearance.action-bar.enabled");
        actionBarText = format(config.getString("vanish-appearance.action-bar.text"));
        adjustServerListCount = config.getBoolean("vanish-appearance.adjust-server-list-count");
        vanishedPlayerFormat = format(config.getString("chat-format.vanished-player-format"));

        // Effects
        hideFromServerList = config.getBoolean("vanish-effects.hide-from-server-list");
        hideRealQuit = config.getBoolean("vanish-effects.hide-real-quit-messages");
        hideRealJoin = config.getBoolean("vanish-effects.hide-real-join-messages");
        broadcastFakeQuit = config.getBoolean("vanish-effects.broadcast-fake-quit");
        broadcastFakeJoin = config.getBoolean("vanish-effects.broadcast-fake-join");
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
        preventEntityInteract = config.getBoolean("invisibility-features.prevent-entity-interact");
        preventAccidentalChat = config.getBoolean("invisibility-features.prevent-accidental-chat");

        // Hooks
        voiceChatEnabled = config.getBoolean("hooks.simple-voice-chat.enabled");
        voiceChatIsolate = config.getBoolean("hooks.simple-voice-chat.isolate-vanished-players");

        // Permissions
        layeredPermsEnabled = config.getBoolean("permissions.layered-permissions-enabled");
        defaultVanishLevel = config.getInt("permissions.default-vanish-level");
        defaultSeeLevel = config.getInt("permissions.default-see-level");
        maxLevel = config.getInt("permissions.max-level");

        // Update Checker
        updateCheckerEnabled = config.getBoolean("update-checker.enabled");
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

    private String format(String m) { return m == null ? "" : m.replace("&", "§"); }
}