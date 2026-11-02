package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Replaces the vanilla LOOK_AT_PLAYER goal on a per-mob basis and behaves like it: a 2% roll per
 * activation check, the nearest player in range as the target, and a 40-79 tick look. Vanished
 * players are never picked, and a target that vanishes mid-look ends the goal at once.
 * <p>
 * MobAiManager removes the vanilla goal before adding this one, so while this goal is idle the
 * LOOK slot can only go to the mob's other LOOK goals (e.g. RandomLookAroundGoal), never to a
 * vanish-unaware look-at-player goal.
 * <p>
 * Registered at a fixed low priority (see MobAiManager.LOOK_GOAL_PRIORITY) so it can never preempt
 * attack goals, which also claim GoalType.LOOK while attacking.
 */
public class VanishLookGoal implements Goal<Mob> {

    public static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("vanishpp", "vanish_look"));

    private static final double LOOK_DISTANCE = 8.0;
    /** Vanilla LookAtPlayerGoal's default probability. */
    static final float LOOK_PROBABILITY = 0.02f;
    static final int MIN_LOOK_TICKS = 40;
    static final int LOOK_TICKS_SPREAD = 40;

    private final Mob mob;
    private final Predicate<Player> isVanished;
    /** On Folia an entity's state may only be read from the region thread that owns it. */
    private final Predicate<Entity> ownedByCurrentRegion;
    // A supplier, not a stored instance: ThreadLocalRandom.current() belongs to the calling
    // thread, and on Folia the mob's region thread can change.
    private final Supplier<? extends RandomGenerator> random;

    private Player lookTarget;
    private int lookTime;

    public VanishLookGoal(Vanishpp plugin, Mob mob) {
        this(mob, plugin::isVanished, Bukkit::isOwnedByCurrentRegion, ThreadLocalRandom::current);
    }

    VanishLookGoal(Mob mob, Predicate<Player> isVanished, Predicate<Entity> ownedByCurrentRegion,
                   Supplier<? extends RandomGenerator> random) {
        this.mob = mob;
        this.isVanished = isVanished;
        this.ownedByCurrentRegion = ownedByCurrentRegion;
        this.random = random;
    }

    @Override
    public boolean shouldActivate() {
        if (random.get().nextFloat() >= LOOK_PROBABILITY) return false;
        lookTarget = nearestVisiblePlayer();
        return lookTarget != null;
    }

    @Override
    public void start() {
        lookTime = MIN_LOOK_TICKS + random.get().nextInt(LOOK_TICKS_SPREAD);
    }

    @Override
    public boolean shouldStayActive() {
        return lookTime > 0 && lookTarget != null && isLookable(lookTarget, mob.getLocation());
    }

    @Override
    public void stop() {
        lookTarget = null;
        lookTime = 0;
    }

    @Override
    public void tick() {
        if (lookTarget != null && ownedByCurrentRegion.test(lookTarget)) {
            mob.lookAt(lookTarget);
        }
        lookTime--;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.LOOK);
    }

    /**
     * Candidates are the players whose view includes the mob's chunk: Paper reads them from the
     * chunk map's per-chunk player list, so the search never visits other entities or players
     * outside view distance. Every player within LOOK_DISTANCE is in the mob's chunk or a
     * neighbour of it, so none is missed.
     */
    private Player nearestVisiblePlayer() {
        Location origin = mob.getLocation();
        Player nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Player player : mob.getWorld().getPlayersSeeingChunk(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
            if (!isLookable(player, origin)) continue;
            double distSq = player.getLocation().distanceSquared(origin);
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    private boolean isLookable(Player player, Location origin) {
        return ownedByCurrentRegion.test(player)
                && player.isValid()
                && !player.isDead()
                && player.getWorld().equals(origin.getWorld())
                && player.getLocation().distanceSquared(origin) <= LOOK_DISTANCE * LOOK_DISTANCE
                && !isVanished.test(player);
    }
}
