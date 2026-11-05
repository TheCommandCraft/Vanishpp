package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Replaces a vanilla LookAtPlayerGoal on a per-mob basis and behaves exactly like it, except that
 * vanished players are never picked and a target that vanishes mid-look ends the goal at once:
 * <ul>
 *   <li>a roll against the goal's chance (2% for most mobs) before anything is searched</li>
 *   <li>the nearest player the mob can see, within the goal's range (8 blocks for most mobs),
 *       shrunk for sneaking, invisible or head-wearing players the way vanilla does</li>
 *   <li>no spectators, no players riding the mob</li>
 *   <li>a 40-79 tick look that ends early if the target dies or walks out of range</li>
 * </ul>
 * Because vanished players are left out here, mobs don't need the invisibility potion to ignore them.
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

    static final int MIN_LOOK_TICKS = 40;
    static final int LOOK_TICKS_SPREAD = 40;
    /** Vanilla never shrinks the range below this, however hidden the player is. */
    private static final double MIN_RANGE = 2.0;

    /** The range, chance and head movement of the vanilla goal this one replaces. */
    public record Settings(float lookDistance, float probability, boolean onlyHorizontal) {

        /** Vanilla LookAtPlayerGoal's defaults. */
        public static final Settings DEFAULT = new Settings(8.0f, 0.02f, false);

        private static final String PLAYER_CLASS = "net.minecraft.world.entity.player.Player";
        private static volatile Field[] fields; // lookAtType, lookDistance, probability, onlyHorizontal

        /**
         * Reads the settings of a vanilla LOOK_AT_PLAYER goal. Paper has no API for them, so this
         * reads the goal's fields; if that fails the vanilla defaults are used. Returns null when
         * the goal looks at something other than players, those are left alone.
         */
        public static Settings of(Goal<?> vanilla) {
            Object handle;
            try {
                handle = vanilla.getClass().getMethod("getHandle").invoke(vanilla);
            } catch (Throwable t) {
                return DEFAULT;
            }
            try {
                Field[] f = fields;
                if (f == null || f[0].getDeclaringClass() != lookGoalClass(handle.getClass())) {
                    Class<?> c = lookGoalClass(handle.getClass());
                    f = new Field[] {c.getDeclaredField("lookAtType"), c.getDeclaredField("lookDistance"),
                            c.getDeclaredField("probability"), c.getDeclaredField("onlyHorizontal")};
                    for (Field field : f) field.setAccessible(true);
                    fields = f;
                }
                if (!(f[0].get(handle) instanceof Class<?> type) || !type.getName().equals(PLAYER_CLASS))
                    return null;
                return new Settings(f[1].getFloat(handle), f[2].getFloat(handle), f[3].getBoolean(handle));
            } catch (Throwable t) {
                return DEFAULT;
            }
        }

        private static Class<?> lookGoalClass(Class<?> c) throws ClassNotFoundException {
            for (Class<?> k = c; k != null; k = k.getSuperclass())
                if (k.getSimpleName().equals("LookAtPlayerGoal")) return k;
            throw new ClassNotFoundException("LookAtPlayerGoal");
        }
    }

    private final Mob mob;
    private final Settings settings;
    private final Predicate<Player> isVanished;
    /** On Folia an entity's state may only be read from the region thread that owns it. */
    private final Predicate<Entity> ownedByCurrentRegion;
    // A supplier, not a stored instance: ThreadLocalRandom.current() belongs to the calling
    // thread, and on Folia the mob's region thread can change.
    private final Supplier<? extends RandomGenerator> random;
    // Reused so the per-tick checks don't allocate. Only ever touched by the mob's own thread.
    private final Location mobPos = new Location(null, 0, 0, 0);
    private final Location targetPos = new Location(null, 0, 0, 0);

    private Player lookTarget;
    private int lookTime;

    public VanishLookGoal(Vanishpp plugin, Mob mob) {
        this(plugin, mob, Settings.DEFAULT);
    }

    public VanishLookGoal(Vanishpp plugin, Mob mob, Settings settings) {
        this(mob, settings, plugin::isVanished, Bukkit::isOwnedByCurrentRegion, ThreadLocalRandom::current);
    }

    VanishLookGoal(Mob mob, Settings settings, Predicate<Player> isVanished, Predicate<Entity> ownedByCurrentRegion,
                   Supplier<? extends RandomGenerator> random) {
        this.mob = mob;
        this.settings = settings;
        this.isVanished = isVanished;
        this.ownedByCurrentRegion = ownedByCurrentRegion;
        this.random = random;
    }

    @Override
    public boolean shouldActivate() {
        if (random.get().nextFloat() >= settings.probability()) return false;
        lookTarget = nearestVisiblePlayer();
        return lookTarget != null;
    }

    @Override
    public void start() {
        // Paper ticks custom goals every other tick, like vanilla goals that don't need every
        // tick, so the count is halved the way vanilla's adjustedTickDelay does: 40-79 game ticks.
        lookTime = (MIN_LOOK_TICKS + random.get().nextInt(LOOK_TICKS_SPREAD) + 1) / 2;
    }

    @Override
    public boolean shouldStayActive() {
        if (lookTime <= 0 || lookTarget == null || !isAvailable(lookTarget)) return false;
        mob.getLocation(mobPos);
        lookTarget.getLocation(targetPos);
        double range = settings.lookDistance();
        return targetPos.getWorld() == mobPos.getWorld() && targetPos.distanceSquared(mobPos) <= range * range;
    }

    @Override
    public void stop() {
        lookTarget = null;
        lookTime = 0;
    }

    @Override
    public void tick() {
        if (lookTarget != null && ownedByCurrentRegion.test(lookTarget)) {
            lookTarget.getLocation(targetPos);
            double y = settings.onlyHorizontal()
                    ? mob.getLocation(mobPos).getY() + mob.getEyeHeight()
                    : targetPos.getY() + lookTarget.getEyeHeight();
            mob.lookAt(targetPos.getX(), y, targetPos.getZ());
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

    private record Candidate(Player player, double distSq) {
    }

    /**
     * Candidates come from the server's entity lookup for the box around the mob, so players
     * outside it are never visited. Line of sight is the only costly check, so it's done last,
     * nearest first, and stops at the first player the mob can see.
     */
    private Player nearestVisiblePlayer() {
        Location origin = mob.getLocation(mobPos);
        double box = Math.max(settings.lookDistance(), MIN_RANGE);
        double eyeY = origin.getY() + mob.getEyeHeight();
        List<Candidate> candidates = null;
        for (Entity entity : mob.getWorld().getNearbyEntities(BoundingBox.of(origin, box, box, box),
                candidate -> candidate instanceof Player)) {
            Player player = (Player) entity;
            if (!isAvailable(player) || player.getGameMode() == GameMode.SPECTATOR || isRiding(player)) continue;
            Location pos = player.getLocation(targetPos);
            if (pos.getWorld() != origin.getWorld()) continue;
            double range = Math.max(settings.lookDistance() * visibility(player), MIN_RANGE);
            if (pos.distanceSquared(origin) > range * range) continue;
            // vanilla picks the nearest by distance from the mob's eyes
            double dx = pos.getX() - origin.getX(), dy = pos.getY() - eyeY, dz = pos.getZ() - origin.getZ();
            if (candidates == null) candidates = new ArrayList<>(2);
            candidates.add(new Candidate(player, dx * dx + dy * dy + dz * dz));
        }
        if (candidates == null) return null;
        if (candidates.size() > 1) candidates.sort(Comparator.comparingDouble(Candidate::distSq));
        for (Candidate candidate : candidates)
            if (mob.hasLineOfSight(candidate.player())) return candidate.player();
        return null;
    }

    /** Owned by this thread (checked first, before anything is read), alive and not vanished. */
    private boolean isAvailable(Player player) {
        return ownedByCurrentRegion.test(player)
                && !isVanished.test(player)
                && player.isValid()
                && !player.isDead();
    }

    /** Vanilla doesn't look at players riding the mob, directly or further up the stack. */
    private boolean isRiding(Player player) {
        for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
            if (vehicle.equals(mob)) return true;
        return false;
    }

    /** Vanilla's LivingEntity#getVisibilityPercent: how much of the range a player is seen from. */
    private double visibility(Player player) {
        double visibility = 1.0;
        if (player.isSneaking()) visibility *= 0.8;
        if (player.isInvisible()) {
            int worn = 0;
            for (ItemStack armor : player.getInventory().getArmorContents())
                if (armor != null && !armor.getType().isAir()) worn++;
            visibility *= 0.7 * Math.max(0.1, worn / 4.0);
        }
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && helmet.getType() == ownHead()) visibility *= 0.5;
        return visibility;
    }

    /**
     * The head that makes this mob type see a player wearing it from half as far. Newer versions
     * keep this as an item component, these are its defaults.
     */
    private Material ownHead() {
        // == instead of an enum switch, which would break if a server's EntityType differs
        EntityType type = mob.getType();
        if (type == EntityType.SKELETON) return Material.SKELETON_SKULL;
        if (type == EntityType.ZOMBIE) return Material.ZOMBIE_HEAD;
        if (type == EntityType.CREEPER) return Material.CREEPER_HEAD;
        if (type == EntityType.PIGLIN || type == EntityType.PIGLIN_BRUTE) return Material.PIGLIN_HEAD;
        return null;
    }
}
