package net.thecommandcraft.vanishpp.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

    private ServerMock server;
    private Vanishpp plugin;
    private StorageProvider storage;
    private UUID uuid;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        storage = plugin.getStorageProvider();
        uuid = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -------------------------------------------------------------------------
    // Vanish state
    // -------------------------------------------------------------------------

    @Test
    void testIsVanished_returnsFalseByDefault() {
        assertFalse(storage.isVanished(uuid));
    }

    @Test
    void testSetVanishedTrue_persistsAndRetrieves() {
        storage.setVanished(uuid, true);
        assertTrue(storage.isVanished(uuid));
    }

    @Test
    void testSetVanishedFalse_removesFromStorage() {
        storage.setVanished(uuid, true);
        storage.setVanished(uuid, false);
        assertFalse(storage.isVanished(uuid));
    }

    @Test
    void testSetVanishedTrue_idempotent() {
        storage.setVanished(uuid, true);
        storage.setVanished(uuid, true); // Should not duplicate
        Set<UUID> vanished = storage.getVanishedPlayers();
        assertEquals(1, vanished.stream().filter(u -> u.equals(uuid)).count(),
                "UUID must appear exactly once even after double-set");
    }

    // -------------------------------------------------------------------------
    // getVanishedPlayers
    // -------------------------------------------------------------------------

    @Test
    void testGetVanishedPlayers_returnsAllVanished() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        storage.setVanished(a, true);
        storage.setVanished(b, true);
        storage.setVanished(c, false); // Should NOT appear

        Set<UUID> vanished = storage.getVanishedPlayers();
        assertTrue(vanished.contains(a));
        assertTrue(vanished.contains(b));
        assertFalse(vanished.contains(c));
    }

    @Test
    void testGetVanishedPlayers_emptyWhenNoneVanished() {
        // The plugin itself may have players loaded from other tests;
        // ensure our fresh uuid is not in the set
        Set<UUID> vanished = storage.getVanishedPlayers();
        assertFalse(vanished.contains(uuid));
    }

    // -------------------------------------------------------------------------
    // Rules
    // -------------------------------------------------------------------------

    @Test
    void testSetRule_thenGetRule() {
        storage.setRule(uuid, "can_chat", true);
        assertTrue(storage.getRule(uuid, "can_chat", false));
    }

    @Test
    void testGetRule_returnsDefault_whenNotSet() {
        assertFalse(storage.getRule(uuid, "can_chat", false));
        assertTrue(storage.getRule(uuid, "can_chat", true));
    }

    @Test
    void testSetRule_overridesDefault() {
        storage.setRule(uuid, "can_break_blocks", true);
        // Default is false, but we set true — should return true
        assertTrue(storage.getRule(uuid, "can_break_blocks", false));
    }

    @Test
    void testSetRule_falseStoredCorrectly() {
        storage.setRule(uuid, "can_interact", true);
        storage.setRule(uuid, "can_interact", false);
        assertFalse(storage.getRule(uuid, "can_interact", true),
                "After setting rule to false, should return false even if default is true");
    }

    // -------------------------------------------------------------------------
    // getRules map
    // -------------------------------------------------------------------------

    @Test
    void testGetRules_returnsEmptyMapForNewUUID() {
        Map<String, Object> rules = storage.getRules(uuid);
        assertNotNull(rules);
        assertTrue(rules.isEmpty());
    }

    @Test
    void testGetRules_returnsAllSetRules() {
        storage.setRule(uuid, "can_chat", true);
        storage.setRule(uuid, "can_pickup_items", false);

        Map<String, Object> rules = storage.getRules(uuid);
        assertEquals(2, rules.size());
        assertEquals(true, rules.get("can_chat"));
        assertEquals(false, rules.get("can_pickup_items"));
    }

    // -------------------------------------------------------------------------
    // Acknowledgements
    // -------------------------------------------------------------------------

    @Test
    void testHasAcknowledged_returnsFalseByDefault() {
        assertFalse(storage.hasAcknowledged(uuid, "some-notification"));
    }

    @Test
    void testAddAcknowledgement_thenHasAcknowledged() {
        storage.addAcknowledgement(uuid, "protocol-lib-warning");
        assertTrue(storage.hasAcknowledged(uuid, "protocol-lib-warning"));
    }

    @Test
    void testAddAcknowledgement_doesNotDuplicate() {
        storage.addAcknowledgement(uuid, "some-notification");
        storage.addAcknowledgement(uuid, "some-notification"); // Second call is a no-op
        // We can't easily check the count from the interface, but at minimum it should
        // still return true and not throw
        assertTrue(storage.hasAcknowledged(uuid, "some-notification"));
    }

    @Test
    void testAcknowledgement_isPerNotification() {
        storage.addAcknowledgement(uuid, "notif-a");
        assertFalse(storage.hasAcknowledged(uuid, "notif-b"),
                "Acknowledging one notification must not affect another");
    }

    @Test
    void testAcknowledgement_isPerPlayer() {
        UUID other = UUID.randomUUID();
        storage.addAcknowledgement(uuid, "protocol-lib-warning");
        assertFalse(storage.hasAcknowledged(other, "protocol-lib-warning"),
                "Acknowledgement for one player must not affect another");
    }

    // -------------------------------------------------------------------------
    // Vanish level
    // -------------------------------------------------------------------------

    @Test
    void testGetVanishLevel_returnsOneByDefault() {
        assertEquals(1, storage.getVanishLevel(uuid));
    }

    @Test
    void testSetVanishLevel_thenGet() {
        storage.setVanishLevel(uuid, 3);
        assertEquals(3, storage.getVanishLevel(uuid));
    }

    @Test
    void testSetVanishLevel_overwritesPrevious() {
        storage.setVanishLevel(uuid, 2);
        storage.setVanishLevel(uuid, 5);
        assertEquals(5, storage.getVanishLevel(uuid));
    }

    // -------------------------------------------------------------------------
    // removePlayerData
    // -------------------------------------------------------------------------

    @Test
    void testRemovePlayerData_clearsRules() {
        storage.setRule(uuid, "can_chat", true);
        storage.removePlayerData(uuid);
        assertTrue(storage.getRules(uuid).isEmpty(), "Rules must be cleared after removePlayerData");
    }

    @Test
    void testRemovePlayerData_clearsAcknowledgements() {
        storage.addAcknowledgement(uuid, "protocol-lib-warning");
        storage.removePlayerData(uuid);
        assertFalse(storage.hasAcknowledged(uuid, "protocol-lib-warning"),
                "Acknowledgements must be cleared after removePlayerData");
    }

    @Test
    void testRemovePlayerData_clearsLevel() {
        storage.setVanishLevel(uuid, 5);
        storage.removePlayerData(uuid);
        assertEquals(1, storage.getVanishLevel(uuid), "Level must reset to 1 after removePlayerData");
    }

    @Test
    void testRemovePlayerData_doesNotAffectVanishState() {
        // removePlayerData intentionally does NOT clear the vanished-players list
        storage.setVanished(uuid, true);
        storage.removePlayerData(uuid);
        // vanish state should still be there (it's managed separately)
        assertTrue(storage.isVanished(uuid),
                "removePlayerData must not clear vanish state (managed separately)");
    }
}
