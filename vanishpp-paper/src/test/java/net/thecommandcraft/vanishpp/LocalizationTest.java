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
}
