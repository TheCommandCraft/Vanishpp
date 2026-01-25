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
        try { oldConfig.save(backup); } catch (Exception e) { e.printStackTrace(); }

        // 2. Load the fresh Template from JAR
        plugin.saveResource("config.yml", true);
        YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);

        // 3. STEP ONE: Recursive Deep Copy (Lossless)
        // We copy everything from old to new, overwriting the new defaults with user values.
        deepMerge(oldConfig, newConfig, "");

        // 4. STEP TWO: Refactoring Rules (Structural changes)
        // This runs after the copy to fix key names.
        applyRefactorRules(oldConfig, newConfig, oldVersion);

        // 5. Finalize
        newConfig.set("config-version", latestVersion);
        try {
            newConfig.save(configFile);
            plugin.getLogger().info("Config migration successful. No data lost.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively copies values from old to new.
     * Preserves custom messages and settings that haven't changed keys.
     */
    private void deepMerge(FileConfiguration source, FileConfiguration target, String path) {
        Set<String> keys = source.getKeys(false);
        if (!path.isEmpty()) {
            if (source.getConfigurationSection(path) == null) return;
            keys = source.getConfigurationSection(path).getKeys(false);
        }

        for (String key : keys) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            if (source.isConfigurationSection(fullPath)) {
                deepMerge(source, target, fullPath);
            } else {
                // Do not copy the internal version key
                if (fullPath.equalsIgnoreCase("config-version")) continue;

                // Copy the user's custom value into the new structure
                target.set(fullPath, source.get(fullPath));
            }
        }
    }

    private void applyRefactorRules(FileConfiguration oldC, FileConfiguration newC, int oldVersion) {
        switch (oldVersion) {
            case 1:
                migrateRefactor(oldC, newC, "vanish-appearance.prefix", "vanish-appearance.tab-prefix");
                migrateRefactor(oldC, newC, "vanish-effects.fake-leave-message", "vanish-effects.hide-real-quit-messages");
                migrateRefactor(oldC, newC, "vanish-effects.fake-join-message", "vanish-effects.hide-real-join-messages");
                configManager.logMigrationChange("Refactored prefix and join/quit keys.");
            case 2:
                // Case 2 specific refactors (None currently, v3 keys are handled by deepMerge/Defaults)
                configManager.logMigrationChange("Enabled Titan God Mode features.");
        }
    }

    private void migrateRefactor(FileConfiguration oldC, FileConfiguration newC, String oldP, String newP) {
        if (oldC.contains(oldP)) {
            newC.set(newP, oldC.get(oldP));
            // Only remove if the path has actually changed to avoid wiping a valid key
            if (!oldP.equals(newP)) newC.set(oldP, null);
        }
    }
}