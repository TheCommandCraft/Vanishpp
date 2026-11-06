package net.thecommandcraft.vanishpp.hooks;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.messaging.IMessageRecipient;
import net.ess3.api.events.PrivateMessagePreSendEvent;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EssentialsRecipientVisibilityTest {
    private ServerMock server;
    private Vanishpp plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    @Test
    void resolvedEssentialsRecipientIsProtectedIncludingRepeatedReplies() {
        PlayerMock hidden = server.addPlayer("Hidden");
        PlayerMock sender = server.addPlayer("Sender");
        plugin.applyVanishEffects(hidden);
        plugin.getConfigManager().layeredPermsEnabled = true;
        hidden.addAttachment(plugin, "vanishpp.vanish.level.10", true);
        sender.addAttachment(plugin, "vanishpp.see", true);
        sender.addAttachment(plugin, "vanishpp.see.level.1", true);
        Essentials essentials = mock(Essentials.class);
        var manager = spy(server.getPluginManager());
        doReturn(essentials).when(manager).getPlugin("Essentials");
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            plugin.getIntegrationManager().load();
        }
        IMessageRecipient from = mock(IMessageRecipient.class);
        IMessageRecipient to = mock(IMessageRecipient.class);
        when(from.getUUID()).thenReturn(sender.getUniqueId());
        when(to.getUUID()).thenReturn(hidden.getUniqueId());
        for (int attempt = 0; attempt < 2; attempt++) {
            PrivateMessagePreSendEvent event = new PrivateMessagePreSendEvent(from, to, "resolved reply");
            server.getPluginManager().callEvent(event);
            assertTrue(event.isCancelled(), "Essentials resolved recipient must respect visibility on every attempt");
        }
        sender.setOp(true);
        PrivateMessagePreSendEvent seer = new PrivateMessagePreSendEvent(from, to, "staff reply");
        server.getPluginManager().callEvent(seer);
        assertFalse(seer.isCancelled(), "authorized seer must retain an Essentials conversation");
    }
}
