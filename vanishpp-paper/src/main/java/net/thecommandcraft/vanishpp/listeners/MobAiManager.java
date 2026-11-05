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

    public void register() {
        // Combat-target safety net: clear ANY mob target/pathfinding aimed at vanished players.
        // Runs every 1 tick (maximum responsiveness) with 3-tick startup delay.
        plugin.getVanishScheduler().runTimerGlobal(this::sweepMobTargets, 3L, 1L);

        // Visual look-at prevention: replace the vanilla LOOK_AT_PLAYER goal with VanishLookGoal
        // on every currently loaded mob. Covers mobs already loaded before this listener existed
        // (e.g. right after /vanishreload or a --restart deploy) - new/loading mobs are covered
        // by onCreatureSpawn/onEntitiesLoad below.
        if (plugin.hasPaperApi()) {
            plugin.getVanishScheduler().runGlobal(this::injectAllLoadedMobs);
        }
    }

    private void sweepMobTargets() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.isVanished(p)) continue;

            // Only enforce clearing if mob_targeting rule is OFF
            if (plugin.getRuleManager().getRule(p, RuleManager.MOB_TARGETING)) continue;

            // getNearbyEntities() requires the owning region thread (Folia safety). Runs inline when
            // this thread already owns the player (always on Paper/Spigot), so no task per player per tick.
            plugin.getVanishScheduler().runEntityNow(p, () -> sweepForPlayer(p));
        }
    }

    private void sweepForPlayer(Player p) {
        // Check all nearby mobs and clear any that have the vanished player as target
        for (Entity entity : p.getNearbyEntities(128, 128, 128)) {
            if (!(entity instanceof Mob mob)) continue;

            // A mob near a region boundary may belong to a different region than the
            // player — its target/pathfinder must be touched on its own owning thread.
            // Everything else is checked inline instead of scheduling a task per mob per tick.
            plugin.getVanishScheduler().runEntityNow(mob, () -> {
                if (p.equals(mob.getTarget())) {
                    mob.setTarget(null);
                    try {
                        mob.getPathfinder().stopPathfinding();
                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (plugin.hasPaperApi() && event.getEntity() instanceof Mob mob) {
            injectIfMissing(mob);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!plugin.hasPaperApi()) return;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Mob mob) injectIfMissing(mob);
        }
    }

    private void injectAllLoadedMobs() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob mob) {
                    plugin.getVanishScheduler().runEntity(mob, () -> injectIfMissing(mob), null);
                }
            }
        }
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
