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

    private static final int ENTITY_WORK_PER_TICK = 64;
    private static final long WORK_INTERVAL_TICKS = 1L;
    private record ChunkWork(World world, int x, int z) {}
    private record MobHandle(java.util.UUID id, java.lang.ref.WeakReference<Mob> mob, ChunkWork region) {}
    private record MobWork(MobHandle handle, java.util.UUID target) {}
    private final java.util.Map<java.util.UUID, java.util.UUID> mobTargets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, java.util.Set<java.util.UUID>> targeting = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, MobHandle> handles = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Queue<ChunkWork> chunks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<MobHandle> initializing = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<java.util.UUID> requests = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Set<java.util.UUID> requested = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<java.util.UUID> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.Iterator<java.util.UUID> currentTargets = java.util.Collections.emptyIterator();
    private java.util.UUID currentPlayer;

    public void register() {
        plugin.getVanishScheduler().runTimerGlobal(this::workTick, WORK_INTERVAL_TICKS, WORK_INTERVAL_TICKS);
        if (plugin.hasPaperApi()) plugin.getVanishScheduler().runGlobal(() -> {
            for (World world : Bukkit.getWorlds()) {
                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                    chunks.add(new ChunkWork(world, chunk.getX(), chunk.getZ()));
                }
            }
        });
    }

    private void workTick() {
        // Admit one loaded chunk each tick; entity work from it shares the same bounded queue.
        ChunkWork chunk = chunks.poll();
        if (chunk != null) plugin.getVanishScheduler().runRegion(chunk.world(), chunk.x(), chunk.z(), () -> {
            if (!chunk.world().isChunkLoaded(chunk.x(), chunk.z())) return;
            for (Entity entity : chunk.world().getChunkAt(chunk.x(), chunk.z()).getEntities())
                if (entity instanceof Mob mob) initializing.add(handle(mob));
        });
        java.util.Map<ChunkWork, List<MobWork>> batches = new java.util.HashMap<>();
        for (int work = 0; work < ENTITY_WORK_PER_TICK; work++) {
            if (!currentTargets.hasNext()) {
                if (currentPlayer != null) requested.remove(currentPlayer);
                currentPlayer = requests.poll();
                currentTargets = currentPlayer == null ? java.util.Collections.emptyIterator()
                        : targeting.getOrDefault(currentPlayer, java.util.Set.of()).iterator();
            }
            if (currentTargets.hasNext()) {
                java.util.UUID uuid = currentTargets.next();
                var handle = handles.get(uuid);
                Mob mob = handle == null ? null : handle.mob().get();
                if (mob == null) forget(uuid);
                else if (inFlight.add(uuid)) batches.computeIfAbsent(handle.region(), key -> new ArrayList<>()).add(new MobWork(handle, currentPlayer));
            } else {
                var handle = initializing.poll();
                if (handle == null) {
                    if (currentPlayer == null) break;
                    continue;
                }
                if (handle.mob().get() != null) {
                    if (inFlight.add(handle.id())) batches.computeIfAbsent(handle.region(), key -> new ArrayList<>()).add(new MobWork(handle, null));
                    else initializing.add(handle);
                }
            }
        }
        batches.forEach((region, batch) -> plugin.getVanishScheduler().runRegion(region.world(), region.x(), region.z(), () -> {
            for (MobWork work : batch) {
                Mob mob = work.handle().mob().get();
                if (mob == null) { forget(work.handle().id()); continue; }
                Runnable apply = () -> {
                    try {
                        if (!mob.isValid()) { forget(work.handle().id()); return; }
                        if (work.target() == null) watch(mob); else clearTargetOwned(mob, work.target());
                    } finally { inFlight.remove(work.handle().id()); }
                };
                if (!plugin.getVanishScheduler().regionThreaded() || plugin.getVanishScheduler().owns(mob)) apply.run();
                else plugin.getVanishScheduler().runEntity(mob, apply, () -> forget(work.handle().id()));
            }
        }));
    }

    private MobHandle handle(Mob mob) {
        org.bukkit.Location location = mob.getLocation();
        return new MobHandle(mob.getUniqueId(), new java.lang.ref.WeakReference<>(mob),
                new ChunkWork(mob.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4));
    }

    public void clearTargets(Player player) {
        if (!plugin.getConfigManager().disableMobTarget || plugin.getRuleManager().getRule(player, RuleManager.MOB_TARGETING)) return;
        if (requested.add(player.getUniqueId())) requests.add(player.getUniqueId());
    }

    private void clearTargetOwned(Mob mob, java.util.UUID player) {
        java.util.UUID uuid = mob.getUniqueId();
        if (!mob.isValid()) { forget(uuid); return; }
        org.bukkit.entity.LivingEntity target = mob.getTarget();
        if (target != null && player.equals(target.getUniqueId()) && plugin.isVanished(player)
                && plugin.getConfigManager().disableMobTarget
                && !plugin.getRuleManager().getRule(player, RuleManager.MOB_TARGETING)) {
            mob.setTarget(null);
            mob.getPathfinder().stopPathfinding();
            forget(uuid);
        }
    }

    private void forget(java.util.UUID mob) {
        inFlight.remove(mob);
        handles.remove(mob);
        removeTarget(mob);
    }

    private void removeTarget(java.util.UUID mob) {
        java.util.UUID previous = mobTargets.remove(mob);
        if (previous != null) targeting.computeIfPresent(previous, (uuid, mobs) -> {
            mobs.remove(mob);
            return mobs.isEmpty() ? null : mobs;
        });
    }

    private void recordTarget(Mob mob, Entity target) {
        removeTarget(mob.getUniqueId());
        handles.remove(mob.getUniqueId());
        if (target instanceof Player player) {
            handles.put(mob.getUniqueId(), handle(mob));
            mobTargets.put(mob.getUniqueId(), player.getUniqueId());
            targeting.compute(player.getUniqueId(), (uuid, mobs) -> {
                if (mobs == null) mobs = java.util.concurrent.ConcurrentHashMap.newKeySet();
                mobs.add(mob.getUniqueId());
                return mobs;
            });
        }
    }

    private void watch(Mob mob) {
        recordTarget(mob, mob.getTarget());
        if (mob.getTarget() instanceof Player target && plugin.isVanished(target)
                && plugin.getConfigManager().disableMobTarget
                && !plugin.getRuleManager().getRule(target, RuleManager.MOB_TARGETING)) clearTargetOwned(mob, target.getUniqueId());
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
        for (Entity entity : event.getEntities()) if (entity instanceof Mob mob) initializing.add(handle(mob));
    }

    @EventHandler
    public void onEntitiesUnload(org.bukkit.event.world.EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof Mob mob) forget(mob.getUniqueId());
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        forget(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onRemove(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
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
