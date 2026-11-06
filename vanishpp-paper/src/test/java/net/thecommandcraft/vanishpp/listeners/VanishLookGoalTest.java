package net.thecommandcraft.vanishpp.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VanishLookGoal against stand-in entities: the goal only reads location, world, validity and
 * death state, so interface proxies stand in for the mob, players and world and no server is needed.
 * The world answers box queries from each player's position, the way the server's entity lookup does.
 */
class VanishLookGoalTest {

    private final List<FakePlayer> fakes = new ArrayList<>();
    private final List<Player> chunkViewers = new ArrayList<>();
    private final List<BoundingBox> boxQueries = new ArrayList<>();
    private int audienceLookups;

    @SuppressWarnings("unchecked")
    private final World world = proxy(World.class, (method, args) -> switch (method.getName()) {
        case "getNearbyEntities" -> {
            if (args.length != 2 || !(args[0] instanceof BoundingBox box)) yield null;
            boxQueries.add(box.clone());
            Predicate<? super Entity> filter = (Predicate<? super Entity>) args[1];
            List<Entity> found = new ArrayList<>();
            for (FakePlayer fake : fakes) {
                if (box.contains(fake.location.toVector()) && (filter == null || filter.test(fake.player))) {
                    found.add(fake.player);
                }
            }
            yield found;
        }
        case "getPlayersSeeingChunk", "getPlayers" -> {
            audienceLookups++;
            yield new ArrayList<>(chunkViewers);
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
        FakePlayer nearby = nearby(2);
        for (float roll : new float[] {0.02f, 0.5f, 0.99f}) {
            random.nextFloat = roll;
            assertFalse(goal.shouldActivate(), "roll " + roll + " must not activate the goal");
        }
        assertTrue(boxQueries.isEmpty(), "a failed roll must not search for players");
        assertEquals(0, nearby.reads, "a failed roll must not read any player");
    }

    @Test
    void shouldActivate_rollBelowTwoPercent_searchesOnceAndTargetsVisiblePlayer() {
        assertEquals(0.02f, VanishLookGoal.LOOK_PROBABILITY, "vanilla LookAtPlayerGoal's default probability");
        Player visible = nearby(3).player;
        random.nextFloat = 0.0199f;

        assertTrue(goal.shouldActivate());
        assertEquals(1, boxQueries.size());
        startAndTick();
        assertEquals(List.of(visible), lookedAt);
    }

    @Test
    void search_isTheEightBlockBoxAroundTheMob() {
        mobLocation = new Location(world, 40.5, 64, -20.5);
        Player near = at(new Location(world, 43, 64, -20)).player;
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate());
        assertEquals(1, boxQueries.size());
        BoundingBox box = boxQueries.get(0);
        assertEquals(32.5, box.getMinX(), 1e-9);
        assertEquals(56, box.getMinY(), 1e-9);
        assertEquals(-28.5, box.getMinZ(), 1e-9);
        assertEquals(48.5, box.getMaxX(), 1e-9);
        assertEquals(72, box.getMaxY(), 1e-9);
        assertEquals(-12.5, box.getMaxZ(), 1e-9);
        startAndTick();
        assertEquals(List.of(near), lookedAt);
        assertEquals(0, audienceLookups, "the search must not use the world's or a chunk's player list");
    }

    @Test
    void search_nearestLocalPlayerMissingFromTheChunksViewers_isStillTheTarget() {
        Player nearest = nearby(2).player;
        FakePlayer fartherViewer = nearby(5);
        chunkViewers.add(fartherViewer.player);
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate());
        startAndTick();
        assertEquals(List.of(nearest), lookedAt, "candidates must not depend on which clients were sent the chunk");
    }

    @Test
    void search_playerInsideTheBoxButBeyondLookDistance_isNotTheTarget() {
        at(new Location(world, 7, 64, 7));
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate(), "a box corner is farther than 8 blocks");
    }

    @Test
    void search_distantPlayersAndViewersDoNotAddWork() {
        vanished.add(nearby(1).player);
        random.nextFloat = 0f;
        assertFalse(goal.shouldActivate());
        int readsWithoutDistant = totalReads();

        List<FakePlayer> distant = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            FakePlayer viewer = at(new Location(world, 64 + i, 64, 0));
            chunkViewers.add(viewer.player);
            distant.add(viewer);
            distant.add(at(new Location(world, 0, 64 + 40 + i, 0)));
            distant.add(at(new Location(world, 500 + i, 64, 500)));
        }
        resetReads();
        assertFalse(goal.shouldActivate());

        assertEquals(readsWithoutDistant, totalReads(), "distant players must not increase the search's reads");
        assertTrue(distant.stream().allMatch(far -> far.reads == 0), "distant players are never read");
        assertEquals(0, audienceLookups);
    }

    @Test
    void shouldActivate_onlyVanishedPlayerInRange_doesNotActivate() {
        vanished.add(nearby(1).player);
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate(), "a vanished player must never become the look target");
    }

    @Test
    void shouldActivate_vanishedPlayerNearer_targetsNearestVisiblePlayer() {
        vanished.add(nearby(1).player);
        nearby(6);
        Player nearestVisible = nearby(4).player;
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate());
        startAndTick();
        assertEquals(List.of(nearestVisible), lookedAt, "the nearest non-vanished player is the target");
    }

    @Test
    void shouldActivate_playerBeyondLookDistance_doesNotActivate() {
        nearby(8.5);
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate());
    }

    @Test
    void shouldActivate_playerOwnedByAnotherRegion_isNeverRead() {
        FakePlayer foreign = nearby(2);
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
        Player target = nearby(3).player;
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        vanished.add(target);
        boxQueries.clear();

        assertFalse(goal.shouldStayActive(), "a target that vanishes mid-look must end the goal");
        assertTrue(boxQueries.isEmpty(), "shouldStayActive must not search for players");
    }

    @Test
    void shouldStayActive_targetMovesToAnotherRegion_endsWithoutReadingOrLooking() {
        FakePlayer target = nearby(3);
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
        FakePlayer target = nearby(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        target.location = new Location(world, 9, 64, 0);

        assertFalse(goal.shouldStayActive());
    }

    @Test
    void shouldStayActive_targetDies_endsTheGoal() {
        FakePlayer target = nearby(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();

        target.dead = true;

        assertFalse(goal.shouldStayActive());
    }

    private void assertLookLasts(int randomResult, int expectedTicks) {
        fakes.clear();
        lookedAt.clear();
        nearby(3);
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

    private FakePlayer nearby(double distance) {
        return at(new Location(world, distance, 64, 0));
    }

    private FakePlayer at(Location location) {
        FakePlayer fake = new FakePlayer(location);
        fakes.add(fake);
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
