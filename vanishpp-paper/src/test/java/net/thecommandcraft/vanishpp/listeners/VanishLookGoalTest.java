package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
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
    private EntityType mobType = EntityType.COW;
    private static final double MOB_EYE_HEIGHT = 1.3;
    private static final double PLAYER_EYE_HEIGHT = 1.62;
    private final List<Entity> lookedAt = new ArrayList<>();
    private final List<Double> lookedAtY = new ArrayList<>();
    private final Set<Player> blockedSight = new HashSet<>();
    private final List<Player> sightChecks = new ArrayList<>();
    private final Mob mob = proxy(Mob.class, (method, args) -> switch (method.getName()) {
        case "getLocation" -> copyInto(mobLocation, args);
        case "getWorld" -> world;
        case "getType" -> mobType;
        case "getEyeHeight" -> MOB_EYE_HEIGHT;
        case "hasLineOfSight" -> {
            Player player = (Player) args[0];
            sightChecks.add(player);
            yield !blockedSight.contains(player);
        }
        case "lookAt" -> {
            if (args.length == 1 && args[0] instanceof Entity entity) lookedAt.add(entity);
            if (args.length == 3) {
                double x = (double) args[0], z = (double) args[2];
                fakes.stream().filter(f -> f.location.getX() == x && f.location.getZ() == z)
                        .findFirst().ifPresent(f -> lookedAt.add(f.player));
                lookedAtY.add((double) args[1]);
            }
            yield null;
        }
        default -> null;
    });

    private final Set<Player> vanished = new HashSet<>();
    private final Set<Entity> otherRegion = new HashSet<>();
    private final FixedRandom random = new FixedRandom();

    private VanishLookGoal goal = goalWith(VanishLookGoal.Settings.DEFAULT);

    private VanishLookGoal goalWith(VanishLookGoal.Settings settings) {
        return new VanishLookGoal(mob, settings, vanished::contains, entity -> !otherRegion.contains(entity), () -> random);
    }

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
        assertEquals(0.02f, VanishLookGoal.Settings.DEFAULT.probability(), "vanilla LookAtPlayerGoal's default probability");
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
    void shouldActivate_vanishedPlayer_isNeverReadPastTheVanishCheck() {
        FakePlayer hidden = nearby(1);
        vanished.add(hidden.player);
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate());
        assertEquals(0, hidden.reads, "a vanished player is skipped before anything about them is read");
        assertTrue(sightChecks.isEmpty(), "no line of sight check for a vanished player");
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

    // ── vanilla TargetingConditions ───────────────────────────────────────────

    @Test
    void spectator_isNeverTheTarget() {
        nearby(2).gameMode = GameMode.SPECTATOR;
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate(), "vanilla mobs don't look at spectators");
    }

    @Test
    void sneakingPlayer_isSeenFromEightyPercentOfTheRange() {
        FakePlayer sneaking = nearby(6.5);
        sneaking.sneaking = true;
        random.nextFloat = 0f;
        assertFalse(goal.shouldActivate(), "6.5 blocks is past 8 * 0.8");

        sneaking.location = new Location(world, 6.3, 64, 0);
        assertTrue(goal.shouldActivate(), "6.3 blocks is within 8 * 0.8");
    }

    @Test
    void invisiblePlayerWithoutArmor_isOnlySeenFromTwoBlocks() {
        FakePlayer invisible = nearby(2.5);
        invisible.invisible = true;
        random.nextFloat = 0f;
        assertFalse(goal.shouldActivate(), "8 * 0.7 * 0.1 is below the 2 block minimum");

        invisible.location = new Location(world, 1.9, 64, 0);
        assertTrue(goal.shouldActivate());
    }

    @Test
    void invisiblePlayerInFullArmor_isSeenFromSeventyPercent() {
        FakePlayer invisible = nearby(5.5);
        invisible.invisible = true;
        invisible.armor = new Material[] {Material.IRON_BOOTS, Material.IRON_LEGGINGS, Material.IRON_CHESTPLATE, Material.IRON_HELMET};
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate(), "5.5 is within 8 * 0.7");
        invisible.location = new Location(world, 5.7, 64, 0);
        assertFalse(goal.shouldActivate(), "5.7 is past 8 * 0.7");
    }

    @Test
    void playerWearingTheMobsOwnHead_isSeenFromHalfTheRange() {
        mobType = EntityType.ZOMBIE;
        FakePlayer disguised = nearby(5);
        disguised.armor = new Material[] {null, null, null, Material.ZOMBIE_HEAD};
        random.nextFloat = 0f;
        assertFalse(goal.shouldActivate(), "a zombie sees a zombie head from 4 blocks");

        mobType = EntityType.SKELETON;
        assertTrue(goal.shouldActivate(), "other heads don't count");
    }

    @Test
    void playerRidingTheMob_isNeverTheTarget() {
        FakePlayer rider = nearby(0.5);
        rider.vehicle = mob;
        random.nextFloat = 0f;

        assertFalse(goal.shouldActivate(), "vanilla mobs don't look at their own rider");
    }

    @Test
    void lineOfSight_checkedNearestFirst_andStopsAtTheFirstSeenPlayer() {
        Player behindWall = nearby(2).player;
        Player seen = nearby(4).player;
        Player farther = nearby(6).player;
        blockedSight.add(behindWall);
        random.nextFloat = 0f;

        assertTrue(goal.shouldActivate());
        assertEquals(List.of(behindWall, seen), sightChecks, "nearest first, and the farther one is never ray traced");
        startAndTick();
        assertEquals(List.of(seen), lookedAt);
        assertFalse(sightChecks.contains(farther));
    }

    @Test
    void lineOfSight_isNotRecheckedWhileLooking() {
        nearby(3);
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        goal.start();
        sightChecks.clear();

        for (int i = 0; i < 5; i++) {
            assertTrue(goal.shouldStayActive());
            goal.tick();
        }
        assertTrue(sightChecks.isEmpty(), "like vanilla, an ongoing look doesn't need line of sight");
    }

    // ── per-goal settings ─────────────────────────────────────────────────────

    @Test
    void settings_probabilityOne_alwaysSearches() {
        goal = goalWith(new VanishLookGoal.Settings(3.0f, 1.0f, false));
        nearby(2);
        random.nextFloat = 0.99f;

        assertTrue(goal.shouldActivate(), "illagers look at near players with a 100% chance");
    }

    @Test
    void settings_lookDistance_limitsSearchAndLook() {
        goal = goalWith(new VanishLookGoal.Settings(3.0f, 0.02f, false));
        FakePlayer player = nearby(3.5);
        random.nextFloat = 0f;
        assertFalse(goal.shouldActivate());

        player.location = new Location(world, 2.5, 64, 0);
        assertTrue(goal.shouldActivate());
        goal.start();
        player.location = new Location(world, 3.5, 64, 0);
        assertFalse(goal.shouldStayActive(), "the look ends once past the goal's own range");
    }

    @Test
    void settings_onlyHorizontal_keepsTheMobsEyeLevel() {
        goal = goalWith(new VanishLookGoal.Settings(8.0f, 0.02f, true));
        FakePlayer player = at(new Location(world, 3, 66, 0));
        random.nextFloat = 0f;
        assertTrue(goal.shouldActivate());
        startAndTick();
        assertEquals(List.of(64 + MOB_EYE_HEIGHT), lookedAtY);

        goal = goalWith(VanishLookGoal.Settings.DEFAULT);
        lookedAtY.clear();
        assertTrue(goal.shouldActivate());
        startAndTick();
        assertEquals(List.of(player.location.getY() + PLAYER_EYE_HEIGHT), lookedAtY, "normally at the player's eyes");
    }

    @Test
    void settings_readFromTheVanillaGoal() {
        VanishLookGoal.Settings read = VanishLookGoal.Settings.of(
                new FakePaperGoal(new LookAtPlayerGoal(net.minecraft.world.entity.player.Player.class, 3.0f, 1.0f, true)));
        assertEquals(new VanishLookGoal.Settings(3.0f, 1.0f, true), read);
    }

    @Test
    void settings_goalLookingAtOtherMobs_isLeftAlone() {
        assertNull(VanishLookGoal.Settings.of(new FakePaperGoal(new LookAtPlayerGoal(Object.class, 8.0f, 0.02f, false))));
    }

    @Test
    void settings_unreadable_fallBackToVanillaDefaults() {
        assertEquals(VanishLookGoal.Settings.DEFAULT, VanishLookGoal.Settings.of(new FakePaperGoal(new Object())));
        assertEquals(VanishLookGoal.Settings.DEFAULT, VanishLookGoal.Settings.of(goal));
    }

    // ── look time ─────────────────────────────────────────────────────────────

    @Test
    void lookTime_isFortyPlusRandomFortyGameTicks_countedInGoalTicks() {
        // Paper ticks the goal every other game tick, so 40-79 game ticks are 20-40 goal ticks
        assertLookLasts(5, 23);
        assertLookLasts(0, 20);
        assertLookLasts(39, 40);
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

    /** getLocation() returns a copy, getLocation(Location) fills the given one, like Bukkit. */
    private static Location copyInto(Location source, Object[] args) {
        if (args.length == 1 && args[0] instanceof Location target) {
            target.setWorld(source.getWorld());
            target.setX(source.getX());
            target.setY(source.getY());
            target.setZ(source.getZ());
            return target;
        }
        return source.clone();
    }

    private static final class FakePlayer {
        Location location;
        boolean dead;
        boolean sneaking;
        boolean invisible;
        GameMode gameMode = GameMode.SURVIVAL;
        Entity vehicle;
        Material[] armor = new Material[4]; // boots, leggings, chestplate, helmet
        int reads;
        final Player player = proxy(Player.class, (method, args) -> {
            reads++;
            return switch (method.getName()) {
                case "getLocation" -> copyInto(location, args);
                case "getWorld" -> location.getWorld();
                case "isValid" -> true;
                case "isDead" -> dead;
                case "isSneaking" -> sneaking;
                case "isInvisible" -> invisible;
                case "getGameMode" -> gameMode;
                case "getVehicle" -> vehicle;
                case "getEyeHeight" -> PLAYER_EYE_HEIGHT;
                case "getInventory" -> inventory();
                default -> null;
            };
        });

        FakePlayer(Location location) {
            this.location = location;
        }

        private PlayerInventory inventory() {
            return proxy(PlayerInventory.class, (method, args) -> switch (method.getName()) {
                case "getArmorContents" -> {
                    ItemStack[] items = new ItemStack[4];
                    for (int i = 0; i < 4; i++) items[i] = item(armor[i]);
                    yield items;
                }
                case "getHelmet" -> item(armor[3]);
                default -> null;
            });
        }

        private static ItemStack item(Material material) {
            // a proxy-free stand-in: only getType() is read
            return material == null ? null : new ItemStack(material) {
                @Override
                public Material getType() {
                    return material;
                }
            };
        }
    }

    /** Shaped like the server's vanilla goal: the fields VanishLookGoal.Settings reads. */
    static class LookAtPlayerGoal {
        protected final Class<?> lookAtType;
        protected final float lookDistance;
        private final float probability;
        private final boolean onlyHorizontal;

        LookAtPlayerGoal(Class<?> lookAtType, float lookDistance, float probability, boolean onlyHorizontal) {
            this.lookAtType = lookAtType;
            this.lookDistance = lookDistance;
            this.probability = probability;
            this.onlyHorizontal = onlyHorizontal;
        }
    }

    /** Shaped like Paper's PaperGoal: the API goal wrapping a vanilla one. */
    public static class FakePaperGoal implements Goal<Mob> {
        private final Object handle;

        FakePaperGoal(Object handle) {
            this.handle = handle;
        }

        public Object getHandle() {
            return handle;
        }

        @Override
        public boolean shouldActivate() {
            return false;
        }

        @Override
        public GoalKey<Mob> getKey() {
            return GoalKey.of(Mob.class, org.bukkit.NamespacedKey.minecraft("look_at_player"));
        }

        @Override
        public EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.LOOK);
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
