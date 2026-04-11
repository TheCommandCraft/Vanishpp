package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.config.PermissionManager;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandTest {

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

    // =========================================================================
    // /vanish
    // =========================================================================

    @Test
    void vanish_self_togglesVanish() {
        player.setOp(true);
        player.performCommand("vanish");
        assertTrue(plugin.isVanished(player));
        player.performCommand("vanish");
        assertFalse(plugin.isVanished(player));
    }

    @Test
    void vanish_self_deniedWithoutPermission() {
        player.setOp(false);
        player.performCommand("vanish");
        assertFalse(plugin.isVanished(player));
    }

    @Test
    void vanish_other_togglesTarget() {
        PlayerMock target = server.addPlayer();
        player.setOp(true); // has vanishpp.vanish.others

        player.performCommand("vanish " + target.getName());
        assertTrue(plugin.isVanished(target));
        assertFalse(plugin.isVanished(player), "Executor must not vanish themselves");
    }

    @Test
    void vanish_other_deniedWithoutOthersPerm() {
        PlayerMock target = server.addPlayer();
        player.setOp(false);

        player.performCommand("vanish " + target.getName());
        assertFalse(plugin.isVanished(target), "Target must not vanish without vanishpp.vanish.others");
    }

    @Test
    void vanish_other_playerNotFound_sendsMessage() {
        player.setOp(true);
        player.performCommand("vanish nonexistentXYZ");
        // Should not crash; just send "player not found" message
        // Verify via message queue (at least one message was sent)
        assertNotNull(player.nextComponentMessage(), "Should receive an error message when player not found");
    }

    @Test
    void vanish_unvanishOther_togglesBackToVisible() {
        PlayerMock target = server.addPlayer();
        player.setOp(true);

        player.performCommand("vanish " + target.getName()); // vanish
        assertTrue(plugin.isVanished(target));

        player.performCommand("vanish " + target.getName()); // unvanish
        assertFalse(plugin.isVanished(target));
    }

    // =========================================================================
    // /vlist
    // =========================================================================

    @Test
    void vlist_showsVanishedPlayer() {
        player.setOp(true);
        plugin.vanishPlayer(player, player);

        PlayerMock observer = server.addPlayer();
        observer.setOp(true);
        observer.performCommand("vlist");

        boolean found = false;
        net.kyori.adventure.text.Component msg;
        while ((msg = observer.nextComponentMessage()) != null) {
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(msg);
            if (text.contains(player.getName())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "/vlist must contain the vanished player's name");
    }

    @Test
    void vlist_emptyWhenNoVanishedPlayers() {
        player.setOp(true);
        player.performCommand("vlist");

        // Should send at least one message (the header/empty state)
        assertNotNull(player.nextComponentMessage(), "/vlist should still send a response when no players are vanished");
    }

    // =========================================================================
    // /vanishrules
    // =========================================================================

    @Test
    void vanishrules_setRule_toTrue() {
        player.setOp(true);
        player.performCommand("vanishrules can_chat true");
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT));
    }

    @Test
    void vanishrules_setRule_toFalse() {
        player.setOp(true);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_CHAT, true);
        player.performCommand("vanishrules can_chat false");
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT));
    }

    @Test
    void vanishrules_setAllTrue() {
        player.setOp(true);
        player.performCommand("vanishrules all true");

        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_BREAK_BLOCKS));
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT));
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_HIT_ENTITIES));
    }

    @Test
    void vanishrules_setAllFalse() {
        player.setOp(true);
        // First set all to true
        player.performCommand("vanishrules all true");
        player.performCommand("vanishrules all false");

        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.CAN_BREAK_BLOCKS));
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT));
    }

    @Test
    void vanishrules_invalidRuleName_sendsError() {
        player.setOp(true);
        player.performCommand("vanishrules nonexistent_rule true");
        // Should get an error message, not crash
        assertNotNull(player.nextComponentMessage(), "Should receive error for invalid rule name");
    }

    @Test
    void vanishrules_invalidValue_sendsError() {
        player.setOp(true);
        player.performCommand("vanishrules can_chat maybe");
        assertNotNull(player.nextComponentMessage(), "Should receive error for invalid value");
    }

    @Test
    void vanishrules_noArgs_sendsUsage() {
        player.setOp(true);
        player.performCommand("vanishrules");
        assertNotNull(player.nextComponentMessage(), "Should receive usage message with no args");
    }

    @Test
    void vanishrules_getRule_sendsCurrentValue() {
        player.setOp(true);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_CHAT, true);
        player.performCommand("vanishrules can_chat");
        assertNotNull(player.nextComponentMessage(), "Should receive current rule value");
    }

    @Test
    void vanishrules_setOther_withPermission() {
        PlayerMock target = server.addPlayer();
        player.setOp(true); // has vanishpp.rules.others

        player.performCommand("vanishrules " + target.getName() + " can_chat true");
        assertTrue(plugin.getRuleManager().getRule(target, RuleManager.CAN_CHAT));
    }

    @Test
    void vanishrules_setOther_deniedWithoutPermission() {
        PlayerMock target = server.addPlayer();
        player.setOp(false);

        boolean initial = plugin.getRuleManager().getRule(target, RuleManager.CAN_CHAT);
        player.performCommand("vanishrules " + target.getName() + " can_chat true");
        assertEquals(initial, plugin.getRuleManager().getRule(target, RuleManager.CAN_CHAT),
                "Setting rules for others must be denied without vanishpp.rules.others");
    }

    // =========================================================================
    // /vperms
    // =========================================================================

    @Test
    void vperms_deniedWithoutManagePerm() {
        player.setOp(false);
        PlayerMock target = server.addPlayer();
        player.performCommand("vperms " + target.getName() + " vanishpp.vanish set");
        // Must not grant the permission
        assertFalse(plugin.getPermissionManager().hasPermission(target.getUniqueId(), "vanishpp.vanish"));
    }

    @Test
    void vperms_set_grantsPermission() {
        player.setOp(true); // has vanishpp.manageperms
        PlayerMock target = server.addPlayer();

        player.performCommand("vperms " + target.getName() + " vanishpp.vanish set");
        assertTrue(plugin.getPermissionManager().hasPermission(target.getUniqueId(), "vanishpp.vanish"),
                "vperms set must grant the permission");
    }

    @Test
    void vperms_remove_revokesPermission() {
        player.setOp(true);
        PlayerMock target = server.addPlayer();
        plugin.getPermissionManager().addPermission(target.getUniqueId(), "vanishpp.vanish");

        player.performCommand("vperms " + target.getName() + " vanishpp.vanish remove");
        assertFalse(plugin.getPermissionManager().hasPermission(target.getUniqueId(), "vanishpp.vanish"),
                "vperms remove must revoke the permission");
    }

    @Test
    void vperms_get_has_sendsPositiveMessage() {
        player.setOp(true);
        PlayerMock target = server.addPlayer();
        plugin.getPermissionManager().addPermission(target.getUniqueId(), "vanishpp.vanish");

        player.performCommand("vperms " + target.getName() + " vanishpp.vanish get");
        // Should respond with a message (has permission)
        assertNotNull(player.nextComponentMessage());
    }

    @Test
    void vperms_get_doesNotHave_sendsNegativeMessage() {
        player.setOp(true);
        PlayerMock target = server.addPlayer();
        // No permission granted

        player.performCommand("vperms " + target.getName() + " vanishpp.vanish get");
        assertNotNull(player.nextComponentMessage());
    }

    @Test
    void vperms_reload_sendsConfirmation() {
        player.setOp(true);
        player.performCommand("vperms reload");
        assertNotNull(player.nextComponentMessage(), "vperms reload must send a confirmation message");
    }

    @Test
    void vperms_invalidPermission_sendsError() {
        player.setOp(true);
        PlayerMock target = server.addPlayer();
        player.performCommand("vperms " + target.getName() + " vanishpp.nonexistent set");
        assertNotNull(player.nextComponentMessage(), "Should receive error for invalid permission");
    }

    @Test
    void vperms_missingArgs_sendsUsage() {
        player.setOp(true);
        player.performCommand("vperms");
        assertNotNull(player.nextComponentMessage(), "Should receive usage message with wrong arg count");
    }

    // =========================================================================
    // /vanishreload
    // =========================================================================

    @Test
    void vanishreload_doesNotCrash() {
        player.setOp(true);
        assertDoesNotThrow(() -> player.performCommand("vanishreload"),
                "/vanishreload must not throw");
    }

    // =========================================================================
    // /vack
    // =========================================================================

    @Test
    void vack_migration_acknowledgesMigration() {
        player.setOp(true);
        // Mark as migrated this boot so the report is sendable
        // Directly acknowledge via the command
        player.performCommand("vack migration");
        assertTrue(plugin.getStorageProvider().hasAcknowledged(player.getUniqueId(),
                "migration_v" + plugin.getConfigManager().getLatestVersion()),
                "vack migration must record the acknowledgement");
    }

    @Test
    void vack_disableHiding_setsConfigFalse() {
        player.setOp(true); // gives vanishpp.config
        plugin.getConfigManager().hideFromPluginList = true;
        player.performCommand("vack disable_hiding");
        assertFalse(plugin.getConfigManager().hideFromPluginList,
                "vack disable_hiding must set hide-from-plugin-list to false in config");
    }

    // =========================================================================
    // /vanishconfig
    // =========================================================================

    @Test
    void vanishconfig_setsBoolean() {
        player.setOp(true);
        player.performCommand("vconfig invisibility-features.god-mode false");
        assertFalse(plugin.getConfigManager().godMode, "vconfig must update the config value in memory");
    }

    @Test
    void vanishconfig_setBooleanTrue_thenCheckMemory() {
        player.setOp(true);
        plugin.getConfigManager().godMode = false;
        player.performCommand("vconfig invisibility-features.god-mode true");
        assertTrue(plugin.getConfigManager().godMode, "vconfig must set god-mode to true");
    }

    @Test
    void vanishconfig_deniedWithoutPermission() {
        player.setOp(false);
        plugin.getConfigManager().godMode = true;
        player.performCommand("vconfig invisibility-features.god-mode false");
        // Without permission the value must not change
        assertTrue(plugin.getConfigManager().godMode, "vconfig must be denied without permission");
    }

    // =========================================================================
    // /vanish — aliases
    // =========================================================================

    @Test
    void vanish_aliasV_works() {
        player.setOp(true);
        player.performCommand("v");
        assertTrue(plugin.isVanished(player), "Alias /v must toggle vanish");
    }

    @Test
    void vanish_aliasSV_works() {
        player.setOp(true);
        player.performCommand("sv");
        assertTrue(plugin.isVanished(player), "Alias /sv must toggle vanish");
    }

    // =========================================================================
    // /vanishrules — additional rules
    // =========================================================================

    @Test
    void vanishrules_canHitEntities_true() {
        player.setOp(true);
        player.performCommand("vanishrules can_hit_entities true");
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_HIT_ENTITIES));
    }

    @Test
    void vanishrules_canHitEntities_false() {
        player.setOp(true);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_HIT_ENTITIES, true);
        player.performCommand("vanishrules can_hit_entities false");
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.CAN_HIT_ENTITIES));
    }

    @Test
    void vanishrules_canThrow_true() {
        player.setOp(true);
        player.performCommand("vanishrules can_throw true");
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_THROW));
    }

    @Test
    void vanishrules_canThrow_false() {
        player.setOp(true);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_THROW, true);
        player.performCommand("vanishrules can_throw false");
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.CAN_THROW));
    }

    @Test
    void vanishrules_canTriggerPhysical_true() {
        player.setOp(true);
        player.performCommand("vanishrules can_trigger_physical true");
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_TRIGGER_PHYSICAL));
    }

    @Test
    void vanishrules_mobTargeting_true() {
        player.setOp(true);
        player.performCommand("vanishrules mob_targeting true");
        assertTrue(plugin.getRuleManager().getRule(player, RuleManager.MOB_TARGETING));
    }

    @Test
    void vanishrules_mobTargeting_false() {
        player.setOp(true);
        plugin.getRuleManager().setRule(player, RuleManager.MOB_TARGETING, true);
        player.performCommand("vanishrules mob_targeting false");
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.MOB_TARGETING));
    }

    @Test
    void vanishrules_spectatorGamemode_false() {
        player.setOp(true);
        player.performCommand("vanishrules spectator_gamemode false");
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.SPECTATOR_GAMEMODE));
    }

    @Test
    void vanishrules_showNotifications_false() {
        player.setOp(true);
        player.performCommand("vanishrules show_notifications false");
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.SHOW_NOTIFICATIONS));
    }

    // =========================================================================
    // /vlist — multiple vanished players
    // =========================================================================

    @Test
    void vlist_showsAllVanishedPlayers() {
        PlayerMock vanished1 = server.addPlayer();
        PlayerMock vanished2 = server.addPlayer();
        vanished1.setOp(true);
        vanished2.setOp(true);
        plugin.vanishPlayer(vanished1, vanished1);
        plugin.vanishPlayer(vanished2, vanished2);

        player.setOp(true);
        player.performCommand("vlist");

        StringBuilder allText = new StringBuilder();
        net.kyori.adventure.text.Component msg;
        while ((msg = player.nextComponentMessage()) != null) {
            allText.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(msg));
        }
        String text = allText.toString();
        assertTrue(text.contains(vanished1.getName()), "/vlist must show first vanished player");
        assertTrue(text.contains(vanished2.getName()), "/vlist must show second vanished player");
    }

    // =========================================================================
    // /vanishreload — preserves existing vanish state
    // =========================================================================

    @Test
    void vanishreload_preservesVanishState() {
        player.setOp(true);
        plugin.vanishPlayer(player, player);
        assertTrue(plugin.isVanished(player));

        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        admin.performCommand("vanishreload");

        assertTrue(plugin.isVanished(player), "Vanish state must survive a config reload");
    }

    @Test
    void vanishreload_sendsConfirmation() {
        player.setOp(true);
        player.performCommand("vanishreload");
        assertNotNull(player.nextComponentMessage(), "/vanishreload must send a confirmation message");
    }

    // =========================================================================
    // /vanish — idempotency / toggle consistency
    // =========================================================================

    @Test
    void vanish_toggleOnOff_multipleRounds() {
        player.setOp(true);
        for (int i = 0; i < 3; i++) {
            player.performCommand("vanish");
            assertTrue(plugin.isVanished(player), "Player must be vanished on odd toggle (round " + (i + 1) + ")");
            player.performCommand("vanish");
            assertFalse(plugin.isVanished(player), "Player must be visible on even toggle (round " + (i + 1) + ")");
        }
    }

    @Test
    void vanish_selfAndOther_independentStates() {
        PlayerMock executor = server.addPlayer();
        executor.setOp(true);
        PlayerMock target = server.addPlayer();
        target.setOp(true);

        executor.performCommand("vanish");           // executor vanishes
        executor.performCommand("vanish " + target.getName()); // target vanishes

        assertTrue(plugin.isVanished(executor), "Executor must be vanished");
        assertTrue(plugin.isVanished(target), "Target must be vanished");

        executor.performCommand("vanish");           // executor unvanishes
        assertFalse(plugin.isVanished(executor), "Executor must be unvanished");
        assertTrue(plugin.isVanished(target), "Target must remain vanished");
    }
}
