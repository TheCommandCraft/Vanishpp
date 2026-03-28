package net.thecommandcraft.vanishpp.config;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

    private ServerMock server;
    private Vanishpp plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -------------------------------------------------------------------------
    // Fresh install
    // -------------------------------------------------------------------------

    @Test
    void testFreshInstallCreatesDefaultConfig() {
        ConfigManager cm = plugin.getConfigManager();
        assertNotNull(cm.getConfig());
        assertEquals(7, cm.getConfig().getInt("config-version"),
                "Fresh config must be at the current version");
        assertEquals("&7[VANISHED] ", cm.getConfig().getString("vanish-appearance.tab-prefix"));
        assertTrue(cm.getConfig().contains("update-checker"),
                "Fresh config must include the update-checker section");
        assertTrue(cm.getConfig().getBoolean("update-checker.enabled", false),
                "update-checker must be enabled by default");
    }

    // -------------------------------------------------------------------------
    // Config key defaults (no config file at all)
    // -------------------------------------------------------------------------

    @Test
    void testConfigManagerDefaultsAreCorrect() {
        ConfigManager cm = plugin.getConfigManager();
        assertTrue(cm.actionBarEnabled,        "action-bar must default true");
        assertTrue(cm.hideRealQuit,            "hide-real-quit-messages must default true");
        assertTrue(cm.hideRealJoin,            "hide-real-join-messages must default true");
        assertTrue(cm.disableMobTarget,        "disable-mob-targeting must default true");
        assertTrue(cm.godMode,                 "god-mode must default true");
        assertTrue(cm.silentChests,            "silent-chests must default true");
        assertTrue(cm.layeredPermsEnabled,     "layered-permissions-enabled must default true");
        assertTrue(cm.updateCheckerEnabled,    "update-checker.enabled must default true");
        assertEquals("PERMISSION", cm.updateCheckerMode, "notify-mode must default PERMISSION");
        assertEquals(1, cm.defaultVanishLevel, "default-vanish-level must default 1");
        assertEquals(1, cm.defaultSeeLevel,    "default-see-level must default 1");
        assertEquals(100, cm.maxLevel,         "max-level must default 100");
    }

    // -------------------------------------------------------------------------
    // v1 → v7 migration: key refactoring
    // -------------------------------------------------------------------------

    @Test
    void testDirectMigrationV1toV7(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("config.yml").toFile();

        YamlConfiguration v1 = new YamlConfiguration();
        v1.set("config-version", 1);
        v1.set("vanish-appearance.prefix", "&c[OldPrefix] ");         // → tab-prefix
        v1.set("vanish-effects.fake-join-message", true);             // → hide-real-join-messages
        v1.save(configFile);

        new MigrationManager(plugin, plugin.getConfigManager()).runMigration(configFile, 1, 7);

        YamlConfiguration result = YamlConfiguration.loadConfiguration(configFile);

        assertEquals(7, result.getInt("config-version"));
        assertEquals("&c[OldPrefix] ", result.getString("vanish-appearance.tab-prefix"),
                "Old 'prefix' key must be migrated to 'tab-prefix'");
        assertNull(result.getString("vanish-appearance.prefix"),
                "Old 'prefix' key must be removed after migration");
        assertTrue(result.getBoolean("vanish-effects.hide-real-join-messages"),
                "Old 'fake-join-message' must become 'hide-real-join-messages'");
        // New sections injected by deepMerge from template
        assertTrue(result.contains("update-checker"),
                "update-checker section must be present after migration from v1");
        assertTrue(result.contains("flight-control"),
                "flight-control section must be present after migration from v1");
    }

    // -------------------------------------------------------------------------
    // v5 → v7 migration: flight-control restructure preserved
    // -------------------------------------------------------------------------

    @Test
    void testDirectMigrationV5toV7_FlightControl(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("config.yml").toFile();

        YamlConfiguration v5 = new YamlConfiguration();
        v5.set("config-version", 5);
        v5.set("invisibility-features.allow-flight", false);           // → flight-control.vanish-enable-fly
        v5.set("invisibility-features.disable-flight-on-unvanish", false); // → flight-control.unvanish-disable-fly
        v5.set("invisibility-features.god-mode", false);               // custom value must survive
        v5.save(configFile);

        new MigrationManager(plugin, plugin.getConfigManager()).runMigration(configFile, 5, 7);

        YamlConfiguration result = YamlConfiguration.loadConfiguration(configFile);

        assertEquals(7, result.getInt("config-version"));
        assertFalse(result.getBoolean("flight-control.vanish-enable-fly", true),
                "allow-flight=false must become vanish-enable-fly=false");
        assertFalse(result.getBoolean("flight-control.unvanish-disable-fly", true),
                "disable-flight-on-unvanish=false must become unvanish-disable-fly=false");
        assertFalse(result.getBoolean("invisibility-features.god-mode", true),
                "Custom god-mode=false must be preserved through migration");
        assertTrue(result.contains("update-checker"),
                "update-checker section must be injected by v6 migration step");
    }

    // -------------------------------------------------------------------------
    // v6 → v7 migration: update-checker section added
    // -------------------------------------------------------------------------

    @Test
    void testDirectMigrationV6toV7_InjectsUpdateChecker(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("config.yml").toFile();

        // A v6 config that was created BEFORE the update-checker section existed
        YamlConfiguration v6 = new YamlConfiguration();
        v6.set("config-version", 6);
        v6.set("vanish-appearance.tab-prefix", "&6[CUSTOM] ");
        v6.set("invisibility-features.god-mode", false);
        v6.save(configFile);

        new MigrationManager(plugin, plugin.getConfigManager()).runMigration(configFile, 6, 7);

        YamlConfiguration result = YamlConfiguration.loadConfiguration(configFile);

        assertEquals(7, result.getInt("config-version"));
        // Custom values preserved
        assertEquals("&6[CUSTOM] ", result.getString("vanish-appearance.tab-prefix"),
                "Custom tab-prefix must survive v6→v7 migration");
        assertFalse(result.getBoolean("invisibility-features.god-mode", true),
                "Custom god-mode=false must survive v6→v7 migration");
        // New section injected from template
        assertTrue(result.contains("update-checker"),
                "update-checker section must be injected during v6→v7 migration");
        assertTrue(result.getBoolean("update-checker.enabled", false),
                "update-checker.enabled must be true (template default) after injection");
        assertEquals("PERMISSION", result.getString("update-checker.notify-mode"),
                "notify-mode must default to PERMISSION after injection");
    }

    // -------------------------------------------------------------------------
    // Backup file created during migration
    // -------------------------------------------------------------------------

    @Test
    void testMigrationCreatesBackupFile(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("config.yml").toFile();

        YamlConfiguration v5 = new YamlConfiguration();
        v5.set("config-version", 5);
        v5.save(configFile);

        new MigrationManager(plugin, plugin.getConfigManager()).runMigration(configFile, 5, 7);

        File backup = new File(plugin.getDataFolder(), "config_backup_v5.yml");
        assertTrue(backup.exists(), "Migration must create a backup of the pre-migration config");
    }
}
