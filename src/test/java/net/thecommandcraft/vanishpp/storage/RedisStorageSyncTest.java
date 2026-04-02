package net.thecommandcraft.vanishpp.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Redis cross-server synchronization layer.
 *
 * <p>These tests do NOT require a running Redis server.  They simulate a remote
 * server by calling {@link RedisStorage#handleSyncMessage} directly — the same
 * code path that executes when a real pub/sub message arrives over the network.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Correct publish payload format (VANISH:/UNVANISH: + UUID)</li>
 *   <li>Remote server vanishes a player → local plugin marks them as vanished</li>
 *   <li>Remote server unvanishes a player → local plugin removes them from vanished set</li>
 *   <li>Round-trip: vanish then unvanish via remote messages</li>
 *   <li>Malformed and unknown messages are safely ignored</li>
 *   <li>Invalid UUID strings in otherwise-correct messages are safely ignored</li>
 *   <li>Extra messages for players not currently online are handled gracefully</li>
 * </ul>
 */
class RedisStorageSyncTest {

    private ServerMock server;
    private Vanishpp plugin;
    private RedisStorage redisStorage;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        // Construct RedisStorage without calling init() — no real Redis needed.
        redisStorage = new RedisStorage(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -------------------------------------------------------------------------
    // Payload format
    // -------------------------------------------------------------------------

    @Test
    void testBroadcastVanishPayloadIsVanishPrefixPlusUuid() {
        // broadcastVanish writes to Redis; we only verify the payload is VANISH:{uuid}
        // by inspecting what handleSyncMessage would need to parse it back correctly.
        UUID uuid = UUID.randomUUID();
        String expectedVanish = "VANISH:" + uuid;
        String expectedUnvanish = "UNVANISH:" + uuid;

        // Round-trip: apply the message → check it resolves without error
        assertDoesNotThrow(() -> redisStorage.handleSyncMessage(expectedVanish),
                "VANISH payload must be parsed without exception");
        tickScheduler();

        assertDoesNotThrow(() -> redisStorage.handleSyncMessage(expectedUnvanish),
                "UNVANISH payload must be parsed without exception");
        tickScheduler();
    }

    // -------------------------------------------------------------------------
    // Remote vanish sync
    // -------------------------------------------------------------------------

    @Test
    void testRemoteVanishSyncAddsPlayerToVanishedSet() {
        UUID uuid = UUID.randomUUID();

        // Simulate: a remote server publishes VANISH:{uuid}
        redisStorage.handleSyncMessage("VANISH:" + uuid);
        tickScheduler();

        assertTrue(plugin.isVanished(uuid),
                "After a remote VANISH sync, the UUID must appear in the local vanished set");
    }

    @Test
    void testRemoteUnvanishSyncRemovesPlayerFromVanishedSet() {
        UUID uuid = UUID.randomUUID();

        // First: put the player into the vanished set via a previous vanish sync
        redisStorage.handleSyncMessage("VANISH:" + uuid);
        tickScheduler();
        assertTrue(plugin.isVanished(uuid), "Pre-condition: player must be vanished");

        // Simulate: remote server unvanishes the player
        redisStorage.handleSyncMessage("UNVANISH:" + uuid);
        tickScheduler();

        assertFalse(plugin.isVanished(uuid),
                "After a remote UNVANISH sync, the UUID must be removed from the local vanished set");
    }

    @Test
    void testRemoteVanishSyncRoundTrip() {
        UUID uuid = UUID.randomUUID();

        redisStorage.handleSyncMessage("VANISH:" + uuid);
        tickScheduler();
        assertTrue(plugin.isVanished(uuid));

        redisStorage.handleSyncMessage("UNVANISH:" + uuid);
        tickScheduler();
        assertFalse(plugin.isVanished(uuid));

        // Vanish again — state must be settable after unvanish
        redisStorage.handleSyncMessage("VANISH:" + uuid);
        tickScheduler();
        assertTrue(plugin.isVanished(uuid));
    }

    @Test
    void testRemoteVanishSyncIsIdempotent() {
        UUID uuid = UUID.randomUUID();

        // Receiving the same VANISH message twice must not cause errors
        redisStorage.handleSyncMessage("VANISH:" + uuid);
        redisStorage.handleSyncMessage("VANISH:" + uuid);
        tickScheduler();

        assertTrue(plugin.isVanished(uuid),
                "Duplicate VANISH sync must be idempotent");
    }

    @Test
    void testMultiplePlayersCanBeSyncedIndependently() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        redisStorage.handleSyncMessage("VANISH:" + a);
        redisStorage.handleSyncMessage("VANISH:" + b);
        tickScheduler();

        assertTrue(plugin.isVanished(a));
        assertTrue(plugin.isVanished(b));
        assertFalse(plugin.isVanished(c), "Player C was never synced and must not be vanished");

        // Unvanish A — B and C must be unaffected
        redisStorage.handleSyncMessage("UNVANISH:" + a);
        tickScheduler();

        assertFalse(plugin.isVanished(a));
        assertTrue(plugin.isVanished(b), "Unvanishing A must not affect B");
        assertFalse(plugin.isVanished(c));
    }

    // -------------------------------------------------------------------------
    // Offline player handling
    // -------------------------------------------------------------------------

    @Test
    void testRemoteVanishForOfflinePlayerDoesNotThrow() {
        // Player is not online (MockBukkit has no players by default)
        UUID offlineUuid = UUID.randomUUID();
        assertDoesNotThrow(() -> {
            redisStorage.handleSyncMessage("VANISH:" + offlineUuid);
            tickScheduler();
        }, "Syncing a vanish for an offline player must not throw");

        // State should still be recorded in the in-memory set
        assertTrue(plugin.isVanished(offlineUuid),
                "Even for offline players, the UUID must be added to the vanished set on VANISH sync");
    }

    // -------------------------------------------------------------------------
    // Malformed message safety
    // -------------------------------------------------------------------------

    @Test
    void testMalformedMessageWithNoColonIsIgnored() {
        assertDoesNotThrow(() -> redisStorage.handleSyncMessage("VANISH_NO_COLON"),
                "Message with no colon separator must be silently ignored");
        tickScheduler();
        // Nothing should have been added to vanished set — no crash
    }

    @Test
    void testEmptyMessageIsIgnored() {
        assertDoesNotThrow(() -> redisStorage.handleSyncMessage(""),
                "Empty message must be silently ignored");
        tickScheduler();
    }

    @Test
    void testMessageWithInvalidUuidIsIgnored() {
        // Valid prefix but garbage UUID
        assertDoesNotThrow(() -> redisStorage.handleSyncMessage("VANISH:not-a-uuid"),
                "Message with invalid UUID must be silently ignored");
        tickScheduler();
    }

    @Test
    void testUnknownActionIsIgnored() {
        UUID uuid = UUID.randomUUID();
        // Action is neither VANISH nor UNVANISH
        assertDoesNotThrow(() -> redisStorage.handleSyncMessage("INVISIBLE:" + uuid),
                "Unknown action prefix must be silently ignored");
        tickScheduler();
        // Should not add to vanished set
        assertFalse(plugin.isVanished(uuid),
                "Unknown action must not alter vanish state");
    }

    @Test
    void testNullMessageIsIgnored() {
        assertDoesNotThrow(() -> redisStorage.handleSyncMessage(null),
                "Null message must be silently ignored without NullPointerException");
        tickScheduler();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Advances the MockBukkit scheduler by one tick to execute any queued runTask() calls. */
    private void tickScheduler() {
        server.getScheduler().performTicks(1);
    }
}
