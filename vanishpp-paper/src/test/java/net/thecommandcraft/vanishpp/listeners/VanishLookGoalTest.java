package net.thecommandcraft.vanishpp.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VanishLookGoal against stand-in entities: the goal only reads location, world, validity and
 * death state, so interface proxies stand in for the mob and players and no server is needed.
 */
class VanishLookGoalTest {

    private final World world = proxy(World.class, (method, args) -> null);
    private final Location mobLocation = new Location(world, 0, 64, 0);
    private final List<Entity> lookedAt = new ArrayList<>();
    private final Mob mob = proxy(Mob.class, (method, args) -> switch (method.getName()) {
        case "getLocation" -> mobLocation.clone();
        case "getWorld" -> world;
        case "lookAt" -> {
            if (args.length == 1 && args[0] instanceof Entity entity) lookedAt.add(entity);
            yield null;
        }
        default -> null;
    });

    private final Set<Player> vanished = new HashSet<>();
    private final List<Player> players = new ArrayList<>();
    private final FixedRandom random = new FixedRandom();
    private int searches;

    private final VanishLookGoal goal = new VanishLookGoal(mob, vanished::contains, () -> {
        searches++;
        return players;
    }, () -> random);

    @Test
    void shouldActivate_rollAtOrAboveTwoPercent_failsWithoutSearching() {
        player(2);
        for (float roll : new float[] {VanishLookGoal.LOOK_PROBABILITY, 0.5f, 0.99f}) {
            random.nextFloat = roll;
            assertFalse(goal.shouldActivate(), "roll " + roll + " must not activate the goal");
        }
        assertEquals(0, searches, "a failed roll must not search for players");
    }

    @Test
    void shouldActivate_rollBelowTwoPercent_searchesOnceAndTargetsVisiblePlayer() {
        assertEquals(0.02f, VanishLookGoal.LOOK_PROBABILITY, "vanilla LookAtPlayerGoal's default probability");
        Player visible = player(3);
        random.nextFloat = 0.0199f;

        assertTrue(goal.shouldActivate());
        assertEquals(1, searches);
        startAndTick();
        assertEquals(List.of(visible), lookedAt);
    }

    @Test
    void shouldActivate_onlyVanishedPlayerInRange_doesNotActivate() {
        vanished.add(player(1));
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate(), "a vanished player must never become the look target");
    }

    @Test
    void shouldActivate_vanishedPlayerNearer_targetsNearestVisiblePlayer() {
        vanished.add(player(1));
        player(6);
        Player nearestVisible = player(4);
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate());
        startAndTick();
        assertEquals(List.of(nearestVisible), lookedAt, "the nearest non-vanished player is the target");
    }

    @Test
    void shouldActivate_playerBeyondLookDistance_doesNotActivate() {
        player(8.5);
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate());
    }

    @Test
    void lookTime_countsDownAndEndsTheGoal() {
        player(3);
        random.nextFloat = 0f;
        random.nextInt = 5;

        assertTrue(goal.shouldActivate());
        goal.start();
        assertEquals(VanishLookGoal.LOOK_TICKS_SPREAD, random.lastBound, "look time is 40 + nextInt(40)");

        int ticks = VanishLookGoal.MIN_LOOK_TICKS + 5;
        for (int i = 0; i < ticks; i++) {
            assertTrue(goal.shouldStayActive(), "must stay active with " + (ticks - i) + " ticks left");
            goal.tick();
        }
        assertFalse(goal.shouldStayActive(), "the goal ends once the look time reaches 0");
        assertEquals(ticks, lookedAt.size());
    }

    @Test
    void shouldStayActive_targetVanishes_endsTheGoal() {
        Player target = player(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        vanished.add(target);
        searches = 0;

        assertFalse(goal.shouldStayActive(), "a target that vanishes mid-look must end the goal");
        assertEquals(0, searches, "shouldStayActive must not search for players");
    }

    @Test
    void shouldStayActive_targetLeavesRange_endsTheGoal() {
        FakePlayer target = fakePlayer(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        target.location = new Location(world, 9, 64, 0);

        assertFalse(goal.shouldStayActive());
    }

    @Test
    void shouldStayActive_targetDies_endsTheGoal() {
        FakePlayer target = fakePlayer(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        target.dead = true;

        assertFalse(goal.shouldStayActive());
    }

    private void startAndTick() {
        goal.start();
        goal.tick();
    }

    private Player player(double distance) {
        return fakePlayer(distance).player;
    }

    private FakePlayer fakePlayer(double distance) {
        FakePlayer fake = new FakePlayer(new Location(world, distance, 64, 0));
        players.add(fake.player);
        return fake;
    }

    private static final class FakePlayer {
        Location location;
        boolean dead;
        final Player player = proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getLocation" -> location.clone();
            case "getWorld" -> location.getWorld();
            case "isValid" -> true;
            case "isDead" -> dead;
            default -> null;
        });

        FakePlayer(Location location) {
            this.location = location;
        }
    }

    private static final class FixedRandom implements RandomGenerator {
        float nextFloat;
        int nextInt;
        int lastBound = -1;

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public float nextFloat() {
            return nextFloat;
        }

        @Override
        public int nextInt(int bound) {
            lastBound = bound;
            return nextInt;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            Object[] safeArgs = args == null ? new Object[0] : args;
            switch (method.getName()) {
                case "equals":
                    return self == safeArgs[0];
                case "hashCode":
                    return System.identityHashCode(self);
                case "toString":
                    return type.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(self));
                default:
                    break;
            }
            Object result = answer.apply(method, safeArgs);
            Class<?> returnType = method.getReturnType();
            if (result == null && returnType.isPrimitive() && returnType != void.class) {
                return Array.get(Array.newInstance(returnType, 1), 0);
            }
            return result;
        });
    }
}
