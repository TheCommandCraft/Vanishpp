package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventListenerTest {

    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock player;
    private World world;
    private Location loc;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        player = server.addPlayer();
        world = server.addSimpleWorld("world");
        loc = new Location(world, 0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -------------------------------------------------------------------------
    // God Mode — EntityDamageEvent
    // -------------------------------------------------------------------------

    @Test
    void godMode_cancelsEntityDamage_whenVanished() {
        plugin.getConfigManager().godMode = true;
        plugin.applyVanishEffects(player);

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Damage must be cancelled in god mode while vanished");
    }

    @Test
    void godMode_doesNotCancelDamage_whenNotVanished() {
        plugin.getConfigManager().godMode = true;
        // Player is NOT vanished

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Damage must not be cancelled when player is not vanished");
    }

    @Test
    void godMode_doesNotCancelDamage_whenGodModeDisabled() {
        plugin.getConfigManager().godMode = false;
        plugin.applyVanishEffects(player);

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Damage must not be cancelled when godMode=false");
    }

    // -------------------------------------------------------------------------
    // Hunger — FoodLevelChangeEvent
    // -------------------------------------------------------------------------

    @Test
    void hunger_cancelsFoodChange_whenVanished() {
        plugin.getConfigManager().disableHunger = true;
        plugin.applyVanishEffects(player);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Food level change must be cancelled for vanished player with disableHunger=true");
    }

    @Test
    void hunger_doesNotCancelFoodChange_whenNotVanished() {
        plugin.getConfigManager().disableHunger = true;

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Food level change must not be cancelled for a non-vanished player");
    }

    @Test
    void hunger_doesNotCancelFoodChange_whenDisableHungerOff() {
        plugin.getConfigManager().disableHunger = false;
        plugin.applyVanishEffects(player);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Food level change must not be cancelled when disableHunger=false");
    }

    // -------------------------------------------------------------------------
    // Block Break — CAN_BREAK_BLOCKS rule
    // -------------------------------------------------------------------------

    @Test
    void blockBreak_cancelled_whenRuleIsFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_BREAK_BLOCKS, false);

        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Block break must be cancelled when CAN_BREAK_BLOCKS=false");
    }

    @Test
    void blockBreak_allowed_whenRuleIsTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_BREAK_BLOCKS, true);

        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Block break must be allowed when CAN_BREAK_BLOCKS=true");
    }

    @Test
    void blockBreak_notCancelled_whenPlayerNotVanished() {
        // Player is NOT vanished; rule is irrelevant
        plugin.getRuleManager().setRule(player, RuleManager.CAN_BREAK_BLOCKS, false);

        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Block break must not be cancelled for a non-vanished player");
    }

    // -------------------------------------------------------------------------
    // Block Place — CAN_PLACE_BLOCKS rule
    // -------------------------------------------------------------------------

    @Test
    void blockPlace_cancelled_whenRuleIsFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_PLACE_BLOCKS, false);

        Block block = world.getBlockAt(1, 64, 0);
        Block against = world.getBlockAt(0, 64, 0);
        BlockState replacedState = block.getState();
        ItemStack hand = new ItemStack(Material.STONE);

        BlockPlaceEvent event = new BlockPlaceEvent(block, replacedState, against, hand,
                player, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Block place must be cancelled when CAN_PLACE_BLOCKS=false");
    }

    @Test
    void blockPlace_allowed_whenRuleIsTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_PLACE_BLOCKS, true);

        Block block = world.getBlockAt(1, 64, 0);
        Block against = world.getBlockAt(0, 64, 0);
        BlockState replacedState = block.getState();
        ItemStack hand = new ItemStack(Material.STONE);

        BlockPlaceEvent event = new BlockPlaceEvent(block, replacedState, against, hand,
                player, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Block place must be allowed when CAN_PLACE_BLOCKS=true");
    }

    // -------------------------------------------------------------------------
    // Item Drop — CAN_DROP_ITEMS rule
    // -------------------------------------------------------------------------

    @Test
    void itemDrop_cancelled_whenRuleIsFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_DROP_ITEMS, false);

        org.bukkit.entity.Item item = world.dropItem(loc, new ItemStack(Material.STONE));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Item drop must be cancelled when CAN_DROP_ITEMS=false");
    }

    @Test
    void itemDrop_allowed_whenRuleIsTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_DROP_ITEMS, true);

        org.bukkit.entity.Item item = world.dropItem(loc, new ItemStack(Material.STONE));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Item drop must be allowed when CAN_DROP_ITEMS=true");
    }

    // -------------------------------------------------------------------------
    // Item Pickup — CAN_PICKUP_ITEMS rule
    // -------------------------------------------------------------------------

    @Test
    void itemPickup_cancelled_whenRuleIsFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_PICKUP_ITEMS, false);

        org.bukkit.entity.Item item = world.dropItem(loc, new ItemStack(Material.STONE));
        EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Item pickup must be cancelled when CAN_PICKUP_ITEMS=false");
    }

    @Test
    void itemPickup_allowed_whenRuleIsTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_PICKUP_ITEMS, true);

        org.bukkit.entity.Item item = world.dropItem(loc, new ItemStack(Material.STONE));
        EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Item pickup must be allowed when CAN_PICKUP_ITEMS=true");
    }

    @Test
    void itemPickup_notCancelled_whenPlayerNotVanished() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_PICKUP_ITEMS, false);

        org.bukkit.entity.Item item = world.dropItem(loc, new ItemStack(Material.STONE));
        EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Pickup rule must not block non-vanished players");
    }

    // -------------------------------------------------------------------------
    // Mob Targeting — EntityTargetLivingEntityEvent
    // -------------------------------------------------------------------------

    @Test
    void mobTargeting_cancelled_whenTargetIsVanished() {
        plugin.applyVanishEffects(player);

        LivingEntity mob = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(mob, player,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Mob targeting a vanished player must be cancelled");
    }

    @Test
    void mobTargeting_notCancelled_whenTargetIsNotVanished() {
        // Player is NOT vanished
        LivingEntity mob = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(mob, player,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Mob targeting a non-vanished player must not be cancelled");
    }

    @Test
    void mobTargeting_notCancelled_whenTargetIsNull() {
        // Mob clears target (null target) — must not crash
        LivingEntity mob = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(mob, null,
                EntityTargetEvent.TargetReason.FORGOT_TARGET);
        assertDoesNotThrow(() -> server.getPluginManager().callEvent(event),
                "Null target in mob targeting event must not throw");
    }

    // -------------------------------------------------------------------------
    // isVanished() API
    // -------------------------------------------------------------------------

    @Test
    void isVanished_trueAfterApplyVanishEffects() {
        plugin.applyVanishEffects(player);
        assertTrue(plugin.isVanished(player));
    }

    @Test
    void isVanished_falseAfterRemoveVanishEffects() {
        plugin.applyVanishEffects(player);
        plugin.removeVanishEffects(player);
        assertFalse(plugin.isVanished(player));
    }

    @Test
    void applyVanishEffects_setsMetadata() {
        plugin.applyVanishEffects(player);
        assertFalse(player.getMetadata("vanished").isEmpty());
        assertTrue(player.getMetadata("vanished").get(0).asBoolean());
    }

    @Test
    void removeVanishEffects_clearsMetadata() {
        plugin.applyVanishEffects(player);
        plugin.removeVanishEffects(player);
        assertTrue(player.getMetadata("vanished").isEmpty() ||
                !player.getMetadata("vanished").get(0).asBoolean());
    }

    // -------------------------------------------------------------------------
    // updateVanishVisibility
    // -------------------------------------------------------------------------

    @Test
    void updateVanishVisibility_hidesVanishedFromNonStaff() {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);

        plugin.applyVanishEffects(player);
        plugin.updateVanishVisibility(player);

        assertFalse(observer.canSee(player), "Non-staff must not see vanished player after updateVanishVisibility");
    }

    @Test
    void updateVanishVisibility_showsVanishedToStaff() {
        PlayerMock staff = server.addPlayer();
        staff.setOp(true); // has vanishpp.see

        plugin.applyVanishEffects(player);
        plugin.updateVanishVisibility(player);

        assertTrue(staff.canSee(player), "Staff (OP) must still see vanished player");
    }

    @Test
    void updateVanishVisibility_showsPlayerToAllAfterUnvanish() {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);

        plugin.applyVanishEffects(player);
        plugin.updateVanishVisibility(player);
        assertFalse(observer.canSee(player)); // sanity check

        plugin.removeVanishEffects(player);
        plugin.updateVanishVisibility(player);
        assertTrue(observer.canSee(player), "Player must be visible to everyone after unvanish");
    }
}
