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
 * death state, so interface proxies stand in for the mob, players and world and no server is needed.
 */
class VanishLookGoalTest {

    private final List<Player> chunkViewers = new ArrayList<>();
    private final List<Player> worldPlayers = new ArrayList<>();
    private final List<int[]> chunkLookups = new ArrayList<>();
    private int worldPlayerListCalls;

    private final World world = proxy(World.class, (method, args) -> switch (method.getName()) {
        case "getPlayersSeeingChunk" -> {
            if (args.length == 2) chunkLookups.add(new int[] {(int) args[0], (int) args[1]});
            yield new ArrayList<>(chunkViewers);
        }
        case "getPlayers" -> {
            worldPlayerListCalls++;
            yield new ArrayList<>(worldPlayers);
        }
        default -> null;
    });

    private Location mobLocation = new Location(world, 0, 64, 0);
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
    private final Set<Entity> otherRegion = new HashSet<>();
    private final FixedRandom random = new FixedRandom();

    private final VanishLookGoal goal = new VanishLookGoal(mob, vanished::contains,
            entity -> !otherRegion.contains(entity), () -> random);

    @Test
    void shouldActivate_rollAtOrAboveTwoPercent_failsWithoutSearching() {
        FakePlayer nearby = viewer(2);
        for (float roll : new float[] {0.02f, 0.5f, 0.99f}) {
            random.nextFloat = roll;
            assertFalse(goal.shouldActivate(), "roll " + roll + " must not activate the goal");
        }
        assertTrue(chunkLookups.isEmpty(), "a failed roll must not search for players");
        assertEquals(0, nearby.reads, "a failed roll must not read any player");
    }

    @Test
    void shouldActivate_rollBelowTwoPercent_searchesOnceAndTargetsVisiblePlayer() {
        assertEquals(0.02f, VanishLookGoal.LOOK_PROBABILITY, "vanilla LookAtPlayerGoal's default probability");
        Player visible = viewer(3).player;
        random.nextFloat = 0.0199f;

        assertTrue(goal.shouldActivate());
        assertEquals(1, chunkLookups.size());
        startAndTick();
        assertEquals(List.of(visible), lookedAt);
    }

    @Test
    void search_readsOnlyPlayersSeeingTheMobsChunk() {
        mobLocation = new Location(world, 40.5, 64, -20.5);
        Player near = viewerAt(new Location(world, 43, 64, -20)).player;
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate());
        assertArrayEquals(new int[] {2, -2}, chunkLookups.get(0), "lookup is keyed by the mob's chunk");
        startAndTick();
        assertEquals(List.of(near), lookedAt);
        assertEquals(0, worldPlayerListCalls, "the search must not enumerate the world's players");
    }

    @Test
    void search_distantPlayersDoNotAddWork() {
        vanished.add(viewer(1).player);
        random.nextFloat = 0f;
        assertFalse(goal.shouldActivate());
        int readsWithoutDistant = totalReads();

        List<FakePlayer> distant = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            FakePlayer far = new FakePlayer(new Location(world, 500 + i, 64, 500));
            distant.add(far);
            worldPlayers.add(far.player);
        }
        resetReads();
        assertFalse(goal.shouldActivate());

        assertEquals(readsWithoutDistant, totalReads(), "distant players must not increase the search's reads");
        assertTrue(distant.stream().allMatch(far -> far.reads == 0), "distant players are never read");
        assertEquals(0, worldPlayerListCalls);
    }

    @Test
    void shouldActivate_onlyVanishedPlayerInRange_doesNotActivate() {
        vanished.add(viewer(1).player);
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate(), "a vanished player must never become the look target");
    }

    @Test
    void shouldActivate_vanishedPlayerNearer_targetsNearestVisiblePlayer() {
        vanished.add(viewer(1).player);
        viewer(6);
        Player nearestVisible = viewer(4).player;
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate());
        startAndTick();
        assertEquals(List.of(nearestVisible), lookedAt, "the nearest non-vanished player is the target");
    }

    @Test
    void shouldActivate_playerBeyondLookDistance_doesNotActivate() {
        viewer(8.5);
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate());
    }

    @Test
    void shouldActivate_playerOwnedByAnotherRegion_isNeverRead() {
        FakePlayer foreign = viewer(2);
        otherRegion.add(foreign.player);
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate());
        assertEquals(0, foreign.reads, "a player owned by another region must not be read");
    }

    @Test
    void lookTime_isFortyPlusRandomFortyAndCountsDownToTheEnd() {
        assertLookLasts(5, 45);
        assertLookLasts(0, 40);
        assertLookLasts(39, 79);
    }

    @Test
    void shouldStayActive_targetVanishes_endsTheGoal() {
        Player target = viewer(3).player;
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        vanished.add(target);
        chunkLookups.clear();

        assertFalse(goal.shouldStayActive(), "a target that vanishes mid-look must end the goal");
        assertTrue(chunkLookups.isEmpty(), "shouldStayActive must not search for players");
    }

    @Test
    void shouldStayActive_targetMovesToAnotherRegion_endsWithoutReadingOrLooking() {
        FakePlayer target = viewer(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();
        assertTrue(goal.shouldStayActive(), "an owned target keeps the goal active");

        otherRegion.add(target.player);
        target.reads = 0;
        lookedAt.clear();

        assertFalse(goal.shouldStayActive(), "a target owned by another region must end the goal");
        goal.tick();
        assertEquals(0, target.reads, "a target owned by another region must not be read");
        assertTrue(lookedAt.isEmpty(), "the mob must not look at a target owned by another region");
    }

    @Test
    void shouldStayActive_targetLeavesRange_endsTheGoal() {
        FakePlayer target = viewer(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        target.location = new Location(world, 9, 64, 0);

        assertFalse(goal.shouldStayActive());
    }

    @Test
    void shouldStayActive_targetDies_endsTheGoal() {
        FakePlayer target = viewer(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        target.dead = true;

        assertFalse(goal.shouldStayActive());
    }

    private void assertLookLasts(int randomResult, int expectedTicks) {
        chunkViewers.clear();
        lookedAt.clear();
        viewer(3);
        random.nextFloat = 0f;
        random.nextInt = randomResult;

        assertTrue(goal.shouldActivate());
        goal.start();
        assertEquals(40, random.lastBound, "look time is 40 + nextInt(40)");

        for (int i = 0; i < expectedTicks; i++) {
            assertTrue(goal.shouldStayActive(), "must stay active with " + (expectedTicks - i) + " ticks left");
            goal.tick();
        }
        assertFalse(goal.shouldStayActive(), "the goal ends after exactly " + expectedTicks + " ticks");
        assertEquals(expectedTicks, lookedAt.size());
        goal.stop();
    }

    private void startAndTick() {
        goal.start();
        goal.tick();
    }

    private final List<FakePlayer> fakes = new ArrayList<>();

    private FakePlayer viewer(double distance) {
        return viewerAt(new Location(world, distance, 64, 0));
    }

    private FakePlayer viewerAt(Location location) {
        FakePlayer fake = new FakePlayer(location);
        fakes.add(fake);
        chunkViewers.add(fake.player);
        worldPlayers.add(fake.player);
        return fake;
    }

    private int totalReads() {
        return fakes.stream().mapToInt(fake -> fake.reads).sum();
    }

    private void resetReads() {
        fakes.forEach(fake -> fake.reads = 0);
    }

    private static final class FakePlayer {
        Location location;
        boolean dead;
        int reads;
        final Player player = proxy(Player.class, (method, args) -> {
            reads++;
            return switch (method.getName()) {
                case "getLocation" -> location.clone();
                case "getWorld" -> location.getWorld();
                case "isValid" -> true;
                case "isDead" -> dead;
                default -> null;
            };
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
