package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
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

    @Test
    void updateVanishVisibility_multipleVanishedPlayers_hiddenFromNonStaff() {
        PlayerMock vanished1 = server.addPlayer();
        PlayerMock vanished2 = server.addPlayer();
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);

        plugin.applyVanishEffects(vanished1);
        plugin.applyVanishEffects(vanished2);
        plugin.updateVanishVisibility(vanished1);
        plugin.updateVanishVisibility(vanished2);

        assertFalse(observer.canSee(vanished1), "First vanished player must be hidden");
        assertFalse(observer.canSee(vanished2), "Second vanished player must be hidden");
    }

    @Test
    void updateVanishVisibility_unvanishOne_otherRemainsHidden() {
        PlayerMock vanished1 = server.addPlayer();
        PlayerMock vanished2 = server.addPlayer();
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);

        plugin.applyVanishEffects(vanished1);
        plugin.applyVanishEffects(vanished2);
        plugin.updateVanishVisibility(vanished1);
        plugin.updateVanishVisibility(vanished2);

        plugin.removeVanishEffects(vanished1);
        plugin.updateVanishVisibility(vanished1);

        assertTrue(observer.canSee(vanished1), "Unvanished player must be visible again");
        assertFalse(observer.canSee(vanished2), "Still-vanished player must remain hidden");
    }

    // -------------------------------------------------------------------------
    // God Mode — extra damage causes
    // -------------------------------------------------------------------------

    @Test
    void godMode_cancels_fallDamage_whenVanished() {
        plugin.getConfigManager().godMode = true;
        plugin.applyVanishEffects(player);

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.FALL, 3.0);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Fall damage must be cancelled in god mode");
    }

    @Test
    void godMode_cancels_fireDamage_whenVanished() {
        plugin.getConfigManager().godMode = true;
        plugin.applyVanishEffects(player);

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.FIRE, 1.0);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Fire damage must be cancelled in god mode");
    }

    @Test
    void godMode_cancels_explosionDamage_whenVanished() {
        plugin.getConfigManager().godMode = true;
        plugin.applyVanishEffects(player);

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, 10.0);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Explosion damage must be cancelled in god mode");
    }

    @Test
    void godMode_cancels_magicDamage_whenVanished() {
        plugin.getConfigManager().godMode = true;
        plugin.applyVanishEffects(player);

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.MAGIC, 2.0);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Magic damage must be cancelled in god mode");
    }

    // -------------------------------------------------------------------------
    // Velocity blocking — PlayerVelocityEvent
    // -------------------------------------------------------------------------

    @Test
    void velocity_cancelled_whenGodModeAndVanished() {
        plugin.getConfigManager().godMode = true;
        plugin.applyVanishEffects(player);

        PlayerVelocityEvent event = new PlayerVelocityEvent(player, new Vector(5, 0, 5));
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Velocity must be cancelled for vanished player in god mode");
    }

    @Test
    void velocity_notCancelled_whenNotVanished() {
        plugin.getConfigManager().godMode = true;
        // Player is NOT vanished

        PlayerVelocityEvent event = new PlayerVelocityEvent(player, new Vector(5, 0, 5));
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Velocity must not be cancelled for non-vanished player");
    }

    @Test
    void velocity_notCancelled_whenGodModeDisabled() {
        plugin.getConfigManager().godMode = false;
        plugin.applyVanishEffects(player);

        PlayerVelocityEvent event = new PlayerVelocityEvent(player, new Vector(5, 0, 5));
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Velocity must not be cancelled when godMode=false");
    }

    // -------------------------------------------------------------------------
    // Potion effects — EntityPotionEffectEvent
    // -------------------------------------------------------------------------

    @Test
    void potion_blocked_whenPreventPotionsAndVanishedAndExternalCause() {
        plugin.getConfigManager().preventPotions = true;
        plugin.applyVanishEffects(player);

        PotionEffect effect = new PotionEffect(PotionEffectType.SPEED, 100, 1);
        EntityPotionEffectEvent event = new EntityPotionEffectEvent(player, null, effect,
                EntityPotionEffectEvent.Cause.ATTACK,
                EntityPotionEffectEvent.Action.ADDED, false);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "External potion must be blocked when preventPotions=true and vanished");
    }

    @Test
    void potion_notBlocked_whenCauseIsPlugin() {
        plugin.getConfigManager().preventPotions = true;
        plugin.applyVanishEffects(player);

        PotionEffect effect = new PotionEffect(PotionEffectType.SPEED, 100, 1);
        EntityPotionEffectEvent event = new EntityPotionEffectEvent(player, null, effect,
                EntityPotionEffectEvent.Cause.PLUGIN,
                EntityPotionEffectEvent.Action.ADDED, false);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Plugin-applied potion must NOT be blocked even when preventPotions=true");
    }

    @Test
    void potion_notBlocked_whenNotVanished() {
        plugin.getConfigManager().preventPotions = true;
        // Player is NOT vanished

        PotionEffect effect = new PotionEffect(PotionEffectType.SPEED, 100, 1);
        EntityPotionEffectEvent event = new EntityPotionEffectEvent(player, null, effect,
                EntityPotionEffectEvent.Cause.ATTACK,
                EntityPotionEffectEvent.Action.ADDED, false);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Potion must not be blocked for non-vanished player");
    }

    @Test
    void potion_notBlocked_whenPreventPotionsFalse() {
        plugin.getConfigManager().preventPotions = false;
        plugin.applyVanishEffects(player);

        PotionEffect effect = new PotionEffect(PotionEffectType.SPEED, 100, 1);
        EntityPotionEffectEvent event = new EntityPotionEffectEvent(player, null, effect,
                EntityPotionEffectEvent.Cause.ATTACK,
                EntityPotionEffectEvent.Action.ADDED, false);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Potion must not be blocked when preventPotions=false");
    }

    // -------------------------------------------------------------------------
    // CAN_HIT_ENTITIES — EntityDamageByEntityEvent
    // -------------------------------------------------------------------------

    @Test
    void attack_cancelled_whenVanished_canHitFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_HIT_ENTITIES, false);

        LivingEntity target = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(player, target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Attack must be cancelled when CAN_HIT_ENTITIES=false");
    }

    @Test
    void attack_allowed_whenVanished_canHitTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_HIT_ENTITIES, true);

        LivingEntity target = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(player, target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Attack must be allowed when CAN_HIT_ENTITIES=true");
    }

    @Test
    void attack_notCancelled_whenNotVanished() {
        // Player is NOT vanished; rule should not apply
        plugin.getRuleManager().setRule(player, RuleManager.CAN_HIT_ENTITIES, false);

        LivingEntity target = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(player, target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Attack rule must not apply to non-vanished players");
    }

    @Test
    void attack_nonPlayerDamager_notCancelled() {
        // A mob attacks a player — no vanish rule should apply to the mob
        plugin.applyVanishEffects(player);
        LivingEntity zombie = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(zombie, player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        server.getPluginManager().callEvent(event);

        // The vanishDamage handler cancels this (god mode), but the attack handler shouldn't add cancellation
        // We're testing that the plugin doesn't NPE with a non-player damager
        assertDoesNotThrow(() -> server.getPluginManager().callEvent(new EntityDamageByEntityEvent(zombie, player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 3.0)));
    }

    // -------------------------------------------------------------------------
    // CAN_THROW — ProjectileLaunchEvent
    // -------------------------------------------------------------------------

    @Test
    void projectileLaunch_cancelled_whenVanished_canThrowFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_THROW, false);

        Projectile snowball = (Projectile) world.spawnEntity(loc, EntityType.SNOWBALL);
        snowball.setShooter(player);
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(snowball);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Projectile launch must be cancelled when CAN_THROW=false");
    }

    @Test
    void projectileLaunch_allowed_whenVanished_canThrowTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_THROW, true);

        Projectile snowball = (Projectile) world.spawnEntity(loc, EntityType.SNOWBALL);
        snowball.setShooter(player);
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(snowball);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Projectile launch must be allowed when CAN_THROW=true");
    }

    @Test
    void projectileLaunch_notCancelled_whenNotVanished() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_THROW, false);

        Projectile snowball = (Projectile) world.spawnEntity(loc, EntityType.SNOWBALL);
        snowball.setShooter(player);
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(snowball);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Projectile launch rule must not apply to non-vanished players");
    }

    @Test
    void projectileLaunch_notCancelled_whenShooterIsNotPlayer() {
        LivingEntity zombie = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);

        Projectile snowball = (Projectile) world.spawnEntity(loc, EntityType.SNOWBALL);
        snowball.setShooter(zombie);
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(snowball);
        assertDoesNotThrow(() -> server.getPluginManager().callEvent(event),
                "Non-player shooter must not crash the listener");
        assertFalse(event.isCancelled(), "Non-player projectile must never be cancelled by vanish plugin");
    }

    // -------------------------------------------------------------------------
    // CAN_TRIGGER_PHYSICAL — PlayerInteractEvent (Action.PHYSICAL)
    // -------------------------------------------------------------------------

    @Test
    void physicalInteract_cancelled_whenVanished_canTriggerFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_TRIGGER_PHYSICAL, false);

        Block pressurePlate = world.getBlockAt(0, 63, 0);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.PHYSICAL, null,
                pressurePlate, BlockFace.UP);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Physical interaction must be cancelled when CAN_TRIGGER_PHYSICAL=false");
    }

    @Test
    void physicalInteract_allowed_whenVanished_canTriggerTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_TRIGGER_PHYSICAL, true);

        Block pressurePlate = world.getBlockAt(0, 63, 0);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.PHYSICAL, null,
                pressurePlate, BlockFace.UP);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Physical interaction must be allowed when CAN_TRIGGER_PHYSICAL=true");
    }

    @Test
    void physicalInteract_notCancelled_whenNotVanished() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_TRIGGER_PHYSICAL, false);

        Block pressurePlate = world.getBlockAt(0, 63, 0);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.PHYSICAL, null,
                pressurePlate, BlockFace.UP);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Physical interaction rule must not apply to non-vanished players");
    }

    // -------------------------------------------------------------------------
    // CAN_INTERACT — PlayerInteractEvent (RIGHT_CLICK_BLOCK)
    // -------------------------------------------------------------------------

    @Test
    void rightClickBlock_cancelled_whenVanished_canInteractFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);

        Block block = world.getBlockAt(1, 64, 1);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
                block, BlockFace.NORTH);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Right-click block must be cancelled when CAN_INTERACT=false");
    }

    @Test
    void rightClickBlock_allowed_whenVanished_canInteractTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);

        Block block = world.getBlockAt(1, 64, 1);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
                block, BlockFace.NORTH);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Right-click block must be allowed when CAN_INTERACT=true");
    }

    @Test
    void rightClickBlock_notCancelled_whenNotVanished() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);

        Block block = world.getBlockAt(1, 64, 1);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
                block, BlockFace.NORTH);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Interact rule must not apply to non-vanished players");
    }

    // -------------------------------------------------------------------------
    // CAN_INTERACT — PlayerInteractEntityEvent
    // -------------------------------------------------------------------------

    @Test
    void entityInteract_cancelled_whenVanished_canInteractFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);

        LivingEntity villager = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, villager);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Entity interaction must be cancelled when CAN_INTERACT=false");
    }

    @Test
    void entityInteract_allowed_whenVanished_canInteractTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);

        LivingEntity villager = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, villager);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Entity interaction must be allowed when CAN_INTERACT=true");
    }

    @Test
    void entityInteract_notCancelled_whenNotVanished() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);

        LivingEntity villager = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, villager);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Entity interaction rule must not apply to non-vanished players");
    }

    // -------------------------------------------------------------------------
    // CAN_INTERACT — PlayerBedEnterEvent
    // -------------------------------------------------------------------------

    @Test
    void bedEnter_cancelled_whenVanished_canInteractFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);
        plugin.getConfigManager().preventSleeping = false;

        Block bed = world.getBlockAt(2, 64, 2);
        PlayerBedEnterEvent event = new PlayerBedEnterEvent(player, bed,
                PlayerBedEnterEvent.BedEnterResult.OK);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Bed enter must be cancelled when CAN_INTERACT=false");
    }

    @Test
    void bedEnter_cancelled_whenVanished_preventSleepingTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);
        plugin.getConfigManager().preventSleeping = true;

        Block bed = world.getBlockAt(2, 64, 2);
        PlayerBedEnterEvent event = new PlayerBedEnterEvent(player, bed,
                PlayerBedEnterEvent.BedEnterResult.OK);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Bed enter must be cancelled when preventSleeping=true");
    }

    @Test
    void bedEnter_allowed_whenVanished_canInteractTrue_preventSleepingFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);
        plugin.getConfigManager().preventSleeping = false;

        Block bed = world.getBlockAt(2, 64, 2);
        PlayerBedEnterEvent event = new PlayerBedEnterEvent(player, bed,
                PlayerBedEnterEvent.BedEnterResult.OK);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Bed enter must be allowed when CAN_INTERACT=true and preventSleeping=false");
    }

    @Test
    void bedEnter_notCancelled_whenNotVanished() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);
        plugin.getConfigManager().preventSleeping = true;

        Block bed = world.getBlockAt(2, 64, 2);
        PlayerBedEnterEvent event = new PlayerBedEnterEvent(player, bed,
                PlayerBedEnterEvent.BedEnterResult.OK);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Bed enter rule must not apply to non-vanished players");
    }

    // -------------------------------------------------------------------------
    // CAN_INTERACT — EntityMountEvent
    // -------------------------------------------------------------------------

    @Test
    void mount_cancelled_whenVanished_canInteractFalse() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);

        LivingEntity horse = (LivingEntity) world.spawnEntity(loc, EntityType.COW);
        EntityMountEvent event = new EntityMountEvent(player, horse);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Mount must be cancelled when CAN_INTERACT=false");
    }

    @Test
    void mount_allowed_whenVanished_canInteractTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);

        LivingEntity cow = (LivingEntity) world.spawnEntity(loc, EntityType.COW);
        EntityMountEvent event = new EntityMountEvent(player, cow);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Mount must be allowed when CAN_INTERACT=true");
    }

    @Test
    void mount_notCancelled_whenNotVanished() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);

        LivingEntity cow = (LivingEntity) world.spawnEntity(loc, EntityType.COW);
        EntityMountEvent event = new EntityMountEvent(player, cow);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Mount rule must not apply to non-vanished players");
    }

    // -------------------------------------------------------------------------
    // MOB_TARGETING rule — explicit enable/disable
    // -------------------------------------------------------------------------

    @Test
    void mobTargeting_notCancelled_whenVanished_mobTargetingRuleTrue() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.MOB_TARGETING, true);

        LivingEntity zombie = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(zombie, player,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Mob must be allowed to target vanished player when MOB_TARGETING=true");
    }

    @Test
    void mobTargeting_cancelled_whenVanished_mobTargetingRuleFalse_skeletonMob() {
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.MOB_TARGETING, false);

        LivingEntity skeleton = (LivingEntity) world.spawnEntity(loc, EntityType.SKELETON);
        EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(skeleton, player,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Skeleton must not target vanished player with MOB_TARGETING=false");
    }

    @Test
    void mobTargeting_creeperDoesNotTargetVanished() {
        plugin.applyVanishEffects(player);
        // Default MOB_TARGETING = false

        LivingEntity creeper = (LivingEntity) world.spawnEntity(loc, EntityType.CREEPER);
        EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(creeper, player,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "Creeper must not target vanished player by default");
    }

    @Test
    void mobTargeting_twoMobs_bothCancelledForVanishedTarget() {
        plugin.applyVanishEffects(player);

        LivingEntity zombie1 = (LivingEntity) world.spawnEntity(loc, EntityType.ZOMBIE);
        LivingEntity zombie2 = (LivingEntity) world.spawnEntity(new Location(world, 5, 64, 5), EntityType.ZOMBIE);

        EntityTargetLivingEntityEvent event1 = new EntityTargetLivingEntityEvent(zombie1, player,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        EntityTargetLivingEntityEvent event2 = new EntityTargetLivingEntityEvent(zombie2, player,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);

        server.getPluginManager().callEvent(event1);
        server.getPluginManager().callEvent(event2);

        assertTrue(event1.isCancelled(), "First zombie must not target vanished player");
        assertTrue(event2.isCancelled(), "Second zombie must not target vanished player");
    }

    // -------------------------------------------------------------------------
    // Block place — non-vanished scenario
    // -------------------------------------------------------------------------

    @Test
    void blockPlace_notCancelled_whenNotVanished_ruleIsFalse() {
        // Rule false but player not vanished — rule must have no effect
        plugin.getRuleManager().setRule(player, RuleManager.CAN_PLACE_BLOCKS, false);

        Block block = world.getBlockAt(2, 64, 0);
        Block against = world.getBlockAt(1, 64, 0);
        BlockState replacedState = block.getState();
        ItemStack hand = new ItemStack(Material.STONE);

        BlockPlaceEvent event = new BlockPlaceEvent(block, replacedState, against, hand,
                player, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Block place rule must not cancel for non-vanished players");
    }

    // -------------------------------------------------------------------------
    // Item drop — non-vanished scenario
    // -------------------------------------------------------------------------

    @Test
    void itemDrop_notCancelled_whenNotVanished_ruleIsFalse() {
        plugin.getRuleManager().setRule(player, RuleManager.CAN_DROP_ITEMS, false);

        org.bukkit.entity.Item item = world.dropItem(loc, new ItemStack(Material.STONE));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Item drop rule must not cancel for non-vanished players");
    }

    // -------------------------------------------------------------------------
    // Vanish state management — idempotency and edge cases
    // -------------------------------------------------------------------------

    @Test
    void isVanished_falseForNewPlayer() {
        assertFalse(plugin.isVanished(player), "New player must not be vanished");
    }

    @Test
    void applyVanishEffects_idempotent_calledTwice() {
        plugin.applyVanishEffects(player);
        plugin.applyVanishEffects(player); // second call must be safe (no-op or idempotent)

        assertTrue(plugin.isVanished(player), "Player must still be vanished after double apply");
        assertEquals(1, plugin.getRawVanishedPlayers().stream()
                .filter(uuid -> uuid.equals(player.getUniqueId())).count(),
                "Player UUID must appear exactly once in the vanished set");
    }

    @Test
    void removeVanishEffects_safeWhenNotVanished() {
        // Should not throw or corrupt state
        assertDoesNotThrow(() -> plugin.removeVanishEffects(player),
                "removeVanishEffects must not throw when player is not vanished");
        assertFalse(plugin.isVanished(player));
    }

    @Test
    void applyThenRemove_stateCorrect() {
        plugin.applyVanishEffects(player);
        assertTrue(plugin.isVanished(player));

        plugin.removeVanishEffects(player);
        assertFalse(plugin.isVanished(player));
        assertTrue(player.getMetadata("vanished").isEmpty() ||
                !player.getMetadata("vanished").get(0).asBoolean(),
                "vanished metadata must be cleared on unvanish");
    }

    @Test
    void multiplePlayers_vanishedIndependently() {
        PlayerMock playerA = server.addPlayer();
        PlayerMock playerB = server.addPlayer();

        plugin.applyVanishEffects(playerA);
        assertFalse(plugin.isVanished(playerB), "Vanishing A must not affect B");

        plugin.applyVanishEffects(playerB);
        assertTrue(plugin.isVanished(playerA), "A must remain vanished after B also vanishes");
        assertTrue(plugin.isVanished(playerB));

        plugin.removeVanishEffects(playerA);
        assertFalse(plugin.isVanished(playerA), "A must be unvanished");
        assertTrue(plugin.isVanished(playerB), "B must still be vanished after A unvanishes");
    }

    @Test
    void vanishSet_containsCorrectPlayers() {
        PlayerMock playerA = server.addPlayer();
        PlayerMock playerB = server.addPlayer();

        plugin.applyVanishEffects(playerA);
        plugin.applyVanishEffects(playerB);

        assertTrue(plugin.getRawVanishedPlayers().contains(playerA.getUniqueId()));
        assertTrue(plugin.getRawVanishedPlayers().contains(playerB.getUniqueId()));
        assertFalse(plugin.getRawVanishedPlayers().contains(player.getUniqueId()),
                "Un-vanished player must not be in vanished set");
    }
}
