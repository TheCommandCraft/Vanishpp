package net.thecommandcraft.vanishpp.config;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuleManagerTest {

    private ServerMock server;
    private Vanishpp plugin;
    private RuleManager rules;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        rules = plugin.getRuleManager();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -------------------------------------------------------------------------
    // Hard defaults
    // -------------------------------------------------------------------------

    @Test
    void testHardDefault_canBreakBlocks_isFalse() {
        // Clear any config overrides
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_BREAK_BLOCKS));
    }

    @Test
    void testHardDefault_canPlaceBlocks_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_PLACE_BLOCKS));
    }

    @Test
    void testHardDefault_canHitEntities_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_HIT_ENTITIES));
    }

    @Test
    void testHardDefault_canPickupItems_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_PICKUP_ITEMS));
    }

    @Test
    void testHardDefault_canDropItems_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_DROP_ITEMS));
    }

    @Test
    void testHardDefault_canTriggerPhysical_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_TRIGGER_PHYSICAL));
    }

    @Test
    void testHardDefault_canInteract_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_INTERACT));
    }

    @Test
    void testHardDefault_canChat_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.CAN_CHAT));
    }

    @Test
    void testHardDefault_mobTargeting_isFalse() {
        plugin.getConfigManager().defaultRules.clear();
        assertFalse(rules.getRule(player, RuleManager.MOB_TARGETING));
    }

    @Test
    void testHardDefault_showNotifications_isTrue() {
        plugin.getConfigManager().defaultRules.clear();
        assertTrue(rules.getRule(player, RuleManager.SHOW_NOTIFICATIONS));
    }

    // -------------------------------------------------------------------------
    // Set and get
    // -------------------------------------------------------------------------

    @Test
    void testSetRule_thenGet() {
        rules.setRule(player, RuleManager.CAN_BREAK_BLOCKS, true);
        assertTrue(rules.getRule(player, RuleManager.CAN_BREAK_BLOCKS));

        rules.setRule(player, RuleManager.CAN_BREAK_BLOCKS, false);
        assertFalse(rules.getRule(player, RuleManager.CAN_BREAK_BLOCKS));
    }

    @Test
    void testSetRule_persistsInStorage() {
        rules.setRule(player, RuleManager.CAN_CHAT, true);
        // Verify directly in storage
        java.util.Map<String, Object> stored = plugin.getStorageProvider().getRules(player.getUniqueId());
        assertEquals(true, stored.get(RuleManager.CAN_CHAT));
    }

    // -------------------------------------------------------------------------
    // Priority: player override > config default > hard default
    // -------------------------------------------------------------------------

    @Test
    void testConfigDefault_overridesHardDefault() {
        plugin.getConfigManager().defaultRules.clear();
        // CAN_INTERACT hard default is true; override with false via config default
        plugin.getConfigManager().defaultRules.put(RuleManager.CAN_INTERACT, false);
        assertFalse(rules.getRule(player, RuleManager.CAN_INTERACT),
                "Config default must override hard default");
    }

    @Test
    void testPlayerOverride_overridesConfigDefault() {
        plugin.getConfigManager().defaultRules.put(RuleManager.CAN_CHAT, true);
        rules.setRule(player, RuleManager.CAN_CHAT, false);
        assertFalse(rules.getRule(player, RuleManager.CAN_CHAT),
                "Player-specific rule must override config default");
    }

    @Test
    void testPlayerOverride_overridesHardDefault() {
        plugin.getConfigManager().defaultRules.clear();
        rules.setRule(player, RuleManager.CAN_DROP_ITEMS, true);
        assertTrue(rules.getRule(player, RuleManager.CAN_DROP_ITEMS),
                "Player-specific rule must override hard default");
    }

    // -------------------------------------------------------------------------
    // setAllRules
    // -------------------------------------------------------------------------

    @Test
    void testSetAllRules_setsAllExceptShowNotifications_toTrue() {
        rules.setAllRules(player, true);

        assertTrue(rules.getRule(player, RuleManager.CAN_BREAK_BLOCKS));
        assertTrue(rules.getRule(player, RuleManager.CAN_PLACE_BLOCKS));
        assertTrue(rules.getRule(player, RuleManager.CAN_HIT_ENTITIES));
        assertTrue(rules.getRule(player, RuleManager.CAN_PICKUP_ITEMS));
        assertTrue(rules.getRule(player, RuleManager.CAN_DROP_ITEMS));
        assertTrue(rules.getRule(player, RuleManager.CAN_TRIGGER_PHYSICAL));
        assertTrue(rules.getRule(player, RuleManager.CAN_INTERACT));
        assertTrue(rules.getRule(player, RuleManager.CAN_CHAT));
        assertTrue(rules.getRule(player, RuleManager.MOB_TARGETING));
    }

    @Test
    void testSetAllRules_doesNotSetShowNotifications() {
        // Start with SHOW_NOTIFICATIONS = true (hard default)
        plugin.getConfigManager().defaultRules.clear();

        rules.setAllRules(player, false);

        // SHOW_NOTIFICATIONS should remain at its default (true), not be set to false
        assertTrue(rules.getRule(player, RuleManager.SHOW_NOTIFICATIONS),
                "setAllRules must not modify SHOW_NOTIFICATIONS");
    }

    @Test
    void testSetAllRules_toFalse() {
        // First set them all to true
        rules.setAllRules(player, true);
        // Then set all back to false
        rules.setAllRules(player, false);

        assertFalse(rules.getRule(player, RuleManager.CAN_BREAK_BLOCKS));
        assertFalse(rules.getRule(player, RuleManager.CAN_CHAT));
    }

    // -------------------------------------------------------------------------
    // getAvailableRules
    // -------------------------------------------------------------------------

    @Test
    void testGetAvailableRules_containsAllTwelveRules() {
        Set<String> available = rules.getAvailableRules();
        assertEquals(12, available.size(), "There should be exactly 12 available rules");
        assertTrue(available.contains(RuleManager.CAN_BREAK_BLOCKS));
        assertTrue(available.contains(RuleManager.CAN_PLACE_BLOCKS));
        assertTrue(available.contains(RuleManager.CAN_HIT_ENTITIES));
        assertTrue(available.contains(RuleManager.CAN_PICKUP_ITEMS));
        assertTrue(available.contains(RuleManager.CAN_DROP_ITEMS));
        assertTrue(available.contains(RuleManager.CAN_TRIGGER_PHYSICAL));
        assertTrue(available.contains(RuleManager.CAN_INTERACT));
        assertTrue(available.contains(RuleManager.CAN_THROW));
        assertTrue(available.contains(RuleManager.CAN_CHAT));
        assertTrue(available.contains(RuleManager.MOB_TARGETING));
        assertTrue(available.contains(RuleManager.SHOW_NOTIFICATIONS));
        assertTrue(available.contains(RuleManager.SPECTATOR_GAMEMODE));
    }

    // -------------------------------------------------------------------------
    // Unknown rule fallback
    // -------------------------------------------------------------------------

    @Test
    void testUnknownRule_returnsFalse() {
        assertFalse(rules.getRule(player, "nonexistent_rule"),
                "Unknown rule should fall back to false");
    }
}
