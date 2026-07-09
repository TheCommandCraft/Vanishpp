package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;

/**
 * Replaces the vanilla LOOK_AT_PLAYER goal on a per-mob basis. Activation mirrors vanilla exactly
 * (any player in range, vanished or not) so the LOOK goal slot is never freed for the vanilla goal
 * to reclaim; only the actual look target excludes vanished players. Registered at a fixed low
 * priority (see MobAiManager.LOOK_GOAL_PRIORITY) so it can never preempt attack goals, which also
 * claim GoalType.LOOK while attacking.
 */
public class VanishLookGoal implements Goal<Mob> {

    public static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("vanishpp", "vanish_look"));

    private static final double LOOK_DISTANCE = 8.0;

    private final Vanishpp plugin;
    private final Mob mob;

    private Player anyNearbyPlayer;
    private Player visibleLookTarget;

    public VanishLookGoal(Vanishpp plugin, Mob mob) {
        this.plugin = plugin;
        this.mob = mob;
    }

    @Override
    public boolean shouldActivate() {
        refreshCandidates();
        return anyNearbyPlayer != null;
    }

    @Override
    public boolean shouldStayActive() {
        refreshCandidates();
        return anyNearbyPlayer != null;
    }

    @Override
    public void tick() {
        if (visibleLookTarget != null) {
            mob.lookAt(visibleLookTarget);
        }
        // Only vanished players nearby: do nothing, leave the mob's head where it is.
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.LOOK);
    }

    private void refreshCandidates() {
        anyNearbyPlayer = null;
        visibleLookTarget = null;
        double nearestAnySq = Double.MAX_VALUE;
        double nearestVisibleSq = Double.MAX_VALUE;

        for (Entity entity : mob.getLocation().getNearbyEntities(LOOK_DISTANCE, LOOK_DISTANCE, LOOK_DISTANCE)) {
            if (!(entity instanceof Player player)) continue;
            double distSq = player.getLocation().distanceSquared(mob.getLocation());

            if (distSq < nearestAnySq) {
                nearestAnySq = distSq;
                anyNearbyPlayer = player;
            }
            if (!plugin.isVanished(player) && distSq < nearestVisibleSq) {
                nearestVisibleSq = distSq;
                visibleLookTarget = player;
            }
        }
    }
}
