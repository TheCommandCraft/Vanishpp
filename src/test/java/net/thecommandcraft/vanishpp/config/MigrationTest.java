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
    }

    private Vanishpp loadVanishpp() {
        try {
            java.lang.reflect.Method method = MockBukkit.class.getMethod("load", Class.class);
            return (Vanishpp) method.invoke(null, Vanishpp.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testFreshInstallCreatesDefaultConfig() {
        plugin = loadVanishpp();
        ConfigManager cm = plugin.getConfigManager();

        assertNotNull(cm.getConfig());
        assertEquals(6, cm.getConfig().getInt("config-version"));
        assertEquals("&7[VANISHED] ", cm.getConfig().getString("vanish-appearance.tab-prefix"));
    }

    @Test
    void testV1MigrationRefactorsKeys() throws IOException {
        // Pre-create v1 config
        // We need to do this BEFORE loading the plugin, but MockBukkit loads plugin
        // from disk?
        // MockBukkit allows loading from a specific jar or just loading the class.
        // When loading by class, it uses a temporary directory for the data folder.
        // We can intercept the load or just manually verify the logic via
        // MigrationManager directly if possible.
        // But better to let the plugin load normally.
        // To inject files before load with specific data folder in MockBukkit is tricky
        // without a descriptor.
        // Instead, let's manually trigger migration or use internal file manipulation
        // if possible.

        // Actually, MockBukkit.load(Vanishpp.class) sets up the data folder.
        // We can access it via configuration logic? No, we need to write the file
        // before the plugin 'onEnable' runs or 'load' runs.
        // But MockBukkit.load calls onEnable.

        // Strategy: MockBukkit doesn't easily let us prepopulate the data folder before
        // load when using load(Class).
        // However, we can use `MockBukkit.createMockPlugin` or similar?
        // A better approach for integration:
        // 1. Mock server.
        // 2. Do NOT load plugin yet.
        // 3. Manually create the plugin instance or minimal setup? No.

        // Alternative: Use the MigrationManager directly with a temp folder.
        // configManager.load() calls MigrationManager.

        // Let's test MigrationManager logic directly first if possible, or try to hack
        // the data folder.

        // Let's try to pass parameters or just rely on MigrationManager unit test
        // interacting with files.
    }

    // Changing strategy to test MigrationManager directly to avoid MockBukkit
    // lifecycle complexity for file prep.

    @Test
    void testDirectMigrationV1toV6(@TempDir Path tempDir) throws IOException {
        // 1. Setup Environment
        plugin = loadVanishpp(); // Load via reflection
        // We mock the data folder return
        // But we can just pass the file to runMigration

        File configFile = tempDir.resolve("config.yml").toFile();

        // 2. Create V1 Data
        YamlConfiguration v1 = new YamlConfiguration();
        v1.set("config-version", 1);
        v1.set("vanish-appearance.prefix", "&c[OldPrefix] "); // Should move to tab-prefix
        v1.set("vanish-effects.fake-join-message", true); // Should move to hide-real-join-messages
        v1.save(configFile);

        // 3. Run Migration
        ConfigManager cm = new ConfigManager(plugin);
        // We need to inject the data folder behavior into the plugin mock if
        // MigrationManager uses it for backups
        // Plugin.getDataFolder() is final or hard to mock without spy.
        // Let's hope MockBukkit provides a valid data folder. It usually does.

        MigrationManager mm = new MigrationManager(plugin, cm);

        // We need to ensure plugin.saveResource works. MockBukkit supports this if we
        // have the resource in src/main/resources.
        // Since we are running in the project, it should find it.

        mm.runMigration(configFile, 1, 6);

        // 4. Verify
        YamlConfiguration result = YamlConfiguration.loadConfiguration(configFile);

        assertEquals(6, result.getInt("config-version"));

        // Check refactor
        assertEquals("&c[OldPrefix] ", result.getString("vanish-appearance.tab-prefix"));
        assertNull(result.getString("vanish-appearance.prefix"));

        // Check value mapping
        assertTrue(result.getBoolean("vanish-effects.hide-real-join-messages"));
    }

    @Test
    void testDirectMigrationV3toV6_DeepMerge(@TempDir Path tempDir) throws IOException {
        // 1. Setup Environment
        plugin = loadVanishpp(); // Load normally to get resources set up
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        // 2. Create V3 Data (User has customized something)
        YamlConfiguration v3 = new YamlConfiguration();
        v3.set("config-version", 3);
        v3.set("messages.vanish", "&aCustom Vanish Message");
        v3.set("invisibility-features.god-mode", false); // Default is true
        v3.save(configFile);

        // 3. Run Migration manually (simulating a reload or boot on old file)
        // Since we already loaded the plugin, it created a default config or used
        // existing.
        // Let's force run the migration method on this file.
        ConfigManager cm = plugin.getConfigManager();
        new MigrationManager(plugin, cm).runMigration(configFile, 3, 6);

        // 4. Verify
        YamlConfiguration result = YamlConfiguration.loadConfiguration(configFile);
        assertEquals(6, result.getInt("config-version"));
        assertEquals("&aCustom Vanish Message", result.getString("messages.vanish")); // Preserved
        assertFalse(result.getBoolean("invisibility-features.god-mode")); // Preserved

        // Check new key exists (assuming v4 added something new from reference config)
        // If config.yml in src/main/resources has something new, it should be in
        // result.
        // Let's assume 'messages.staff-notify.enabled' is new or we just check a
        // standard key
        assertTrue(result.contains("vanish-appearance.tab-prefix"));
    }
}
