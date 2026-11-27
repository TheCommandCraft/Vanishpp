package net.thecommandcraft.vanishpp.hooks;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFer: the voice chat hook said true on every server, also without Simple Voice Chat. It is "auto" now: on when
 * Simple Voice Chat is installed, and admins are told once, with a way to turn it off, when it is found.
 */
class VoiceChatNoticeTest {

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

    private String joinAndRead(String name) throws InterruptedException {
        PlayerMock player = new PlayerMock(server, name, java.util.UUID.nameUUIDFromBytes(name.getBytes())); // same admin each join
        server.addPlayer(player);
        player.setOp(true); // before the delayed join notices run
        server.getScheduler().performTicks(120);
        StringBuilder messages = new StringBuilder();
        String message;
        while ((message = player.nextMessage()) != null) messages.append(message).append('\n');
        player.disconnect();
        server.getScheduler().performTicks(5);
        Thread.sleep(200); // the acknowledgement is written by the storage worker
        return messages.toString();
    }

    @Test
    void autoIsTheDefaultAndMeansOnWhenInstalled() {
        assertEquals("auto", plugin.getConfigManager().getConfig().getString("hooks.simple-voice-chat.enabled"));
        assertTrue(plugin.getConfigManager().voiceChatAuto);
        assertTrue(plugin.getConfigManager().voiceChatEnabled, "auto must not switch the hook off");
    }

    @Test
    void adminsAreToldOnceWhenSimpleVoiceChatIsFound() throws InterruptedException {
        String without = joinAndRead("Admin");
        assertFalse(without.contains("Simple Voice Chat found"), "no notice without Simple Voice Chat:\n" + without);

        MockBukkit.createMockPlugin("voicechat");
        String first = joinAndRead("Admin");
        assertTrue(first.contains("Simple Voice Chat found"), "admins must be told once Simple Voice Chat is found:\n" + first);
        String second = joinAndRead("Admin");
        assertFalse(second.contains("Simple Voice Chat found"), "the notice must not repeat:\n" + second);
    }

    @Test
    void anExplicitTrueIsAChoiceAndGetsNoNotice() throws InterruptedException {
        plugin.getConfigManager().setAndSave("hooks.simple-voice-chat.enabled", true);
        assertFalse(plugin.getConfigManager().voiceChatAuto);
        MockBukkit.createMockPlugin("voicechat");
        String messages = joinAndRead("Admin");
        assertFalse(messages.contains("Simple Voice Chat found"), "true was chosen on purpose:\n" + messages);
    }
}
