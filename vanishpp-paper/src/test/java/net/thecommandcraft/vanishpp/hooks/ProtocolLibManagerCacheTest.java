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
 * Covers ProtocolLibManager's entityId -> UUID cache, the fix for an intermittent
 * non-seer leak (e.g. a floating head/equipment on an otherwise-hidden vanished player):
 * every packet-filtering decision resolves "is this entityId a vanished player" through
 * this cache instead of ProtocolLib's live, world-scoped getEntityFromID lookup, which
 * could transiently return null/the wrong entity around spawn/despawn timing and let a
 * packet through unfiltered. See git history a9267db/47f90dd for the prior art this
 * restores (it existed on a since-abandoned legacy source tree, but was never carried
 * over when vanishpp-paper was split out as its own module).
 */
class ProtocolLibManagerCacheTest {

    private ServerMock server;
    private Vanishpp plugin;
    private ProtocolLibManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        manager = new ProtocolLibManager(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void isVanishedEntity_true_whenCachedAndVanished() {
        PlayerMock player = server.addPlayer();
        plugin.applyVanishEffects(player);
        manager.cachePlayer(player);

        assertTrue(manager.isVanishedEntity(player.getEntityId()),
                "a cached, vanished player's entityId must resolve as vanished");
    }

    @Test
    void isVanishedEntity_false_whenCachedButNotVanished() {
        PlayerMock player = server.addPlayer();
        manager.cachePlayer(player);

        assertFalse(manager.isVanishedEntity(player.getEntityId()),
                "a cached, non-vanished player's entityId must not resolve as vanished");
    }

    @Test
    void isVanishedEntity_false_whenNeverCached() {
        // Simulates the exact failure mode this fix replaces: a lookup that can't resolve
        // the entity must fail closed for THIS check (i.e. not claim vanished), which is
        // safe here because unknown ids simply aren't touched by the caller's cancellation
        // logic at all - unlike the old getEntityFromID path, there is no ambiguous "maybe
        // this id belongs to a vanished player, maybe the lookup just failed" case anymore.
        assertFalse(manager.isVanishedEntity(9999),
                "an entityId that was never cached must not resolve as vanished");
    }

    @Test
    void isVanishedEntity_false_afterUncache() {
        PlayerMock player = server.addPlayer();
        plugin.applyVanishEffects(player);
        manager.cachePlayer(player);
        assertTrue(manager.isVanishedEntity(player.getEntityId()));

        manager.uncachePlayer(player);

        assertFalse(manager.isVanishedEntity(player.getEntityId()),
                "an uncached (quit) player's entityId must stop resolving as vanished");
    }

    @Test
    void isVanishedEntity_reflectsUnvanish_withoutRecaching() {
        // The cache only ever needs to be touched on join/quit - unvanishing must be
        // reflected purely through plugin.isVanished(uuid) on the next lookup, with no
        // cache invalidation of its own.
        PlayerMock player = server.addPlayer();
        plugin.applyVanishEffects(player);
        manager.cachePlayer(player);
        assertTrue(manager.isVanishedEntity(player.getEntityId()));

        plugin.removeVanishEffects(player);

        assertFalse(manager.isVanishedEntity(player.getEntityId()),
                "unvanishing must be reflected immediately without touching the entityId cache");
    }
}
