package net.thecommandcraft.vanishpp.listeners;

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

            // Check all nearby mobs and clear any that have the vanished player as target
            for (Entity entity : p.getNearbyEntities(128, 128, 128)) {
                if (!(entity instanceof Mob mob)) continue;

                // Clear target if aimed at this vanished player
                if (p.equals(mob.getTarget())) {
                    mob.setTarget(null);
                    try {
                        mob.getPathfinder().stopPathfinding();
                    } catch (Throwable ignored) {}
                }
            }
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
     * Removes the vanilla LOOK_AT_PLAYER goal and replaces it with a vanish-aware equivalent.
     * Never touches any other GoalType (MOVE/JUMP/TARGET) - those belong to attack/movement
     * goals and must never be touched (doing so previously broke vanilla combat, see CHANGELOG).
     */
    private void injectIfMissing(Mob mob) {
        try {
            MobGoals goals = Bukkit.getMobGoals();
            if (!goals.hasGoal(mob, VanillaGoal.LOOK_AT_PLAYER)) return; // mob type has no look goal
            if (goals.hasGoal(mob, VanishLookGoal.KEY)) return; // already injected

            goals.removeGoal(mob, VanillaGoal.LOOK_AT_PLAYER);
            goals.addGoal(mob, LOOK_GOAL_PRIORITY, new VanishLookGoal(plugin, mob));
        } catch (Throwable ignored) {
            // MobGoals API unavailable for this mob type/platform - fall back to vanilla look behavior
        }
    }
}
