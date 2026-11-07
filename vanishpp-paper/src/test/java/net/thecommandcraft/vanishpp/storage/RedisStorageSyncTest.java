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
    void testBroadcastVanishPayloadIsVanishPrefixPlusUuid() throws Exception {
        UUID uuid = UUID.randomUUID();
        java.util.List<String> published = new java.util.ArrayList<>();
        redis.clients.jedis.Jedis connection = new redis.clients.jedis.Jedis() {
            @Override
            public long publish(String channel, String message) {
                published.add(channel + " " + message);
                redisStorage.handleSyncMessage(message);
                return 1;
            }

            @Override
            public void close() {}
        };
        try (redis.clients.jedis.JedisPool pool = new redis.clients.jedis.JedisPool() {
            @Override
            public redis.clients.jedis.Jedis getResource() { return connection; }
        }) {
            java.lang.reflect.Field field = RedisStorage.class.getDeclaredField("jedisPool");
            field.setAccessible(true);
            field.set(redisStorage, pool);

            redisStorage.broadcastVanish(uuid, true);
            tickScheduler();
            assertEquals(java.util.List.of("vanishpp:sync VANISH:" + uuid), published,
                    "broadcastVanish must publish the vanish payload on the sync channel");
            assertTrue(plugin.isVanished(uuid), "Published vanish must reach the receiver");

            redisStorage.broadcastVanish(uuid, false);
            tickScheduler();
            assertEquals(java.util.List.of("vanishpp:sync VANISH:" + uuid,
                    "vanishpp:sync UNVANISH:" + uuid), published,
                    "broadcastVanish must publish the unvanish payload on the sync channel");
            assertFalse(plugin.isVanished(uuid), "Published unvanish must reach the receiver");
        }
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
    void testMalformedMessagesLeaveStateUnchanged() {
        UUID vanished = UUID.randomUUID();
        UUID visible = UUID.randomUUID();
        redisStorage.handleSyncMessage("VANISH:" + vanished);
        tickScheduler();
        java.util.Set<UUID> before = java.util.Set.copyOf(plugin.getRawVanishedPlayers());
        assertTrue(before.contains(vanished));
        assertFalse(before.contains(visible));

        for (String message : new String[] {null, "", "VANISH_NO_COLON", "VANISH:not-a-uuid",
                "UNVANISH:not-a-uuid", "VANISH:", "UNVANISH:" + vanished + ":extra"}) {
            assertDoesNotThrow(() -> redisStorage.handleSyncMessage(message));
            tickScheduler();
            assertEquals(before, plugin.getRawVanishedPlayers(),
                    "Malformed message must leave the vanished set unchanged: " + message);
        }
    }

    @Test
    void testUnknownActionIsIgnored() {
        UUID vanished = UUID.randomUUID();
        UUID visible = UUID.randomUUID();
        redisStorage.handleSyncMessage("VANISH:" + vanished);
        tickScheduler();
        assertTrue(plugin.isVanished(vanished), "Pre-condition: player must be vanished");
        assertFalse(plugin.isVanished(visible));

        redisStorage.handleSyncMessage("INVISIBLE:" + vanished);
        redisStorage.handleSyncMessage("INVISIBLE:" + visible);
        tickScheduler();
        assertTrue(plugin.isVanished(vanished), "Unknown action must not unvanish a vanished player");
        assertFalse(plugin.isVanished(visible), "Unknown action must not vanish a visible player");

        redisStorage.handleSyncMessage("UNVANISH:" + vanished);
        tickScheduler();
        assertFalse(plugin.isVanished(vanished), "Supported unvanish must still change state");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Advances the MockBukkit scheduler by one tick to execute any queued runTask() calls. */
    private void tickScheduler() {
        server.getScheduler().performTicks(1);
    }
}
