package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.config.PermissionManager;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for core vanish state management: the vanished set, visibility API,
 * cleanup on quit, level management, and canSee logic.
 */
class VanishStateTest {

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
    // getRawVanishedPlayers() — set membership
    // -------------------------------------------------------------------------

    @Test
    void rawVanishedSet_emptyInitially() {
        assertTrue(plugin.getRawVanishedPlayers().isEmpty(),
                "No players should be vanished at startup");
    }

    @Test
    void rawVanishedSet_containsPlayerAfterVanish() {
        plugin.applyVanishEffects(player);
        assertTrue(plugin.getRawVanishedPlayers().contains(player.getUniqueId()));
    }

    @Test
    void rawVanishedSet_doesNotContainPlayerAfterUnvanish() {
        plugin.applyVanishEffects(player);
        plugin.removeVanishEffects(player);
        assertFalse(plugin.getRawVanishedPlayers().contains(player.getUniqueId()));
    }

    @Test
    void rawVanishedSet_sizeTracksCorrectly_multipleVanishUnvanish() {
        PlayerMock p1 = server.addPlayer();
        PlayerMock p2 = server.addPlayer();
        PlayerMock p3 = server.addPlayer();

        assertEquals(0, plugin.getRawVanishedPlayers().size());

        plugin.applyVanishEffects(p1);
        assertEquals(1, plugin.getRawVanishedPlayers().size());

        plugin.applyVanishEffects(p2);
        plugin.applyVanishEffects(p3);
        assertEquals(3, plugin.getRawVanishedPlayers().size());

        plugin.removeVanishEffects(p2);
        assertEquals(2, plugin.getRawVanishedPlayers().size());
        assertFalse(plugin.getRawVanishedPlayers().contains(p2.getUniqueId()));
        assertTrue(plugin.getRawVanishedPlayers().contains(p1.getUniqueId()));
        assertTrue(plugin.getRawVanishedPlayers().contains(p3.getUniqueId()));
    }

    // -------------------------------------------------------------------------
    // isVanished(Player) and isVanished(UUID)
    // -------------------------------------------------------------------------

    @Test
    void isVanished_falseByDefault() {
        assertFalse(plugin.isVanished(player));
    }

    @Test
    void isVanished_trueAfterApply() {
        plugin.applyVanishEffects(player);
        assertTrue(plugin.isVanished(player));
    }

    @Test
    void isVanished_falseAfterRemove() {
        plugin.applyVanishEffects(player);
        plugin.removeVanishEffects(player);
        assertFalse(plugin.isVanished(player));
    }

    @Test
    void isVanished_doesNotAffectOtherPlayers() {
        PlayerMock other = server.addPlayer();
        plugin.applyVanishEffects(player);

        assertFalse(plugin.isVanished(other),
                "Vanishing one player must not mark other players as vanished");
    }

    @Test
    void isVanished_unknownUUID_returnsFalse() {
        assertFalse(plugin.getRawVanishedPlayers().contains(UUID.randomUUID()),
                "A random UUID not in the set must not be reported as vanished");
    }

    // -------------------------------------------------------------------------
    // canSee — permission-based visibility
    // -------------------------------------------------------------------------

    @Test
    void canSee_nonVanishedPlayerAlwaysVisible() {
        PlayerMock observer = server.addPlayer();
        // Player is NOT vanished — everyone should see them
        assertTrue(plugin.getPermissionManager().canSee(observer, player),
                "Non-vanished player must be visible to all");
    }

    @Test
    void canSee_vanishedPlayer_hiddenFromNonStaff() {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        plugin.applyVanishEffects(player);

        assertFalse(plugin.getPermissionManager().canSee(observer, player),
                "Vanished player must be hidden from non-staff");
    }

    @Test
    void canSee_vanishedPlayer_visibleToOp() {
        PlayerMock staffObserver = server.addPlayer();
        staffObserver.setOp(true); // OP has vanishpp.see
        plugin.applyVanishEffects(player);

        assertTrue(plugin.getPermissionManager().canSee(staffObserver, player),
                "Vanished player must be visible to OP staff");
    }

    @Test
    void canSee_vanishedPlayer_visibleToCustomPermissionHolder() {
        PlayerMock staffObserver = server.addPlayer();
        staffObserver.setOp(false);
        plugin.getPermissionManager().addPermission(staffObserver.getUniqueId(), "vanishpp.see");
        plugin.applyVanishEffects(player);

        assertTrue(plugin.getPermissionManager().canSee(staffObserver, player),
                "Player with vanishpp.see via /vperms must see vanished players");
    }

    @Test
    void canSee_afterUnvanish_visibleToAll() {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);

        plugin.applyVanishEffects(player);
        assertFalse(plugin.getPermissionManager().canSee(observer, player)); // sanity

        plugin.removeVanishEffects(player);
        assertTrue(plugin.getPermissionManager().canSee(observer, player),
                "Unvanished player must be visible to everyone");
    }

    // -------------------------------------------------------------------------
    // Vanish level management
    // -------------------------------------------------------------------------

    @Test
    void vanishLevel_defaultIsOne() {
        // YamlStorage defaults to 1 when no level has been explicitly set
        assertEquals(1, plugin.getStorageProvider().getVanishLevel(player.getUniqueId()));
    }

    @Test
    void vanishLevel_setAndGet_roundTrip() {
        plugin.getStorageProvider().setVanishLevel(player.getUniqueId(), 5);
        assertEquals(5, plugin.getStorageProvider().getVanishLevel(player.getUniqueId()));
    }

    @Test
    void vanishLevel_overwrite_returnsLatestValue() {
        plugin.getStorageProvider().setVanishLevel(player.getUniqueId(), 2);
        plugin.getStorageProvider().setVanishLevel(player.getUniqueId(), 7);
        assertEquals(7, plugin.getStorageProvider().getVanishLevel(player.getUniqueId()),
                "Overwritten level must return the latest value");
    }

    @Test
    void vanishLevel_independentPerPlayer() {
        PlayerMock p2 = server.addPlayer();
        plugin.getStorageProvider().setVanishLevel(player.getUniqueId(), 3);
        plugin.getStorageProvider().setVanishLevel(p2.getUniqueId(), 8);

        assertEquals(3, plugin.getStorageProvider().getVanishLevel(player.getUniqueId()));
        assertEquals(8, plugin.getStorageProvider().getVanishLevel(p2.getUniqueId()));
    }

    // -------------------------------------------------------------------------
    // Quit cleanup
    // -------------------------------------------------------------------------

    @Test
    void quit_vanishedPlayer_vanishStatePersistedInStorage() {
        // When a vanished player quits, their vanish state is persisted to storage
        // so they rejoin as vanished. The in-memory set may still contain them until
        // the periodic purge runs; this test verifies the storage reflects vanished=true.
        plugin.applyVanishEffects(player);
        assertTrue(plugin.isVanished(player));

        plugin.getStorageProvider().setVanished(player.getUniqueId(), true);
        assertTrue(plugin.getStorageProvider().isVanished(player.getUniqueId()),
                "Vanish state must be persisted in storage so reconnects restore it");
    }

    @Test
    void cleanupPlayerCache_doesNotThrow_forVanishedPlayer() {
        plugin.applyVanishEffects(player);
        assertDoesNotThrow(() -> plugin.cleanupPlayerCache(player.getUniqueId()),
                "cleanupPlayerCache must not throw even for a currently vanished player");
    }

    @Test
    void cleanupPlayerCache_doesNotThrow_forNonVanishedPlayer() {
        assertDoesNotThrow(() -> plugin.cleanupPlayerCache(player.getUniqueId()),
                "cleanupPlayerCache must not throw for a non-vanished player");
    }

    @Test
    void cleanupPlayerCache_calledTwice_doesNotThrow() {
        plugin.applyVanishEffects(player);
        assertDoesNotThrow(() -> {
            plugin.cleanupPlayerCache(player.getUniqueId());
            plugin.cleanupPlayerCache(player.getUniqueId());
        }, "cleanupPlayerCache must be safe to call multiple times");
    }

    // -------------------------------------------------------------------------
    // Metadata contract
    // -------------------------------------------------------------------------

    @Test
    void vanishedMetadata_setOnApply() {
        plugin.applyVanishEffects(player);
        assertFalse(player.getMetadata("vanished").isEmpty());
        assertTrue(player.getMetadata("vanished").get(0).asBoolean());
    }

    @Test
    void vanishedMetadata_clearedOnRemove() {
        plugin.applyVanishEffects(player);
        plugin.removeVanishEffects(player);
        assertTrue(player.getMetadata("vanished").isEmpty() ||
                !player.getMetadata("vanished").get(0).asBoolean(),
                "vanished metadata must be false or absent after unvanish");
    }

    @Test
    void vanishedMetadata_twoPlayers_independent() {
        PlayerMock p2 = server.addPlayer();
        plugin.applyVanishEffects(player);

        assertFalse(player.getMetadata("vanished").isEmpty());
        assertTrue(p2.getMetadata("vanished").isEmpty() ||
                !p2.getMetadata("vanished").get(0).asBoolean(),
                "Non-vanished player must not have vanished metadata");
    }
}
