package net.thecommandcraft.vanishpp.utils;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;

public class SafeLookAtPlayerGoal implements Goal<Mob> {

    private final Vanishpp plugin;
    private final Mob mob;
    private final GoalKey<Mob> key;
    private Player targetPlayer;

    public SafeLookAtPlayerGoal(Vanishpp plugin, Mob mob) {
        this.plugin = plugin;
        this.mob = mob;
        this.key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "safe_look_at_player"));
    }

    @Override
    public boolean shouldActivate() {
        if (mob.getTarget() instanceof Player p) {
            if (plugin.isVanished(p)) {
                // Combat target is vanished: claim the LOOK slot so vanilla can't fall through,
                // but don't set a targetPlayer (tick() will be a no-op).
                targetPlayer = null;
                return true;
            }
            targetPlayer = p;
            return true;
        }

        // Scan nearby players within vanilla range (8 blocks).
        // We ALWAYS activate (claiming the LOOK slot) if ANY player is nearby,
        // so the vanilla LookAtPlayerGoal can never fill the slot for vanished players.
        Player nearest = null;
        boolean anyNearby = false;
        double nearestDistSq = 64.0; // 8 * 8
        for (Entity e : mob.getLocation().getNearbyEntitiesByType(Player.class, 8.0)) {
            if (!(e instanceof Player p)) continue;
            anyNearby = true;
            if (plugin.isVanished(p)) continue; // skip vanished — don't track them
            double d = mob.getLocation().distanceSquared(p.getLocation());
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = p;
            }
        }
        targetPlayer = nearest; // may be null if only vanished players nearby
        // Activate whenever ANY player is nearby so we hold the slot and vanilla stays idle.
        return anyNearby;
    }

    @Override
    public boolean shouldStayActive() {
        // If we have a live non-vanished target in range, keep tracking.
        if (targetPlayer != null && targetPlayer.isOnline() && !targetPlayer.isDead()
                && !plugin.isVanished(targetPlayer)
                && mob.getLocation().distanceSquared(targetPlayer.getLocation()) < 64.0) {
            return true;
        }
        // Target gone/vanished — but keep the slot active if any player (vanished or not)
        // is still nearby, so vanilla can't claim the slot and look at vanished players.
        targetPlayer = null;
        for (Entity e : mob.getLocation().getNearbyEntitiesByType(Player.class, 8.0)) {
            if (e instanceof Player p && !plugin.isVanished(p)) {
                targetPlayer = p; // re-acquire a non-vanished target
                return true;
            } else if (e instanceof Player) {
                return true; // vanished player nearby — hold the slot, do nothing
            }
        }
        return false;
    }

    @Override
    public void start() {
        // Look logic handled by tick
    }

    @Override
    public void stop() {
        targetPlayer = null;
    }

    @Override
    public void tick() {
        if (targetPlayer != null && !plugin.isVanished(targetPlayer)) {
            mob.lookAt(targetPlayer);
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.LOOK);
    }
}
