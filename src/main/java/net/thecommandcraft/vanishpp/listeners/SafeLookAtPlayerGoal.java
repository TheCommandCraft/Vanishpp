package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Random;

public class SafeLookAtPlayerGoal implements Goal<Mob> {

    private final Vanishpp plugin;
    private final Mob mob;
    private final GoalKey<Mob> key;
    private final Random random = new Random();

    private Player target;
    private int lookTime;

    public SafeLookAtPlayerGoal(Vanishpp plugin, Mob mob) {
        this.plugin = plugin;
        this.mob = mob;
        this.key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "safe_look_at_player"));
    }

    @Override
    public boolean shouldActivate() {
        if (random.nextFloat() >= 0.02) return false;
        if (mob.getTarget() != null) return false;

        this.target = mob.getWorld().getNearbyPlayers(mob.getLocation(), 8.0, p -> {
            // THE CRITICAL CHECK
            if (plugin.isVanished(p)) return false;
            return p.getGameMode() != GameMode.SPECTATOR;
        }).stream().findFirst().orElse(null);

        return this.target != null;
    }

    @Override
    public boolean shouldStayActive() {
        if (target == null || !target.isValid()) return false;
        if (plugin.isVanished(target)) return false;
        if (mob.getLocation().distanceSquared(target.getLocation()) > 64.0) return false;
        return lookTime > 0;
    }

    @Override
    public void start() {
        this.lookTime = 40 + random.nextInt(40);
    }

    @Override
    public void tick() {
        if (target != null) {
            mob.lookAt(target.getEyeLocation());
            lookTime--;
        }
    }

    @Override
    public @NotNull GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public @NotNull EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.LOOK);
    }
}