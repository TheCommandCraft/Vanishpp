package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.storage.StorageProvider;
import net.thecommandcraft.vanishpp.storage.VanishHistoryEntry;
import net.thecommandcraft.vanishpp.storage.VanishStats;
import net.thecommandcraft.vanishpp.zone.VanishZone;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for new features added in 1.1.7:
 * - Vanish history storage
 * - VanishStats storage
 * - Auto-vanish on join preference
 * - Rule presets storage
 * - VanishHistoryEntry formatting
 * - VanishStats formatting
 * - VanishZone geometry
 * - Vanish reason tracking
 * - Incognito mode state
 */
class NewFeaturesTest {

    private ServerMock server;
    private Vanishpp plugin;
    private StorageProvider storage;
    private UUID uuid;
    private UUID uuid2;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().preventSleeping = false;
        storage = plugin.getStorageProvider();
        uuid = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ── VanishHistoryEntry factory methods ───────────────────────────────────

    @Test
    void vanishEntry_hasCorrectAction() {
        VanishHistoryEntry entry = VanishHistoryEntry.vanish(uuid, "Alice", "TestServer", "testing");
        assertEquals(VanishHistoryEntry.Action.VANISH, entry.getAction());
        assertEquals("Alice", entry.getPlayerName());
        assertEquals(uuid, entry.getPlayerUuid());
        assertEquals("testing", entry.getReason());
        assertEquals("TestServer", entry.getServer());
        assertEquals(0, entry.getDurationMs());
    }

    @Test
    void vanishEntry_nullReason_isStored() {
        VanishHistoryEntry entry = VanishHistoryEntry.vanish(uuid, "Alice", "TestServer", null);
        assertNull(entry.getReason());
    }

    @Test
    void unvanishEntry_hasCorrectActionAndDuration() {
        long duration = 120_000L;
        VanishHistoryEntry entry = VanishHistoryEntry.unvanish(uuid, "Alice", "TestServer", null, duration);
        assertEquals(VanishHistoryEntry.Action.UNVANISH, entry.getAction());
        assertEquals(120_000L, entry.getDurationMs());
    }

    @Test
    void vanishEntry_timestampIsRecent() {
        Instant before = Instant.now().minusSeconds(1);
        VanishHistoryEntry entry = VanishHistoryEntry.vanish(uuid, "Alice", "S", null);
        Instant after = Instant.now().plusSeconds(1);
        assertTrue(entry.getTimestamp().isAfter(before));
        assertTrue(entry.getTimestamp().isBefore(after));
    }

    @Test
    void formatDuration_zero_returnsPlaceholder() {
        VanishHistoryEntry entry = VanishHistoryEntry.vanish(uuid, "X", "S", null);
        assertEquals("—", entry.formatDuration());
    }

    @Test
    void formatDuration_seconds_only() {
        VanishHistoryEntry entry = VanishHistoryEntry.unvanish(uuid, "X", "S", null, 45_000L);
        assertEquals("45s", entry.formatDuration());
    }

    @Test
    void formatDuration_minutes_and_seconds() {
        VanishHistoryEntry entry = VanishHistoryEntry.unvanish(uuid, "X", "S", null, 90_000L);
        assertEquals("1m 30s", entry.formatDuration());
    }

    @Test
    void formatDuration_hours_minutes_seconds() {
        VanishHistoryEntry entry = VanishHistoryEntry.unvanish(uuid, "X", "S", null, 3_661_000L);
        assertEquals("1h 1m 1s", entry.formatDuration());
    }

    // ── VanishStats ──────────────────────────────────────────────────────────

    @Test
    void vanishStats_empty_hasZeros() {
        VanishStats stats = VanishStats.empty();
        assertEquals(0, stats.getTotalVanishTimeMs());
        assertEquals(0, stats.getVanishCount());
        assertEquals(0, stats.getLongestSessionMs());
    }

    @Test
    void vanishStats_formatTotal_zero() {
        assertEquals("0s", VanishStats.empty().formatTotal());
    }

    @Test
    void vanishStats_formatTotal_withTime() {
        VanishStats stats = new VanishStats(3600_000L, 5, 3600_000L);
        String formatted = stats.formatTotal();
        assertTrue(formatted.contains("1h") || formatted.contains("60m"),
                "Expected formatted time containing hours or minutes, got: " + formatted);
    }

    @Test
    void vanishStats_formatLongest_zero() {
        assertEquals("0s", VanishStats.empty().formatLongest());
    }

    @Test
    void vanishStats_counts_correctly() {
        VanishStats stats = new VanishStats(500L, 3, 250L);
        assertEquals(3, stats.getVanishCount());
        assertEquals(500L, stats.getTotalVanishTimeMs());
        assertEquals(250L, stats.getLongestSessionMs());
    }

    // ── Storage: auto-vanish on join ─────────────────────────────────────────

    @Test
    void autoVanishOnJoin_defaultIsFalse() {
        assertFalse(storage.getAutoVanishOnJoin(uuid));
    }

    @Test
    void autoVanishOnJoin_setTrueAndRetrieve() {
        storage.setAutoVanishOnJoin(uuid, true);
        assertTrue(storage.getAutoVanishOnJoin(uuid));
    }

    @Test
    void autoVanishOnJoin_setFalseAfterTrue() {
        storage.setAutoVanishOnJoin(uuid, true);
        storage.setAutoVanishOnJoin(uuid, false);
        assertFalse(storage.getAutoVanishOnJoin(uuid));
    }

    @Test
    void autoVanishOnJoin_independentPerPlayer() {
        storage.setAutoVanishOnJoin(uuid, true);
        storage.setAutoVanishOnJoin(uuid2, false);
        assertTrue(storage.getAutoVanishOnJoin(uuid));
        assertFalse(storage.getAutoVanishOnJoin(uuid2));
    }

    // ── Storage: vanish history ──────────────────────────────────────────────

    @Test
    void addHistoryEntry_and_retrieve() {
        VanishHistoryEntry entry = VanishHistoryEntry.vanish(uuid, "Alice", "Server", "test");
        storage.addHistoryEntry(entry);
        List<VanishHistoryEntry> history = storage.getPlayerHistory(uuid, 1, 10);
        assertEquals(1, history.size());
        assertEquals(VanishHistoryEntry.Action.VANISH, history.get(0).getAction());
        assertEquals("Alice", history.get(0).getPlayerName());
    }

    @Test
    void historyEntries_orderMostRecentFirst() throws InterruptedException {
        VanishHistoryEntry older = VanishHistoryEntry.vanish(uuid, "A", "S", null);
        Thread.sleep(10);
        VanishHistoryEntry newer = VanishHistoryEntry.unvanish(uuid, "A", "S", null, 5000L);
        storage.addHistoryEntry(older);
        storage.addHistoryEntry(newer);

        List<VanishHistoryEntry> history = storage.getPlayerHistory(uuid, 1, 10);
        assertEquals(2, history.size());
        assertEquals(VanishHistoryEntry.Action.UNVANISH, history.get(0).getAction(),
                "Most recent (UNVANISH) should appear first");
    }

    @Test
    void getPlayerHistory_paginatesCorrectly() {
        for (int i = 0; i < 15; i++) {
            storage.addHistoryEntry(VanishHistoryEntry.vanish(uuid, "A", "S", null));
        }
        List<VanishHistoryEntry> page1 = storage.getPlayerHistory(uuid, 1, 10);
        List<VanishHistoryEntry> page2 = storage.getPlayerHistory(uuid, 2, 10);
        assertEquals(10, page1.size());
        assertEquals(5, page2.size());
    }

    @Test
    void getAllHistory_returnsEntriesFromMultiplePlayers() {
        storage.addHistoryEntry(VanishHistoryEntry.vanish(uuid, "A", "S", null));
        storage.addHistoryEntry(VanishHistoryEntry.vanish(uuid2, "B", "S", null));
        List<VanishHistoryEntry> all = storage.getAllHistory(1, 10);
        assertEquals(2, all.size());
    }

    @Test
    void getPlayerHistory_isolatedPerPlayer() {
        storage.addHistoryEntry(VanishHistoryEntry.vanish(uuid, "A", "S", null));
        storage.addHistoryEntry(VanishHistoryEntry.vanish(uuid2, "B", "S", null));
        List<VanishHistoryEntry> history = storage.getPlayerHistory(uuid, 1, 10);
        assertEquals(1, history.size());
        assertEquals("A", history.get(0).getPlayerName());
    }

    @Test
    void pruneHistory_zeroRetentionIsNoOp() {
        // pruneHistory(0) is a no-op — retentionDays <= 0 means "disabled", returns 0 removed
        storage.addHistoryEntry(VanishHistoryEntry.vanish(uuid, "A", "S", null));
        int removed = storage.pruneHistory(0);
        assertEquals(0, removed, "pruneHistory(0) should be a no-op and return 0");
        assertFalse(storage.getPlayerHistory(uuid, 1, 10).isEmpty(), "Entry should still exist after no-op prune");
    }

    @Test
    void pruneHistory_keepsFreshEntries() {
        storage.addHistoryEntry(VanishHistoryEntry.vanish(uuid, "A", "S", null));
        int pruned = storage.pruneHistory(30); // 30 days retention — nothing should be pruned
        assertEquals(0, pruned, "Fresh entries should not be pruned");
        assertFalse(storage.getPlayerHistory(uuid, 1, 10).isEmpty());
    }

    // ── Storage: vanish statistics ───────────────────────────────────────────

    @Test
    void getStats_defaultIsEmpty() {
        VanishStats stats = storage.getStats(uuid);
        assertEquals(0, stats.getTotalVanishTimeMs());
        assertEquals(0, stats.getVanishCount());
    }

    @Test
    void recordVanishSession_accumulatesTime() {
        storage.recordVanishSession(uuid, 60_000L);
        storage.recordVanishSession(uuid, 30_000L);
        VanishStats stats = storage.getStats(uuid);
        assertEquals(90_000L, stats.getTotalVanishTimeMs());
        assertEquals(2, stats.getVanishCount());
    }

    @Test
    void recordVanishSession_tracksLongestSession() {
        storage.recordVanishSession(uuid, 30_000L);
        storage.recordVanishSession(uuid, 120_000L);
        storage.recordVanishSession(uuid, 60_000L);
        VanishStats stats = storage.getStats(uuid);
        assertEquals(120_000L, stats.getLongestSessionMs());
    }

    @Test
    void recordVanishSession_isolatedPerPlayer() {
        storage.recordVanishSession(uuid, 100_000L);
        storage.recordVanishSession(uuid2, 200_000L);
        assertEquals(100_000L, storage.getStats(uuid).getTotalVanishTimeMs());
        assertEquals(200_000L, storage.getStats(uuid2).getTotalVanishTimeMs());
    }

    // ── Storage: rule presets ────────────────────────────────────────────────

    @Test
    void saveAndLoadRulePreset() {
        Map<String, Boolean> rules = Map.of("can_break_blocks", false, "mob_targeting", true);
        storage.saveRulePreset(uuid, "myPreset", rules);

        Map<String, Boolean> loaded = storage.loadRulePreset(uuid, "myPreset");
        assertNotNull(loaded);
        assertEquals(false, loaded.get("can_break_blocks"));
        assertEquals(true, loaded.get("mob_targeting"));
    }

    @Test
    void listRulePresets_returnsAllSaved() {
        storage.saveRulePreset(uuid, "preset1", Map.of("mob_targeting", true));
        storage.saveRulePreset(uuid, "preset2", Map.of("mob_targeting", false));
        Set<String> presets = storage.listRulePresets(uuid);
        assertTrue(presets.contains("preset1"));
        assertTrue(presets.contains("preset2"));
    }

    @Test
    void deleteRulePreset_removesIt() {
        storage.saveRulePreset(uuid, "toDelete", Map.of("mob_targeting", true));
        storage.deleteRulePreset(uuid, "toDelete");
        Map<String, Boolean> loaded = storage.loadRulePreset(uuid, "toDelete");
        assertTrue(loaded == null || loaded.isEmpty());
    }

    @Test
    void loadRulePreset_nonExistent_returnsNullOrEmpty() {
        Map<String, Boolean> result = storage.loadRulePreset(uuid, "nonexistent");
        assertTrue(result == null || result.isEmpty());
    }

    @Test
    void rulePresets_isolatedPerPlayer() {
        storage.saveRulePreset(uuid, "shared", Map.of("mob_targeting", true));
        // uuid2 should not see uuid's preset
        Set<String> presets2 = storage.listRulePresets(uuid2);
        assertFalse(presets2.contains("shared"),
                "Player 2 should not see Player 1's presets");
    }

    @Test
    void saveRulePreset_overwritesExisting() {
        storage.saveRulePreset(uuid, "p", Map.of("mob_targeting", false));
        storage.saveRulePreset(uuid, "p", Map.of("mob_targeting", true));
        Map<String, Boolean> loaded = storage.loadRulePreset(uuid, "p");
        assertEquals(true, loaded.get("mob_targeting"));
    }

    // ── VanishZone geometry ──────────────────────────────────────────────────

    @Test
    void vanishZone_contains_locationInside() {
        World world = server.addSimpleWorld("world");
        VanishZone zone = new VanishZone("test", "world", 0, 64, 0, 10.0, true);
        Location inside = new Location(world, 5, 64, 5);
        assertTrue(zone.contains(inside));
    }

    @Test
    void vanishZone_contains_locationOutside() {
        World world = server.addSimpleWorld("world2");
        VanishZone zone = new VanishZone("test", "world2", 0, 64, 0, 10.0, true);
        Location outside = new Location(world, 20, 64, 20);
        assertFalse(zone.contains(outside));
    }

    @Test
    void vanishZone_contains_exactBoundary() {
        World world = server.addSimpleWorld("world3");
        VanishZone zone = new VanishZone("test", "world3", 0, 64, 0, 10.0, true);
        // Exactly at radius distance
        Location boundary = new Location(world, 10, 64, 0);
        // Boundary is <= radius, so should be inside
        assertTrue(zone.contains(boundary));
    }

    @Test
    void vanishZone_contains_differentWorld_returnsFalse() {
        World worldA = server.addSimpleWorld("worldA");
        World worldB = server.addSimpleWorld("worldB");
        VanishZone zone = new VanishZone("test", "worldA", 0, 64, 0, 50.0, true);
        Location wrongWorld = new Location(worldB, 0, 64, 0);
        assertFalse(zone.contains(wrongWorld));
    }

    @Test
    void vanishZone_getName_isForceUnvanish() {
        VanishZone zone = new VanishZone("arena", "world", 10, 64, 20, 5.0, true);
        assertEquals("arena", zone.getName());
        assertTrue(zone.isForceUnvanish());
    }

    // ── Vanish reason tracking in plugin ────────────────────────────────────

    @Test
    void vanishReason_setViaReason_overload() {
        PlayerMock player = server.addPlayer();
        plugin.vanishPlayer(player, player, "investigating");
        assertEquals("investigating", plugin.getVanishReason(player.getUniqueId()));
    }

    @Test
    void vanishReason_clearedOnUnvanish() {
        PlayerMock player = server.addPlayer();
        plugin.vanishPlayer(player, player, "some reason");
        plugin.unvanishPlayer(player, player);
        assertNull(plugin.getVanishReason(player.getUniqueId()));
    }

    @Test
    void vanishReason_nullIfNoReason() {
        PlayerMock player = server.addPlayer();
        plugin.vanishPlayer(player, player);
        assertNull(plugin.getVanishReason(player.getUniqueId()));
    }

    @Test
    void vanishReason_setVanishReason_updatesOnFly() {
        PlayerMock player = server.addPlayer();
        plugin.vanishPlayer(player, player);
        plugin.setVanishReason(player.getUniqueId(), "late reason");
        assertEquals("late reason", plugin.getVanishReason(player.getUniqueId()));
    }

    @Test
    void vanishReason_setVanishReason_clearWithNull() {
        PlayerMock player = server.addPlayer();
        plugin.vanishPlayer(player, player, "reason");
        plugin.setVanishReason(player.getUniqueId(), null);
        assertNull(plugin.getVanishReason(player.getUniqueId()));
    }

    // ── Incognito mode state ─────────────────────────────────────────────────

    @Test
    void incognito_defaultNotActive() {
        PlayerMock player = server.addPlayer();
        assertFalse(plugin.isIncognito(player));
        assertNull(plugin.getIncognitoName(player.getUniqueId()));
    }

    @Test
    void incognito_setAndRetrieveFakeName() {
        PlayerMock player = server.addPlayer();
        plugin.incognitoNames.put(player.getUniqueId(), "FakeSteve");
        assertTrue(plugin.isIncognito(player));
        assertEquals("FakeSteve", plugin.getIncognitoName(player.getUniqueId()));
    }

    @Test
    void incognito_removedOnCleanup() {
        PlayerMock player = server.addPlayer();
        plugin.incognitoNames.put(player.getUniqueId(), "FakeAlex");
        plugin.cleanupPlayerCache(player.getUniqueId());
        assertFalse(plugin.isIncognito(player));
    }

    @Test
    void incognito_independentPerPlayer() {
        PlayerMock p1 = server.addPlayer();
        PlayerMock p2 = server.addPlayer();
        plugin.incognitoNames.put(p1.getUniqueId(), "FakeOne");
        assertFalse(plugin.isIncognito(p2));
    }

    // ── Vanish start time tracking ───────────────────────────────────────────

    @Test
    void vanishStartTime_setOnVanish() {
        PlayerMock player = server.addPlayer();
        long before = System.currentTimeMillis();
        plugin.vanishPlayer(player, player);
        long after = System.currentTimeMillis();
        long startTime = plugin.getVanishStartTime(player.getUniqueId());
        assertTrue(startTime >= before, "Start time should be >= before vanish");
        assertTrue(startTime <= after, "Start time should be <= after vanish");
    }

    @Test
    void vanishStartTime_clearedOnUnvanish() {
        PlayerMock player = server.addPlayer();
        plugin.vanishPlayer(player, player);
        plugin.unvanishPlayer(player, player);
        assertEquals(-1L, plugin.getVanishStartTime(player.getUniqueId()));
    }

    @Test
    void vanishStartTime_notVanished_returnsMinusOne() {
        PlayerMock player = server.addPlayer();
        assertEquals(-1L, plugin.getVanishStartTime(player.getUniqueId()));
    }

    // ── getTimedRemaining stub ───────────────────────────────────────────────

    @Test
    void getTimedRemaining_alwaysMinusOne() {
        PlayerMock player = server.addPlayer();
        plugin.vanishPlayer(player, player);
        assertEquals(-1L, plugin.getTimedRemaining(player.getUniqueId()),
                "Timed vanish not yet implemented — should return -1");
    }
}
