package net.thecommandcraft.vanishpp.config;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Set;
import java.util.function.Function;

public class MigrationManager {

    private final Vanishpp plugin;
    private final ConfigManager configManager;

    public MigrationManager(Vanishpp plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void runMigration(File configFile, int oldVersion, int latestVersion) {
        plugin.getLogger().info("Starting Smart-Merge Migration (v" + oldVersion + " -> v" + latestVersion + ")");

        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(configFile);

        // 1. Create Safety Backup
        File backup = new File(plugin.getDataFolder(), "config_backup_v" + oldVersion + ".yml");
        try {
            oldConfig.save(backup);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. Load the fresh Template from JAR (In-Memory)
        // We do NOT saveResource yet to avoid overwriting the user's file if saving
        // fails.
        YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(plugin.getResource("config.yml"),
                        java.nio.charset.StandardCharsets.UTF_8));

        // 3. STEP ONE: Recursive Deep Copy (Lossless)
        // We copy everything from old to new, overwriting the new defaults with user
        // values.
        deepMerge(oldConfig, newConfig, "");

        // 4. STEP TWO: Refactoring Rules (Structural changes)
        // This runs after the copy to fix key names.
        applyRefactorRules(oldConfig, newConfig, oldVersion);

        // 5. Migrate Legacy Messages to messages.yml
        if (oldVersion < 6) {
            migrateLegacyMessages(oldConfig);
        }

        // 6. Finalize
        newConfig.set("config-version", latestVersion);
        try {
            newConfig.save(configFile);
            plugin.getLogger().info("Config migration successful. No data lost.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void migrateLegacyMessages(FileConfiguration oldC) {
        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!msgFile.exists()) {
            plugin.getLogger().warning("messages.yml not found during migration, skipping message port.");
            return;
        }

        YamlConfiguration msgC = YamlConfiguration.loadConfiguration(msgFile);
        boolean changed = false;

        changed |= migrateToMessages(oldC, msgC, "messages.no-permission", "no-permission");
        changed |= migrateToMessages(oldC, msgC, "messages.player-not-found", "player-not-found");
        changed |= migrateToMessages(oldC, msgC, "messages.vanish-self", "vanish.self");
        changed |= migrateToMessages(oldC, msgC, "messages.vanished-other", "vanish.others");
        changed |= migrateToMessages(oldC, msgC, "messages.unvanish-self", "vanish.unvanish-self");
        changed |= migrateToMessages(oldC, msgC, "messages.unvanished-other", "vanish.unvanish-others");
        changed |= migrateToMessages(oldC, msgC, "messages.silent-chest-blocked", "silent-chest.blocked");
        changed |= migrateToMessages(oldC, msgC, "messages.pickup-enabled", "pickup.enabled");
        changed |= migrateToMessages(oldC, msgC, "messages.pickup-disabled", "pickup.disabled");
        changed |= migrateToMessages(oldC, msgC, "messages.chat-locked", "chat.locked");
        changed |= migrateToMessages(oldC, msgC, "messages.chat-sent", "chat.sent");
        changed |= migrateToMessages(oldC, msgC, "messages.no-chat-pending", "chat.no-pending");

        changed |= migrateToMessages(oldC, msgC, "messages.vperms.reload", "vperms.reload");
        changed |= migrateToMessages(oldC, msgC, "messages.vperms.invalid-usage", "vperms.invalid-usage");
        changed |= migrateToMessages(oldC, msgC, "messages.vperms.invalid-permission", "vperms.invalid-permission");
        changed |= migrateToMessages(oldC, msgC, "messages.vperms.perm-set", "vperms.perm-set");
        changed |= migrateToMessages(oldC, msgC, "messages.vperms.perm-removed", "vperms.perm-removed");
        changed |= migrateToMessages(oldC, msgC, "messages.vperms.perm-get-has", "vperms.perm-get-has");
        changed |= migrateToMessages(oldC, msgC, "messages.vperms.perm-get-does-not-have",
                "vperms.perm-get-does-not-have");

        changed |= migrateToMessages(oldC, msgC, "messages.silent-join", "staff.silent-join");
        changed |= migrateToMessages(oldC, msgC, "messages.silent-quit", "staff.silent-quit");

        changed |= migrateToMessages(oldC, msgC, "messages.staff-notify.on-vanish", "staff.notify-vanish");
        changed |= migrateToMessages(oldC, msgC, "messages.staff-notify.on-unvanish", "staff.notify-unvanish");

        changed |= migrateToMessages(oldC, msgC, "vanish-appearance.action-bar.text", "appearance.action-bar");
        changed |= migrateToMessages(oldC, msgC, "chat-format.vanished-player-format",
                "appearance.vanished-player-format");

        if (changed) {
            try {
                msgC.save(msgFile);
                configManager.logMigrationChange("Extracted customizable text into messages.yml");
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save migrated messages to messages.yml");
            }
        }
    }

    private boolean migrateToMessages(FileConfiguration oldC, FileConfiguration msgC, String oldKey, String msgKey) {
        if (oldC.contains(oldKey) && oldC.getString(oldKey) != null && !oldC.getString(oldKey).isEmpty()) {
            msgC.set(msgKey, oldC.getString(oldKey));
            oldC.set(oldKey, null); // Wipe from the main config structure so it deepmerges clean
            return true;
        }
        return false;
    }

    /**
     * Recursively copies values from old to new.
     * Preserves custom messages and settings that haven't changed keys.
     */
    private void deepMerge(FileConfiguration source, FileConfiguration target, String path) {
        Set<String> keys = source.getKeys(false);
        if (!path.isEmpty()) {
            if (source.getConfigurationSection(path) == null)
                return;
            keys = source.getConfigurationSection(path).getKeys(false);
        }

        for (String key : keys) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            if (source.isConfigurationSection(fullPath)) {
                deepMerge(source, target, fullPath);
            } else {
                // Do not copy the internal version key
                if (fullPath.equalsIgnoreCase("config-version"))
                    continue;

                // Copy the user's custom value into the new structure
                target.set(fullPath, source.get(fullPath));
            }
        }
    }

    private void applyRefactorRules(FileConfiguration oldC, FileConfiguration newC, int oldVersion) {
        switch (oldVersion) {
            case 1:
                migrateRefactor(oldC, newC, "vanish-appearance.prefix", "vanish-appearance.tab-prefix");
                migrateRefactor(oldC, newC, "vanish-effects.fake-leave-message",
                        "vanish-effects.hide-real-quit-messages");
                migrateRefactor(oldC, newC, "vanish-effects.fake-join-message",
                        "vanish-effects.hide-real-join-messages");
                configManager.logMigrationChange("Refactored prefix and join/quit keys.");
            case 2:
                configManager.logMigrationChange("Enabled Titan God Mode features.");
            case 3:
                configManager.logMigrationChange("Optimized rule feedback by removing alert rate-limiting.");
            case 4:
                configManager.logMigrationChange("Added setting to persist flight mode after unvanishing.");
            case 5:
                migrateRefactor(oldC, newC, "invisibility-features.allow-flight", "flight-control.vanish-enable-fly");
                migrateRefactor(oldC, newC, "invisibility-features.disable-flight-on-unvanish",
                        "flight-control.unvanish-disable-fly");
                configManager
                        .logMigrationChange("Restructured flight settings into dedicated 'flight-control' section.");
                break;
        }
    }

    private void migrateRefactor(FileConfiguration oldC, FileConfiguration newC, String oldP, String newP) {
        if (oldC.contains(oldP)) {
            newC.set(newP, oldC.get(oldP));
            // Only remove if the path has actually changed to avoid wiping a valid key
            if (!oldP.equals(newP))
                newC.set(oldP, null);
        }
    }
}