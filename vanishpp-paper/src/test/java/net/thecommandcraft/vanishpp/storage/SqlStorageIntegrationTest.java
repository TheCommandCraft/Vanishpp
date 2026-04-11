package net.thecommandcraft.vanishpp.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@link SqlStorage} using an H2 in-memory database
 * in MySQL compatibility mode.  These tests exercise the actual {@link SqlStorage}
 * class (not raw SQL), covering every {@link StorageProvider} method plus schema
 * idempotency and the Boolean-type contract for {@link SqlStorage#getRules}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlStorageIntegrationTest {

    private static ServerMock server;
    private static Vanishpp plugin;
    private static SqlStorage storage;

    private UUID uuid;

    @BeforeAll
    static void setUpAll() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);

        // H2 in MySQL compatibility mode — supports INSERT IGNORE and REPLACE INTO
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:vpp_sql_integration;DB_CLOSE_DELAY=-1;MODE=MySQL");

        storage = new SqlStorage(plugin, ds);
        storage.init();
    }

    @AfterAll
    static void tearDownAll() {
        storage.shutdown();
        MockBukkit.unmock();
    }

    @BeforeEach
    void setUp() {
        uuid = UUID.randomUUID(); // fresh UUID per test — no cross-test interference
    }

    // -------------------------------------------------------------------------
    // Vanish state
    // -------------------------------------------------------------------------

    @Test
    void testIsVanishedFalseByDefault() {
        assertFalse(storage.isVanished(uuid));
    }

    @Test
    void testSetVanishedTruePersists() {
        storage.setVanished(uuid, true);
        assertTrue(storage.isVanished(uuid));
    }

    @Test
    void testSetVanishedFalseRemoves() {
        storage.setVanished(uuid, true);
        storage.setVanished(uuid, false);
        assertFalse(storage.isVanished(uuid));
    }

    @Test
    void testSetVanishedTrueIsIdempotent() {
        storage.setVanished(uuid, true);
        storage.setVanished(uuid, true); // Must not throw or duplicate
        long count = storage.getVanishedPlayers().stream().filter(u -> u.equals(uuid)).count();
        assertEquals(1, count, "Double set-vanished must not create duplicate rows");
    }

    // -------------------------------------------------------------------------
    // getVanishedPlayers
    // -------------------------------------------------------------------------

    @Test
    void testGetVanishedPlayersReturnsOnlyVanished() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        storage.setVanished(a, true);
        storage.setVanished(b, true);
        storage.setVanished(c, false);

        Set<UUID> vanished = storage.getVanishedPlayers();
        assertTrue(vanished.contains(a));
        assertTrue(vanished.contains(b));
        assertFalse(vanished.contains(c));
    }

    // -------------------------------------------------------------------------
    // Rules
    // -------------------------------------------------------------------------

    @Test
    void testSetRuleThenGetRule() {
        storage.setRule(uuid, "can_chat", true);
        assertTrue(storage.getRule(uuid, "can_chat", false));
    }

    @Test
    void testGetRuleReturnsDefaultWhenNotSet() {
        assertFalse(storage.getRule(uuid, "can_chat", false));
        assertTrue(storage.getRule(uuid, "can_chat", true));
    }

    @Test
    void testSetRuleOverridesDefault() {
        storage.setRule(uuid, "can_break_blocks", true);
        assertTrue(storage.getRule(uuid, "can_break_blocks", false));
    }

    @Test
    void testSetRuleFalseStoredCorrectly() {
        storage.setRule(uuid, "can_interact", true);
        storage.setRule(uuid, "can_interact", false);
        assertFalse(storage.getRule(uuid, "can_interact", true),
                "After overwrite to false, getRule must return false even when default is true");
    }

    // -------------------------------------------------------------------------
    // getRules — Boolean type contract
    // -------------------------------------------------------------------------

    @Test
    void testGetRulesEmptyForNewPlayer() {
        assertTrue(storage.getRules(uuid).isEmpty());
    }

    @Test
    void testGetRulesReturnsBooleanNotString() {
        storage.setRule(uuid, "can_chat", true);
        storage.setRule(uuid, "can_pickup_items", false);

        Map<String, Object> rules = storage.getRules(uuid);
        assertEquals(2, rules.size());
        assertInstanceOf(Boolean.class, rules.get("can_chat"),
                "getRules must return Boolean, not raw String");
        assertInstanceOf(Boolean.class, rules.get("can_pickup_items"),
                "getRules must return Boolean, not raw String");
        assertEquals(Boolean.TRUE, rules.get("can_chat"));
        assertEquals(Boolean.FALSE, rules.get("can_pickup_items"));
    }

    @Test
    void testSetRuleUpsertOverwritesPrevious() {
        storage.setRule(uuid, "can_chat", true);
        storage.setRule(uuid, "can_chat", false);
        Map<String, Object> rules = storage.getRules(uuid);
        assertEquals(1, rules.size(), "Upsert must not create duplicate rule rows");
        assertEquals(Boolean.FALSE, rules.get("can_chat"));
    }

    // -------------------------------------------------------------------------
    // Acknowledgements
    // -------------------------------------------------------------------------

    @Test
    void testHasAcknowledgedFalseByDefault() {
        assertFalse(storage.hasAcknowledged(uuid, "migration_v7"));
    }

    @Test
    void testAddAcknowledgementThenHasAcknowledged() {
        storage.addAcknowledgement(uuid, "migration_v7");
        assertTrue(storage.hasAcknowledged(uuid, "migration_v7"));
    }

    @Test
    void testAddAcknowledgementIsIdempotent() {
        storage.addAcknowledgement(uuid, "some-notif");
        storage.addAcknowledgement(uuid, "some-notif"); // Must not throw or duplicate
        assertTrue(storage.hasAcknowledged(uuid, "some-notif"));
    }

    @Test
    void testAcknowledgementIsPerNotification() {
        storage.addAcknowledgement(uuid, "notif-a");
        assertFalse(storage.hasAcknowledged(uuid, "notif-b"));
    }

    @Test
    void testAcknowledgementIsPerPlayer() {
        UUID other = UUID.randomUUID();
        storage.addAcknowledgement(uuid, "protocol-lib-warning");
        assertFalse(storage.hasAcknowledged(other, "protocol-lib-warning"));
    }

    // -------------------------------------------------------------------------
    // Vanish level
    // -------------------------------------------------------------------------

    @Test
    void testGetVanishLevelDefaultsToOne() {
        assertEquals(1, storage.getVanishLevel(uuid));
    }

    @Test
    void testSetAndGetVanishLevel() {
        storage.setVanishLevel(uuid, 3);
        assertEquals(3, storage.getVanishLevel(uuid));
    }

    @Test
    void testSetVanishLevelOverwritesPrevious() {
        storage.setVanishLevel(uuid, 2);
        storage.setVanishLevel(uuid, 5);
        assertEquals(5, storage.getVanishLevel(uuid));
    }

    // -------------------------------------------------------------------------
    // removePlayerData
    // -------------------------------------------------------------------------

    @Test
    void testRemovePlayerDataClearsRules() {
        storage.setRule(uuid, "can_chat", true);
        storage.removePlayerData(uuid);
        assertTrue(storage.getRules(uuid).isEmpty(), "Rules must be cleared after removePlayerData");
    }

    @Test
    void testRemovePlayerDataClearsAcknowledgements() {
        storage.addAcknowledgement(uuid, "protocol-lib-warning");
        storage.removePlayerData(uuid);
        assertFalse(storage.hasAcknowledged(uuid, "protocol-lib-warning"),
                "Acknowledgements must be cleared after removePlayerData");
    }

    @Test
    void testRemovePlayerDataClearsLevel() {
        storage.setVanishLevel(uuid, 5);
        storage.removePlayerData(uuid);
        assertEquals(1, storage.getVanishLevel(uuid), "Level must reset to 1 after removePlayerData");
    }

    @Test
    void testRemovePlayerDataDoesNotClearVanishState() {
        storage.setVanished(uuid, true);
        storage.removePlayerData(uuid);
        assertTrue(storage.isVanished(uuid),
                "removePlayerData must not touch vanish state (managed separately)");
    }

    // -------------------------------------------------------------------------
    // Schema idempotency
    // -------------------------------------------------------------------------

    @Test
    void testInitIsIdempotentWhenCalledTwice() {
        assertDoesNotThrow(storage::init,
                "Calling init() a second time (CREATE TABLE IF NOT EXISTS + INSERT IGNORE) must not throw");
    }
}
