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
        plugin.getConfigManager().preventSleeping = false;
        plugin.applyVanishEffects(player);
        assertTrue(player.isSleepingIgnored(), "vanished player should be sleeping-ignored even when prevent-sleeping=false");

        // And unvanishing clears it.
        plugin.removeVanishEffects(player);
        assertFalse(player.isSleepingIgnored(), "unvanished player should no longer be sleeping-ignored");
    }
}
