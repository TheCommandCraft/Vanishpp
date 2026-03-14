package net.thecommandcraft.vanishpp.config;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PermissionManagerTest {

    private ServerMock server;
    private Vanishpp plugin;
    private PermissionManager perms;
    private UUID uuid;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        perms = plugin.getPermissionManager();
        player = server.addPlayer();
        uuid = player.getUniqueId();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -------------------------------------------------------------------------
    // Basic add / has / remove
    // -------------------------------------------------------------------------

    @Test
    void testAddPermission_thenHasPermission() {
        perms.addPermission(uuid, "vanishpp.vanish");
        assertTrue(perms.hasPermission(uuid, "vanishpp.vanish"));
    }

    @Test
    void testHasPermission_returnsFalseWithoutGrant() {
        assertFalse(perms.hasPermission(uuid, "vanishpp.vanish"));
    }

    @Test
    void testRemovePermission_removesIt() {
        perms.addPermission(uuid, "vanishpp.vanish");
        perms.removePermission(uuid, "vanishpp.vanish");
        assertFalse(perms.hasPermission(uuid, "vanishpp.vanish"));
    }

    @Test
    void testAddPermission_noDuplicatesStored() {
        perms.addPermission(uuid, "vanishpp.vanish");
        perms.addPermission(uuid, "vanishpp.vanish"); // Second add is a no-op
        perms.removePermission(uuid, "vanishpp.vanish");
        assertFalse(perms.hasPermission(uuid, "vanishpp.vanish"),
                "Permission should be fully removed after one remove call");
    }

    // -------------------------------------------------------------------------
    // Wildcard
    // -------------------------------------------------------------------------

    @Test
    void testWildcard_grantsAnyPermission() {
        perms.addPermission(uuid, "vanishpp.*");
        assertTrue(perms.hasPermission(uuid, "vanishpp.vanish"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.see"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.fly"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.manageperms"));
    }

    // -------------------------------------------------------------------------
    // Group inheritance: vanishpp.abilities
    // -------------------------------------------------------------------------

    @Test
    void testAbilitiesGroup_grantsSubPermissions() {
        perms.addPermission(uuid, "vanishpp.abilities");
        assertTrue(perms.hasPermission(uuid, "vanishpp.silentchest"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.chat"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.notarget"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.nohunger"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.nightvision"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.fly"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.no-raid"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.no-sculk"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.no-trample"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.join-vanished"));
    }

    @Test
    void testAbilitiesGroup_doesNotGrantManagementPerms() {
        perms.addPermission(uuid, "vanishpp.abilities");
        assertFalse(perms.hasPermission(uuid, "vanishpp.manageperms"),
                "vanishpp.abilities must not grant management-tier permissions");
    }

    // -------------------------------------------------------------------------
    // Group inheritance: vanishpp.management
    // -------------------------------------------------------------------------

    @Test
    void testManagementGroup_grantsSubPermissions() {
        perms.addPermission(uuid, "vanishpp.management");
        assertTrue(perms.hasPermission(uuid, "vanishpp.manageperms"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.ignorewarning"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.rules"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.rules.others"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.pickup"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.pickup.others"));
    }

    @Test
    void testManagementGroup_doesNotGrantCorePerms() {
        perms.addPermission(uuid, "vanishpp.management");
        assertFalse(perms.hasPermission(uuid, "vanishpp.vanish"),
                "vanishpp.management must not grant core vanish permission");
    }

    // -------------------------------------------------------------------------
    // Group inheritance: vanishpp.core
    // -------------------------------------------------------------------------

    @Test
    void testCoreGroup_grantsSubPermissions() {
        perms.addPermission(uuid, "vanishpp.core");
        assertTrue(perms.hasPermission(uuid, "vanishpp.vanish"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.vanish.others"));
        assertTrue(perms.hasPermission(uuid, "vanishpp.see"));
    }

    // -------------------------------------------------------------------------
    // Bukkit permission fallback
    // -------------------------------------------------------------------------

    @Test
    void testBukkitPermission_checkedFirst() {
        // Player is OP → has all Bukkit permissions
        player.setOp(true);
        // No custom file permission granted
        assertTrue(perms.hasPermission(player, "vanishpp.vanish"),
                "Bukkit (OP) permission must satisfy hasPermission(Player, ...)");
    }

    @Test
    void testBukkitPermission_nonOpWithoutCustom_returnsFalse() {
        player.setOp(false);
        assertFalse(perms.hasPermission(player, "vanishpp.vanish"));
    }

    @Test
    void testCustomPermission_satisfiesPlayerCheck() {
        player.setOp(false);
        perms.addPermission(uuid, "vanishpp.vanish");
        assertTrue(perms.hasPermission(player, "vanishpp.vanish"),
                "Custom file permission must satisfy hasPermission(Player, ...) even without Bukkit perm");
    }

    // -------------------------------------------------------------------------
    // canSee
    // -------------------------------------------------------------------------

    @Test
    void testCanSee_nonVanishedPlayerAlwaysVisible() {
        PlayerMock observer = server.addPlayer();
        // player is not vanished
        assertTrue(perms.canSee(observer, player));
    }

    @Test
    void testCanSee_vanishedPlayer_hiddenFromNonStaff() {
        plugin.vanishPlayer(player, player);
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        assertFalse(perms.canSee(observer, player),
                "Non-staff must not see a vanished player");
    }

    @Test
    void testCanSee_vanishedPlayer_visibleToStaff() {
        plugin.vanishPlayer(player, player);
        PlayerMock staff = server.addPlayer();
        staff.setOp(true); // OP has vanishpp.see
        assertTrue(perms.canSee(staff, player),
                "Staff (OP) must see a vanished player");
    }

    @Test
    void testCanSee_vanishedPlayer_visibleToCustomPermHolder() {
        plugin.vanishPlayer(player, player);
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        perms.addPermission(observer.getUniqueId(), "vanishpp.see");
        assertTrue(perms.canSee(observer, player),
                "Player with custom vanishpp.see must see a vanished player");
    }

    // -------------------------------------------------------------------------
    // Layered permissions
    // -------------------------------------------------------------------------

    @Test
    void testLayeredPerms_lowerSeeLevel_cannotSeeHigherVanishLevel() {
        plugin.getConfigManager().layeredPermsEnabled = true;
        plugin.getConfigManager().defaultVanishLevel = 1;
        plugin.getConfigManager().defaultSeeLevel = 1;
        plugin.getConfigManager().maxLevel = 5;

        // Vanish player at level 3
        player.addAttachment(plugin, "vanishpp.vanish.level.3", true);
        plugin.vanishPlayer(player, player);

        // Observer has see, but only at default level 1
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        perms.addPermission(observer.getUniqueId(), "vanishpp.see");

        assertFalse(perms.canSee(observer, player),
                "Observer at see level 1 must not see player vanished at level 3");
    }

    @Test
    void testLayeredPerms_equalLevel_canSee() {
        plugin.getConfigManager().layeredPermsEnabled = true;
        plugin.getConfigManager().defaultVanishLevel = 1;
        plugin.getConfigManager().defaultSeeLevel = 1;
        plugin.getConfigManager().maxLevel = 5;

        // Both at default level 1
        plugin.vanishPlayer(player, player);
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        perms.addPermission(observer.getUniqueId(), "vanishpp.see");

        assertTrue(perms.canSee(observer, player),
                "Observer at see level 1 should see player vanished at level 1");
    }
}
