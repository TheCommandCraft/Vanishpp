package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.ArrayList;
import java.util.List;

public class MobAiManager implements Listener {

    /**
     * Vanilla LOOK_AT_PLAYER sits around priority 8-10 for most mobs; all vanilla attack goals
     * sit at priority 2-4. A fixed low-precedence priority guarantees this goal can never outrank
     * an attack goal for the shared GoalType.LOOK flag, without needing to introspect the exact
     * vanilla priority (Paper's MobGoals API exposes no getter for it).
     * Public (and covered by a test) because lowering this back toward vanilla attack-goal
     * priority (2-4) is the exact mistake that broke combat in four past regressions.
     */
    public static final int LOOK_GOAL_PRIORITY = 10;

    private final Vanishpp plugin;

    public MobAiManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    private final java.util.Map<java.util.UUID, java.util.UUID> mobTargets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, java.util.Map<java.util.UUID, Mob>> targeting = new java.util.concurrent.ConcurrentHashMap<>();

    public void register() {
        if (plugin.hasPaperApi()) plugin.getVanishScheduler().runGlobal(() -> {
            for (World world : Bukkit.getWorlds()) {
                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                    int x = chunk.getX(), z = chunk.getZ();
                    plugin.getVanishScheduler().runRegion(world, x, z, () -> {
                        if (!world.isChunkLoaded(x, z)) return;
                        for (Entity entity : chunk.getEntities()) if (entity instanceof Mob mob)
                            plugin.getVanishScheduler().runEntityNow(mob, () -> watch(mob));
                    });
                }
            }
        });
    }

    public void clearTargets(Player player) {
        if (!plugin.getConfigManager().disableMobTarget) return;
        java.util.Map<java.util.UUID, Mob> mobs = targeting.remove(player.getUniqueId());
        if (mobs != null) for (Mob mob : mobs.values()) clearTarget(mob, player.getUniqueId());
        // Paper permits a one-time world snapshot; Folia uses owner-published target references.
        if (!plugin.getVanishScheduler().regionThreaded()) {
            for (Entity entity : player.getWorld().getEntities()) if (entity instanceof Mob mob) clearTarget(mob, player.getUniqueId());
        }
    }

    private void clearTarget(Mob mob, java.util.UUID player) {
        plugin.getVanishScheduler().runEntityNow(mob, () -> {
            org.bukkit.entity.LivingEntity target = mob.getTarget();
            if (target != null && player.equals(target.getUniqueId()) && plugin.isVanished(player)) {
                mob.setTarget(null);
                mob.getPathfinder().stopPathfinding();
                forget(mob.getUniqueId());
            }
        });
    }

    private void forget(java.util.UUID mob) {
        java.util.UUID previous = mobTargets.remove(mob);
        if (previous != null) targeting.computeIfPresent(previous, (uuid, mobs) -> {
            mobs.remove(mob);
            return mobs.isEmpty() ? null : mobs;
        });
    }

    private void recordTarget(Mob mob, Entity target) {
        forget(mob.getUniqueId());
        if (target instanceof Player player) {
            mobTargets.put(mob.getUniqueId(), player.getUniqueId());
            targeting.compute(player.getUniqueId(), (uuid, mobs) -> {
                if (mobs == null) mobs = new java.util.concurrent.ConcurrentHashMap<>();
                mobs.put(mob.getUniqueId(), mob);
                return mobs;
            });
        }
    }

    private void watch(Mob mob) {
        recordTarget(mob, mob.getTarget());
        if (mob.getTarget() instanceof Player target && plugin.isVanished(target)
                && plugin.getConfigManager().disableMobTarget
                && !plugin.getRuleManager().getRule(target, RuleManager.MOB_TARGETING)) clearTarget(mob, target.getUniqueId());
        if (plugin.hasPaperApi()) injectIfMissing(mob);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onTarget(org.bukkit.event.entity.EntityTargetEvent event) {
        if (event.getEntity() instanceof Mob mob) recordTarget(mob, event.getTarget());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Mob mob) watch(mob);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof Mob mob) watch(mob);
    }

    @EventHandler
    public void onEntitiesUnload(org.bukkit.event.world.EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof Mob mob) forget(mob.getUniqueId());
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        forget(event.getEntity().getUniqueId());
    }
    /**
     * Replaces each vanilla LOOK_AT_PLAYER goal that looks at players with a vanish-aware copy
     * using the same range, chance and head movement. Ones that look at other mobs (e.g. illagers
     * looking at Mob) stay vanilla, they never pick a player.
     * Never touches any other GoalType (MOVE/JUMP/TARGET) - those belong to attack/movement
     * goals and must never be touched (doing so previously broke vanilla combat, see CHANGELOG).
     */
    private void injectIfMissing(Mob mob) {
        try {
            MobGoals goals = Bukkit.getMobGoals();
            // one pass over the LOOK goals instead of a full goal scan per hasGoal/getGoals call
            List<Goal<Mob>> vanilla = new ArrayList<>(2);
            for (Goal<Mob> goal : goals.getAllGoals(mob, GoalType.LOOK)) {
                if (goal.getKey().equals(VanishLookGoal.KEY)) return; // already injected
                if (goal.getKey().equals(VanillaGoal.LOOK_AT_PLAYER)) vanilla.add(goal);
            }

            for (Goal<Mob> goal : vanilla) {
                VanishLookGoal.Settings settings = VanishLookGoal.Settings.of(goal);
                if (settings == null) continue; // looks at other mobs, not players
                goals.removeGoal(mob, goal);
                goals.addGoal(mob, LOOK_GOAL_PRIORITY, new VanishLookGoal(plugin, mob, settings));
            }
        } catch (Throwable ignored) {
            // MobGoals API unavailable for this mob type/platform - fall back to vanilla look behavior
        }
    }
}
