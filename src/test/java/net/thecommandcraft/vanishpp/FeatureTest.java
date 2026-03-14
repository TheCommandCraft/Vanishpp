package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -------------------------------------------------------------------------
    // Core vanish toggle
    // -------------------------------------------------------------------------

    @Test
    void testVanishCommandToggle() {
        player.setOp(true);

        player.performCommand("vanish");
        assertTrue(plugin.isVanished(player), "Player should be vanished after /vanish");
        assertTrue(player.getMetadata("vanished").get(0).asBoolean(), "Metadata should be set");

        player.performCommand("vanish");
        assertFalse(plugin.isVanished(player), "Player should be visible after second /vanish");
    }

    @Test
    void testVanishPermissions() {
        player.setOp(false);
        player.performCommand("vanish");
        assertFalse(plugin.isVanished(player), "Non-OP player must not be able to vanish");
    }

    // -------------------------------------------------------------------------
    // Vanish another player
    // -------------------------------------------------------------------------

    @Test
    void testVanishOtherPlayer() {
        PlayerMock executor = server.addPlayer();
        executor.setOp(true);
        PlayerMock target = server.addPlayer();

        executor.performCommand("vanish " + target.getName());

        assertTrue(plugin.isVanished(target), "Target should be vanished");
        assertFalse(plugin.isVanished(executor), "Executor should not be vanished");
    }

    @Test
    void testVanishOther_NoPerm() {
        PlayerMock executor = server.addPlayer();
        executor.setOp(false);
        PlayerMock target = server.addPlayer();

        executor.performCommand("vanish " + target.getName());

        assertFalse(plugin.isVanished(target), "Target must not be vanished without permission");
    }

    // -------------------------------------------------------------------------
    // Join / quit message suppression
    // -------------------------------------------------------------------------

    @Test
    void testSilentJoin_suppressesMessage() {
        player.setOp(true);
        plugin.getConfigManager().hideRealJoin = true;
        plugin.vanishPlayer(player, player);

        // Simulate a reconnect join event while the player is marked vanished
        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, Component.text("joined the game"));
        server.getPluginManager().callEvent(joinEvent);

        assertNull(joinEvent.joinMessage(),
                "Join message should be null for a vanished player when hideRealJoin=true");
    }

    @Test
    void testSilentJoin_keepsMsgWhenNotVanished() {
        plugin.getConfigManager().hideRealJoin = true;
        // Player is NOT vanished

        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, Component.text("joined the game"));
        server.getPluginManager().callEvent(joinEvent);

        assertNotNull(joinEvent.joinMessage(), "Join message must not be suppressed for a non-vanished player");
    }

    @Test
    void testSilentQuit_suppressesMessage() {
        player.setOp(true);
        plugin.getConfigManager().hideRealQuit = true;
        plugin.vanishPlayer(player, player);

        PlayerQuitEvent quitEvent = new PlayerQuitEvent(player, Component.text("left the game"),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        server.getPluginManager().callEvent(quitEvent);

        assertNull(quitEvent.quitMessage(),
                "Quit message should be null for a vanished player when hideRealQuit=true");
    }

    @Test
    void testSilentQuit_keepsMsgWhenNotVanished() {
        plugin.getConfigManager().hideRealQuit = true;

        PlayerQuitEvent quitEvent = new PlayerQuitEvent(player, Component.text("left the game"),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        server.getPluginManager().callEvent(quitEvent);

        assertNotNull(quitEvent.quitMessage(), "Quit message must not be suppressed for a non-vanished player");
    }

    // -------------------------------------------------------------------------
    // New player join hides vanished players
    // -------------------------------------------------------------------------

    @Test
    void testJoinHidesVanishedFromNewPlayer() {
        player.setOp(true);
        plugin.vanishPlayer(player, player);

        // Non-OP new player joins — should have vanished player hidden
        PlayerMock newPlayer = server.addPlayer();
        assertFalse(newPlayer.canSee(player),
                "Non-staff joining player must not see an already-vanished player");
    }

    @Test
    void testJoinShowsVanishedToStaff() {
        player.setOp(true);
        plugin.vanishPlayer(player, player);

        // OP new player joins — at join time they are not yet OP, so the join handler
        // hides the vanished player from them. After receiving OP and a visibility
        // refresh they must be able to see the vanished player.
        PlayerMock staffPlayer = server.addPlayer();
        staffPlayer.setOp(true);
        plugin.updateVanishVisibility(player); // re-evaluate now that staffPlayer has OP
        assertTrue(staffPlayer.canSee(player),
                "Staff player with OP must see vanished players after visibility refresh");
    }

    // -------------------------------------------------------------------------
    // /vlist
    // -------------------------------------------------------------------------

    @Test
    void testVanishList() {
        player.setOp(true);
        player.performCommand("vanish");

        PlayerMock observer = server.addPlayer();
        observer.setOp(true);
        observer.performCommand("vlist");

        boolean found = false;
        Component message;
        while ((message = observer.nextComponentMessage()) != null) {
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(message);
            if (text.contains(player.getName())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "/vlist output must contain the vanished player's name");
    }

    // -------------------------------------------------------------------------
    // Flight persistence
    // -------------------------------------------------------------------------

    @Test
    void testFlyModePersistence() {
        player.setOp(true);

        // Case A: Fly disabled on unvanish, no fly permission
        plugin.getConfigManager().disableFlyOnUnvanish = true;
        org.bukkit.permissions.PermissionAttachment att = player.addAttachment(plugin);
        att.setPermission("essentials.fly", false);
        att.setPermission("bukkit.command.fly", false);

        player.performCommand("vanish");
        assertTrue(player.getAllowFlight(), "Flight should be granted on vanish");
        player.performCommand("vanish");
        assertFalse(player.getAllowFlight(), "Flight should be revoked on unvanish without fly permission");

        // Case B: Config keeps flight
        plugin.getConfigManager().disableFlyOnUnvanish = false;
        player.performCommand("vanish");
        player.performCommand("vanish");
        assertTrue(player.getAllowFlight(), "Flight should be preserved when disableFlyOnUnvanish=false");

        // Case C: Rank permission overrides config disable
        plugin.getConfigManager().disableFlyOnUnvanish = true;
        player.addAttachment(plugin, "essentials.fly", true);
        player.performCommand("vanish");
        player.performCommand("vanish");
        assertTrue(player.getAllowFlight(), "Flight should be preserved when player has essentials.fly");
    }

    // -------------------------------------------------------------------------
    // Storage persistence
    // -------------------------------------------------------------------------

    @Test
    void testStoragePersistence() {
        player.setOp(true);
        UUID uuid = player.getUniqueId();

        player.performCommand("vanish");
        assertTrue(plugin.getStorageProvider().isVanished(uuid), "Storage must record vanished state");

        plugin.getRuleManager().setRule(player, RuleManager.CAN_PICKUP_ITEMS, true);
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_PICKUP_ITEMS),
                "Pickup rule should be set to true");

        Map<String, Object> rules = plugin.getStorageProvider().getRules(uuid);
        assertEquals(true, rules.get(RuleManager.CAN_PICKUP_ITEMS), "Rule must be persisted in storage");

        plugin.setWarningIgnored(player, true);
        assertTrue(plugin.getStorageProvider().hasAcknowledged(uuid, "protocol-lib-warning"),
                "Acknowledgement must be persisted");
    }
}
