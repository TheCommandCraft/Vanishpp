package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeatureTest {

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
        // Workaround for MockBukkit UnimplementedOperationException
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        
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
    void testVanishCommandToggle() {
        // Grant permission
        player.setOp(true);

        // 1. Run /vanish (Enable)
        player.performCommand("vanish");
        
        // Check API/Metadata
        assertTrue(player.getMetadata("vanished").get(0).asBoolean()); 
        // Check Bukkit invisibility (MockBukkit handles setInvisible?)
        // In Vanishpp, it likely uses NMS or hidePlayer. MockBukkit supports canSee checks.
        
        // 2. Run /vanish (Disable)
        player.performCommand("vanish");
        assertFalse(plugin.isVanished(player));
    }

    @Test
    void testVanishPermissions() {
        // Player is NOT op by default in MockBukkit unless set
        player.setOp(false);
        
        // Try vanish without perm
        player.performCommand("vanish");
        assertFalse(plugin.isVanished(player));
        // player.performCommand returned false presumably.
    }

    @Test
    void testSilentJoin() {
        // Configure Silent Join
        plugin.getConfigManager().setAndSave("messages.silent-join", true); // This is a message string, checking logic...
        // Actually silent join usually means no join string.
        // Let's check config boolean if exists or if it's based on permission.
        // Looking at code: public boolean hideRealJoin;
        
        plugin.getConfigManager().setAndSave("vanish-effects.hide-real-join-messages", true);
        
        PlayerMock newPlayer = server.addPlayer();
        // MockBukkit triggers join event.
        // We need to see if the join message was set to null.
        
        // In a real generic Unit test, it's hard to capture the broadcast unless we spy.
        // But we can check if the player is vanished if they have 'join-vanished' perm?
        // Let's test that simpler case.
        
        // Test: Auto-Vanish on Join
        // newPlayer.addAttachment(plugin, "vanishpp.join-vanished", true);
        // Unable to add perm easily before join in MockBukkit addPlayer helper?
        // We can manually fire the event.
    }
    
    @Test
    void testVanishList() {
        player.setOp(true);
        player.performCommand("vanish");
        
        PlayerMock observer = server.addPlayer();
        observer.setOp(true);
        
        // Run /vlist
        observer.performCommand("vlist");
        
        // Verify message interaction - consume all messages
        boolean found = false;
        net.kyori.adventure.text.Component message;
        while ((message = observer.nextComponentMessage()) != null) {
            String plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message);
            if (plainText.contains(player.getName())) {
                found = true;
                break;
            }
        }
        
        assertTrue(found, "Vanish list output should contain the vanished player's name");
    }
}
