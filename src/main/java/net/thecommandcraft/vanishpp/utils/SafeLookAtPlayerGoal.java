package net.thecommandcraft.vanishpp.utils;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.NamespacedKey;
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
        if (mob.getTarget() instanceof Player) {
            targetPlayer = (Player) mob.getTarget();
            if (plugin.isVanished(targetPlayer)) {
                return false;
            }
            return true;
        }

        // Find nearest player to look at
        double targetDistanceSq = 64.0; // 8 blocks radius squared (8 * 8)
        for (Player p : mob.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(mob.getLocation()) < targetDistanceSq) {
                if (!plugin.isVanished(p)) {
                    targetPlayer = p;
                    return true;
                }
            }
        }
        return false;
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
