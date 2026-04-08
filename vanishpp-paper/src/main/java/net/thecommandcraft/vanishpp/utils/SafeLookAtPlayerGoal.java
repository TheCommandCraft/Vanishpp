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
            if (plugin.isVanished(p)) return false;
            targetPlayer = p;
            return true;
        }

        // Replicate vanilla LookAtPlayerGoal: nearest non-vanished player within 8 blocks
        Player nearest = null;
        double nearestDistSq = 64.0; // 8 * 8 — vanilla range
        for (Entity e : mob.getLocation().getNearbyEntitiesByType(Player.class, 8.0)) {
            if (!(e instanceof Player p)) continue;
            if (plugin.isVanished(p)) continue;
            double d = mob.getLocation().distanceSquared(p.getLocation());
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = p;
            }
        }
        if (nearest == null) return false;
        targetPlayer = nearest;
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        if (targetPlayer == null || !targetPlayer.isOnline() || targetPlayer.isDead()) {
            return false;
        }
        if (plugin.isVanished(targetPlayer)) {
            return false; // Stop looking if they vanish
        }
        return mob.getLocation().distanceSquared(targetPlayer.getLocation()) < 64.0;
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
        if (targetPlayer != null) {
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
