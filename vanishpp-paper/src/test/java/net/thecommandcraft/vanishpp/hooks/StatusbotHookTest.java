package net.thecommandcraft.vanishpp.hooks;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import mopsy.productions.discord.statusbot.BotManager;
import mopsy.productions.discord.statusbot.Calls;
import mopsy.productions.discord.statusbot.EmbedManager;
import mopsy.productions.discord.statusbot.FakeStatusbot;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for issue #35: vanished players must not show up in Statusbot's Discord status,
 * embeds or join/leave messages. Runs against the stand-ins in
 * {@code mopsy.productions.discord.statusbot}, which mirror Statusbot 24.0's internals.
 */
class StatusbotHookTest {

    private ServerMock server;
    private FakeStatusbot statusbot;
    private Vanishpp plugin;
    private PlayerMock visible;
    private PlayerMock staff;

    @BeforeEach
    void setUp() {
        Calls.reset();
        server = MockBukkit.mock();
        statusbot = MockBukkit.loadWith(FakeStatusbot.class,
                new PluginDescriptionFile("Statusbot", "24.0", FakeStatusbot.class.getName()));
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        visible = server.addPlayer("Steve");
        staff = server.addPlayer("Admin");
        Calls.joined.clear();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        Calls.reset();
    }

    private void quit(PlayerMock player) {
        server.getPluginManager().callEvent(new PlayerQuitEvent(player, Component.text("left the game"),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    @Test
    void hookIsLoaded() {
        assertNotNull(plugin.getIntegrationManager().getStatusbot());
    }

    @Test
    void statusbotsOwnJoinQuitHandlersAreReplaced() {
        server.addPlayer("Alex");
        quit(visible);
        assertTrue(Calls.rawEvents.isEmpty(), "Statusbot's raw handlers should no longer fire: " + Calls.rawEvents);
    }

    @Test
    void visibleJoin_isAnnouncedWithoutVanishedPlayers() {
        plugin.vanishPlayer(staff, staff);
        server.addPlayer("Alex");
        assertEquals(List.of("2 online: Steve,Alex | Alex joined"), Calls.joined);
    }

    @Test
    void vanishedQuit_isNotAnnounced() {
        plugin.vanishPlayer(staff, staff);
        Calls.left.clear();
        quit(staff);
        assertTrue(Calls.left.isEmpty(), "a vanished player leaving must not reach Discord: " + Calls.left);
    }

    @Test
    void visibleQuit_isAnnouncedWithoutThatPlayer() {
        quit(visible);
        assertEquals(List.of("1 online: Admin | Steve left"), Calls.left);
    }

    @Test
    void embedVariables_excludeVanishedPlayers() {
        plugin.vanishPlayer(staff, staff);
        assertEquals("1", EmbedManager.resolve("amount-of-players", statusbot));
        assertEquals("Steve", EmbedManager.resolve("player-list", statusbot));
    }

    @Test
    void vanish_sendsFakeLeave_unvanish_sendsFakeJoin() {
        plugin.getConfigManager().broadcastFakeQuit = true;
        plugin.getConfigManager().broadcastFakeJoin = true;

        plugin.vanishPlayer(staff, staff);
        assertEquals(List.of("1 online: Steve | Admin left"), Calls.left);

        plugin.unvanishPlayer(staff, staff);
        assertEquals(List.of("2 online: Steve,Admin | Admin joined"), Calls.joined);
    }

    @Test
    void vanish_withoutFakeBroadcast_sendsNoMessage() {
        plugin.getConfigManager().broadcastFakeQuit = false;
        plugin.vanishPlayer(staff, staff);
        assertTrue(Calls.left.isEmpty());
    }

    @Test
    void vanishChange_refreshesPresenceOnlyWhenBotIsRunning() {
        plugin.applyVanishEffects(staff);
        assertTrue(Calls.presence.isEmpty(), "must not trigger a bot login when no bot is running");

        BotManager.jda = new Object();
        plugin.removeVanishEffects(staff);
        plugin.applyVanishEffects(staff);
        assertEquals(List.of("2 online: Steve,Admin", "1 online: Steve"), Calls.presence);
    }

    @Test
    void brokenStatusbot_disablesHookAndRestoresDefaults() {
        Calls.parserBroken = true;
        assertDoesNotThrow(() -> server.addPlayer("Alex"));

        Calls.parserBroken = false;
        server.addPlayer("Bob");
        assertEquals(List.of("join Bob"), Calls.rawEvents, "Statusbot's own handlers should be back");
        assertTrue(EmbedManager.resolve("amount-of-players", statusbot).startsWith("raw"));
    }

    @Test
    void disablingVanishpp_restoresStatusbotDefaults() {
        server.getPluginManager().disablePlugin(plugin);
        server.addPlayer("Alex");
        assertEquals(List.of("join Alex"), Calls.rawEvents);
        assertTrue(EmbedManager.resolve("player-list", statusbot).startsWith("raw"));
    }
}
