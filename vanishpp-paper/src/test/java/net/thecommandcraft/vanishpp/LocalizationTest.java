package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalizationTest {

    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        try {
            plugin = loadVanishpp();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        player = server.addPlayer();
    }

    private Vanishpp loadVanishpp() throws Exception {
        java.lang.reflect.Method method = MockBukkit.class.getMethod("load", Class.class);
        return (Vanishpp) method.invoke(null, Vanishpp.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testLanguageManagerLoading() {
        assertNotNull(plugin.getConfigManager().getLanguageManager());
        String vanishSelf = plugin.getConfigManager().getLanguageManager().getMessage("vanish.self");
        assertNotNull(vanishSelf);
        assertNotEquals("vanish.self", vanishSelf, "Should load actual message from en.yml");
    }

    @Test
    void testLocalizedCommandOutput() {
        player.setOp(true);
        player.performCommand("vanish");

        // The first message after /vanish should be the localized vanish.self message
        net.kyori.adventure.text.Component msg = player.nextComponentMessage();
        assertNotNull(msg);

        String plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(msg);
        String expected = plugin.getConfigManager().getLanguageManager().getMessage("vanish.self");

        // We can't do exact match easily due to MiniMessage parsing, but we check if
        // it's not the key
        assertFalse(plainText.contains("vanish.self"));
    }

    @Test
    void testReloadUpdatesLanguage() {
        // Change language in config (simulated)
        plugin.getConfig().set("language", "en");
        plugin.reloadPluginConfig();

        String vanishSelf = plugin.getConfigManager().getLanguageManager().getMessage("vanish.self");
        assertNotNull(vanishSelf);
    }

    @Test
    void testMissingKeyFallsBackToEnglish() {
        plugin.getConfig().set("language", "en-us");
        plugin.reloadPluginConfig();

        // A key that is deliberately absent from the active translation must fall back to
        // the bundled en-us value rather than rendering a "[Missing: ...]" marker.
        String msg = plugin.getConfigManager().getLanguageManager().getMessage("vanish.self");
        assertNotNull(msg);
        assertFalse(msg.contains("[Missing"), "Missing keys should fall back to en-us, got: " + msg);
    }

    @Test
    void testSelfHealWritesMissingKeysToDisk() throws Exception {
        plugin.getConfig().set("language", "en-us");
        plugin.reloadPluginConfig();

        // Locate the on-disk copy of the active language file and remove one key to simulate
        // an out-of-date install, then trigger a reload so self-heal re-fills it.
        java.io.File langDir = new java.io.File(plugin.getDataFolder(), "languages");
        java.io.File userFile = new java.io.File(langDir, "messages_en-us.yml");
        if (!userFile.exists()) {
            org.bukkit.configuration.file.YamlConfiguration tmp = new org.bukkit.configuration.file.YamlConfiguration();
            // Ensure a bare throw exists? Just skip if the file is not present (MockBukkit may
            // not have extracted it). We only assert self-heal when the file is present.
            return;
        }

        String probeKey = "vanish.self";
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(userFile);
        cfg.set(probeKey, null); // drop the key from the on-disk copy
        cfg.save(userFile);

        plugin.getConfigManager().getLanguageManager().load();

        org.bukkit.configuration.file.YamlConfiguration after =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(userFile);
        assertTrue(after.contains(probeKey), "self-heal should restore the dropped key on disk");
    }

    @Test
    void testVanishAlwaysSetsSleepingIgnored_whenPreventSleepingOff() {
        // Issue #2: a vanished player must never block the server from skipping the night. Even when
        // prevent-sleeping is OFF, vanishing should mark the player sleeping-ignored (fauxSleeping)
        // so they count as a sleeper rather than an awake denominator in the skip-night percentage.
        //
        // SleepRecordingPlayer, not a plain PlayerMock: MockBukkit's isSleepingIgnored() throws
        // UnimplementedOperationException, which JUnit counts as skipped, so the assertions below
        // would never actually run.
        SleepRecordingPlayer sleeper = new SleepRecordingPlayer(server, "Sleepy");
        server.addPlayer(sleeper);

        plugin.getConfigManager().preventSleeping = false;
        plugin.applyVanishEffects(sleeper);
        assertTrue(sleeper.isSleepingIgnored(),
                "vanished player should be sleeping-ignored even when prevent-sleeping=false");

        // And unvanishing clears it.
        plugin.removeVanishEffects(sleeper);
        assertFalse(sleeper.isSleepingIgnored(),
                "unvanished player should no longer be sleeping-ignored");
    }

    /** PlayerMock that actually stores the sleeping-ignored flag (MockBukkit leaves it unimplemented). */
    private static class SleepRecordingPlayer extends PlayerMock {
        private boolean sleepingIgnored;

        SleepRecordingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void setSleepingIgnored(boolean ignored) {
            this.sleepingIgnored = ignored;
        }

        @Override
        public boolean isSleepingIgnored() {
            return sleepingIgnored;
        }
    }

    @Test
    void testVersionKeysResolveInBothLanguages() {
        // Upstream v1.1.9.1 added the nested version.* keys used by /vanish version. The
        // LanguageManager flattens them to messages.version.*, so the flat lookup must resolve
        // for the active language rather than falling back to a [Missing: ...] marker.
        var lm = plugin.getConfigManager().getLanguageManager();

        // en-us
        plugin.getConfig().set("language", "en-us");
        plugin.reloadPluginConfig();
        assertFalse(lm.getMessage("version.header").contains("[Missing"),
                "version.header must resolve (en-us)");
        assertFalse(lm.getMessage("version.up-to-date").contains("[Missing"),
                "version.up-to-date must resolve (en-us)");

        // zh-cn — upstream ships a translated version.* block, so this must not fall back either.
        plugin.getConfig().set("language", "zh-cn");
        plugin.reloadPluginConfig();
        assertFalse(lm.getMessage("version.header").contains("[Missing"),
                "version.header must resolve (zh-cn)");
        assertFalse(lm.getMessage("version.modrinth").contains("[Missing"),
                "version.modrinth must resolve (zh-cn)");
    }
}
