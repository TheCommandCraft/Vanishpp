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
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * A Warden's baseline "idle wander toward the closest entity" behavior (see
     * hasOnlyVanishedTargetsNearby) isn't scoped to the same 15-block vibration radius as its
     * hearing - it reacts to any "targetable entity" in a much wider area. 32 is a generous
     * approximation of that wider awareness radius; the exact vanilla box doesn't need to be
     * matched precisely, since erring slightly wide here only means we occasionally leave a
     * Warden un-frozen (aware) a little further out than strictly necessary, never the other
     * way around.
     */
    private static final double WARDEN_AWARENESS_RANGE = 32;

    private final Vanishpp plugin;

    /**
     * Wardens this manager has set unaware (see hasOnlyVanishedTargetsNearby) via
     * Mob#setAware(false), so unfreezeStaleWardens knows which ones it's responsible for
     * restoring - never touches a Warden's awareness state unless it's the one that froze it.
     */
    private final Set<UUID> frozenWardens = ConcurrentHashMap.newKeySet();

    public MobAiManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void register() {
        // Combat-target safety net: clear ANY mob target/pathfinding aimed at vanished players.
        // Runs every 1 tick (maximum responsiveness) with 3-tick startup delay.
        plugin.getVanishScheduler().runTimerGlobal(this::sweepMobTargets, 3L, 1L);

        // Guarantees any Warden frozen via hasOnlyVanishedTargetsNearby always thaws again,
        // even once it's no longer within sweepMobTargets' reach - e.g. the vanished player
        // that triggered the freeze quits, unvanishes, or simply walks/teleports far enough
        // away that this Warden stops showing up in that player's per-tick nearby-entity scan.
        // frozenWardens is normally empty or tiny, so a slower 20-tick period is plenty
        // responsive without adding meaningful overhead.
        plugin.getVanishScheduler().runTimerGlobal(this::unfreezeStaleWardens, 20L, 20L);

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

            // getNearbyEntities() requires the owning region thread (Folia safety) — dispatch per player.
            plugin.getVanishScheduler().runEntity(p, () -> sweepForPlayer(p), null);
        }
    }

    private void sweepForPlayer(Player p) {
        // Check all nearby mobs and clear any that have the vanished player as target
        for (Entity entity : p.getNearbyEntities(128, 128, 128)) {
            if (!(entity instanceof Mob mob)) continue;

            // A mob near a region boundary may belong to a different region than the
            // player — its target/pathfinder must be touched on its own owning thread.
            plugin.getVanishScheduler().runEntity(mob, () -> {
                if (p.equals(mob.getTarget())) {
                    mob.setTarget(null);
                    try {
                        mob.getPathfinder().stopPathfinding();
                    } catch (Throwable ignored) {}
                }
                // Wardens don't use Mob#target for their sonic-boom attack - they track threats
                // via a separate per-entity anger score (Warden#getAnger/clearAnger), which the
                // target-clearing above never touches. A player may already have nonzero anger
                // from before they vanished, so this has to be cleared here too, not just guarded
                // against going forward via onWardenAngerChange in PlayerListener.
                if (mob instanceof Warden warden) {
                    if (warden.getAnger(p) > 0) {
                        warden.clearAnger(p);
                    }

                    // Anger and Mob#target only cover a Warden's *hostile* pursuit. Independent
                    // of both, an idle Warden with nothing else to react to autonomously
                    // pathfinds toward the closest entity it's aware of - a separate Brain
                    // behavior, invisible to the target-clearing above, and never blocked by
                    // keeping anger at 0 (if anything, keeping anger down makes it MORE visible,
                    // since a real Warden usually escalates straight past this idle state before
                    // anyone notices it). Mob#setAware(false) is the only lever that reaches
                    // Brain-driven movement, since it stops a mob acting on its own accord
                    // entirely rather than reacting to what it already decided. Only engage it
                    // while this specific Warden has no other, non-vanished player nearby to
                    // legitimately react to, so a real fight/threat near the same Warden is never
                    // silently disabled - see hasOnlyVanishedTargetsNearby. unfreezeStaleWardens
                    // guarantees this always gets reversed even after this player stops being
                    // the one bringing this Warden into sweepForPlayer's reach at all.
                    UUID wardenId = warden.getUniqueId();
                    if (hasOnlyVanishedTargetsNearby(warden)) {
                        if (frozenWardens.add(wardenId)) {
                            warden.setAware(false);
                        }
                    } else if (frozenWardens.remove(wardenId)) {
                        warden.setAware(true);
                    }
                }
            }, null);
        }
    }

    /**
     * True if the only players within this Warden's own vicinity are vanished (with
     * mob_targeting off) - i.e. there is nothing nearby it should legitimately be reacting to.
     * Deliberately scoped to the Warden's own position (not the vanished player being swept),
     * so a Warden that's simultaneously near a real fight and a vanished bystander is correctly
     * left alone.
     */
    private boolean hasOnlyVanishedTargetsNearby(Warden warden) {
        boolean sawVanished = false;
        for (Entity nearby : warden.getNearbyEntities(WARDEN_AWARENESS_RANGE, WARDEN_AWARENESS_RANGE, WARDEN_AWARENESS_RANGE)) {
            if (!(nearby instanceof Player other)) continue;
            if (plugin.isVanished(other) && !plugin.getRuleManager().getRule(other, RuleManager.MOB_TARGETING)) {
                sawVanished = true;
            } else {
                return false; // a real (or mob_targeting-exempt) player is in range - leave it alone
            }
        }
        return sawVanished;
    }

    /**
     * Re-checks every Warden this manager has frozen via hasOnlyVanishedTargetsNearby, on its
     * own timer rather than only when sweepForPlayer happens to still be visiting it. Without
     * this, a Warden frozen because the only nearby player was vanished would stay unaware
     * forever the moment that player quits, unvanishes, or wanders/teleports out of range -
     * sweepForPlayer only ever looks at mobs near a *currently* vanished player, so it has no
     * opportunity to notice and reverse a freeze once that's no longer true.
     */
    private void unfreezeStaleWardens() {
        if (frozenWardens.isEmpty()) return;

        for (UUID id : frozenWardens) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Warden warden)) {
                frozenWardens.remove(id);
                continue;
            }

            plugin.getVanishScheduler().runEntity(warden, () -> {
                if (!warden.isValid()) {
                    frozenWardens.remove(id);
                } else if (!hasOnlyVanishedTargetsNearby(warden)) {
                    warden.setAware(true);
                    frozenWardens.remove(id);
                }
            }, null);
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
