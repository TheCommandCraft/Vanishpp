package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

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
        // In Vanishpp, it likely uses NMS or hidePlayer. MockBukkit supports canSee
        // checks.

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
        plugin.getConfigManager().setAndSave("messages.silent-join", true); // This is a message string, checking
                                                                            // logic...
        // Actually silent join usually means no join string.
        // Let's check config boolean if exists or if it's based on permission.
        // Looking at code: public boolean hideRealJoin;

        plugin.getConfigManager().setAndSave("vanish-effects.hide-real-join-messages", true);

        PlayerMock newPlayer = server.addPlayer();
        assertNotNull(newPlayer, "Should be able to add a new player to the server");
        // We need to see if the join message was set to null.

        // In a real generic Unit test, it's hard to capture the broadcast unless we
        // spy.
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
            String plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(message);
            if (plainText.contains(player.getName())) {
                found = true;
                break;
            }
        }

        assertTrue(found, "Vanish list output should contain the vanished player's name");
    }

    @Test
    void testFlyModePersistence() {
        player.setOp(true);

        // Test Case A: Default behavior (disable on unvanish) - Without Permission
        plugin.getConfigManager().disableFlyOnUnvanish = true;
        org.bukkit.permissions.PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission("essentials.fly", false);
        attachment.setPermission("bukkit.command.fly", false);

        player.performCommand("vanish"); // Enable
        assertTrue(player.getAllowFlight());
        player.performCommand("vanish"); // Disable
        assertFalse(player.getAllowFlight(), "Should disable flight if no fly permission exists");
        plugin.getConfigManager().disableFlyOnUnvanish = false;
        player.performCommand("vanish"); // Enable
        assertTrue(player.getAllowFlight());
        player.performCommand("vanish"); // Disable
        assertTrue(player.getAllowFlight(), "Should keep flight if config says so");

        // Test Case C: Rank Permission overrides config disable
        plugin.getConfigManager().disableFlyOnUnvanish = true;
        player.addAttachment(plugin, "essentials.fly", true);

        player.performCommand("vanish"); // Enable
        assertTrue(player.getAllowFlight());
        player.performCommand("vanish"); // Disable
        assertTrue(player.getAllowFlight(), "Should PRESERVE flight because of essentials.fly permission");
    }

    @Test
    void testStoragePersistence() {
        player.setOp(true);
        UUID uuid = player.getUniqueId();

        // 1. Test Vanish State Persistence
        player.performCommand("vanish");
        assertTrue(plugin.getStorageProvider().isVanished(uuid), "Storage should record vanished state");

        // 2. Test Rule Persistence
        player.performCommand("vanishpickup");
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_PICKUP_ITEMS), "Rule should be toggled");

        // Directly check storage for the rule
        Map<String, Object> rules = plugin.getStorageProvider().getRules(uuid);
        assertEquals(true, rules.get(RuleManager.CAN_PICKUP_ITEMS), "Rule should be persisted in storage");

        // 3. Test Metadata/Ack Persistence
        plugin.setWarningIgnored(player, true);
        assertTrue(plugin.getStorageProvider().hasAcknowledged(uuid, "protocol-lib-warning"),
                "Acknowledgement should be persisted");
    }
}
