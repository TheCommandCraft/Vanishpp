package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.listeners.PlayerListener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.Bukkit;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.*;
import net.thecommandcraft.vanishpp.utils.ViewerTeams;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ViewerTeamLifecycleTest {
    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock subject, viewer;
    private final Set<Objective> readOnlySources = Collections.newSetFromMap(new IdentityHashMap<>());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        subject = server.addPlayer("Hidden");
        viewer = server.addPlayer("Staff");
        viewer.setOp(true);
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    @Test
    void subjectQuitRetiresOnlyItsMembershipAndRestoresLastOverlay() {
        Scoreboard original = viewer.getScoreboard();
        PlayerMock second = server.addPlayer("Second");
        plugin.applyVanishEffects(subject);
        plugin.applyVanishEffects(second);
        PlayerListener listener = new PlayerListener(plugin);
        PlayerQuitEvent quit = new PlayerQuitEvent(subject, Component.empty());
        listener.onQuit(quit);
        assertFalse(viewer.getScoreboard().getTeam("Vanishpp_Vanished").hasEntry(subject.getName()),
                "departed subjects must leave every private team");
        assertTrue(viewer.getScoreboard().getTeam("Vanishpp_Vanished").hasEntry(second.getName()));
        listener.onQuit(quit);
        assertTrue(viewer.getScoreboard().getTeam("Vanishpp_Vanished").hasEntry(second.getName()));
        listener.onQuit(new PlayerQuitEvent(second, Component.empty()));
        assertSame(original, viewer.getScoreboard(), "last subject quit must restore the original scoreboard");
        assertTrue(plugin.isVanished(subject), "quit cleanup must preserve vanished state for reconnect");
    }

    @Test
    void subjectQuitAlsoRetiresPacketFilteredSharedMembership() throws Exception {
        var field = Vanishpp.class.getDeclaredField("hasProtocolLib");
        field.setAccessible(true);
        field.set(plugin, true);
        plugin.applyVanishEffects(subject);
        Team team = server.getScoreboardManager().getMainScoreboard().getTeam("Vanishpp_Vanished");
        assertTrue(team.hasEntry(subject.getName()));
        new PlayerListener(plugin).onQuit(new PlayerQuitEvent(subject, Component.empty()));
        assertFalse(team.hasEntry(subject.getName()), "quit must retire shared membership before new viewers learn offline names");
        assertTrue(plugin.isVanished(subject));
    }

    @Test
    void personalTeamMembershipAndOptionsSurviveVanishAndUnvanish() {
        Scoreboard original = server.getScoreboardManager().getNewScoreboard();
        Team team = original.registerNewTeam("builders");
        team.prefix(Component.text("Builder"));
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.ALWAYS);
        team.addEntry(subject.getName());
        viewer.setScoreboard(original);
        plugin.applyVanishEffects(subject);
        assertTrue(team.hasEntry(subject.getName()), "private overlays must not steal the owner's team membership");
        assertNotSame(original, viewer.getScoreboard());
        plugin.removeVanishEffects(subject);
        assertSame(original, viewer.getScoreboard());
        assertTrue(team.hasEntry(subject.getName()));
        assertEquals(Component.text("Builder"), team.prefix());
        assertEquals(Team.OptionStatus.ALWAYS, team.getOption(Team.Option.COLLISION_RULE));
        assertNull(original.getTeam("Vanishpp_Vanished"), "foreign personal boards must not retain plugin teams");
    }

    @Test
    void disablingRestoresPersonalScoreboardWithoutMutatingItsTeams() {
        Scoreboard original = server.getScoreboardManager().getNewScoreboard();
        Team team = original.registerNewTeam("original");
        team.addEntry(subject.getName());
        viewer.setScoreboard(original);
        plugin.applyVanishEffects(subject);
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(team.hasEntry(subject.getName()), "disable must preserve the original personal team membership");
        assertSame(original, viewer.getScoreboard());
        assertNull(original.getTeam("Vanishpp_Vanished"));
    }

    @Test
    void originalScoreboardUpdatesRemainVisibleThroughOverlay() {
        ScoreboardManager nativeManager = server.getScoreboardManager();
        ScoreboardManager manager = mock(ScoreboardManager.class);
        Scoreboard original = formattingScoreboard(nativeManager.getMainScoreboard());
        when(manager.getMainScoreboard()).thenReturn(original);
        when(manager.getNewScoreboard()).thenAnswer(call -> formattingScoreboard(nativeManager.getNewScoreboard()));
        viewer.setScoreboard(original);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
        Objective score = original.registerNewObjective("points", "dummy", Component.text("Points"));
        score.getScore("public").setScore(5);
        Team team = original.registerNewTeam("builders");
        for (Team.Option option : Team.Option.values()) team.setOption(option, Team.OptionStatus.ALWAYS);
        team.prefix(Component.text("Before"));
        team.addEntry("public");
        plugin.applyVanishEffects(subject);
        reconcile();
        assertEquals(5, viewer.getScoreboard().getObjective("points").getScore("public").getScore());
        score.getScore("public").setScore(9);
        score.displayName(Component.text("Updated"));
        team.prefix(Component.text("After"));
        team.addEntry("Visible");
        plugin.reapplyTeamEntry(subject);
        reconcile();
        Scoreboard shown = viewer.getScoreboard();
        assertEquals(9, shown.getObjective("points").getScore("public").getScore(),
                "private overlay must continue showing original scoreboard updates");
        assertEquals(Component.text("Updated"), shown.getObjective("points").displayName());
        assertEquals(Component.text("After"), shown.getTeam("builders").prefix());
        assertTrue(shown.getTeam("builders").hasEntry("Visible"));
        score.unregister();
        team.unregister();
        plugin.reapplyTeamEntry(subject);
        reconcile();
        assertNull(shown.getObjective("points"));
        assertNull(shown.getTeam("builders"));
        assertTrue(shown.getTeam("Vanishpp_Vanished").hasEntry(subject.getName()));
        }
    }

    private void reconcile() {
        for (int pass = 0; pass < 80; pass++) plugin.reapplyTeamEntry(subject);
    }

    @Test
    void populatedReadOnlyHealthObjectiveUsesWritableDisplayMirror() {
        plugin.applyVanishEffects(subject);
        ScoreboardManager nativeManager = server.getScoreboardManager();
        Scoreboard raw = nativeManager.getNewScoreboard();
        Objective health = raw.registerNewObjective("health", "dummy", Component.text("Health"));
        readOnlySources.add(health);
        health.setRenderType(RenderType.HEARTS);
        health.setDisplaySlot(DisplaySlot.BELOW_NAME);
        health.getScore("public").setScore(20); // Simulates server-owned health updates.
        Objective points = raw.registerNewObjective("points", "dummy", Component.text("Points"));
        points.getScore("public").setScore(7);
        Scoreboard original = spy(formattingScoreboard(raw));
        doReturn(Set.of("public")).when(original).getEntries();
        viewer.setScoreboard(original);
        ScoreboardManager manager = mock(ScoreboardManager.class);
        when(manager.getNewScoreboard()).thenAnswer(call -> formattingScoreboard(nativeManager.getNewScoreboard()));
        ViewerTeams teams = new ViewerTeams(plugin);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
            assertDoesNotThrow(() -> { for (int pass = 0; pass < 80; pass++) teams.refresh(); },
                    "read-only health objectives must be copied into writable display mirrors");
            Objective shown = viewer.getScoreboard().getObjective("health");
            assertEquals(20, shown.getScore("public").getScore());
            assertTrue(shown.isModifiable());
            assertEquals(RenderType.HEARTS, shown.getRenderType());
            assertEquals(DisplaySlot.BELOW_NAME, shown.getDisplaySlot());
            health.getScore("public").setScore(13);
            points.getScore("public").setScore(9);
            for (int pass = 0; pass < 80; pass++) teams.refresh();
            assertEquals(13, shown.getScore("public").getScore());
            assertEquals(9, viewer.getScoreboard().getObjective("points").getScore("public").getScore());
            assertTrue(viewer.getScoreboard().getTeam("Vanishpp_Vanished").hasEntry(subject.getName()));
            assertFalse(original.getObjective("health").isModifiable());
        } finally { teams.close(); }
        assertSame(original, viewer.getScoreboard());
    }

    @Test
    void largeScoreboardDiscoveryAndCopyAdvanceWithinEachPassBudget() {
        plugin.applyVanishEffects(subject);
        ScoreboardManager nativeManager = server.getScoreboardManager();
        Scoreboard raw = nativeManager.getNewScoreboard();
        Objective objective = raw.registerNewObjective("points", "dummy", Component.text("Points"));
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < 10000; i++) {
            String name = "entry" + i;
            names.add(name);
            objective.getScore(name).setScore(i + 1);
        }
        AtomicInteger visited = new AtomicInteger(), snapshots = new AtomicInteger();
        Scoreboard source = spy(formattingScoreboard(raw));
        doReturn(counted(names, visited)).when(source).getEntries();
        Scoreboard original = countedSnapshots(source, snapshots);
        viewer.setScoreboard(original);
        ScoreboardManager manager = mock(ScoreboardManager.class);
        when(manager.getNewScoreboard()).thenAnswer(call -> countedSnapshots(
                formattingScoreboard(nativeManager.getNewScoreboard()), snapshots));
        ViewerTeams teams = new ViewerTeams(plugin);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
            for (int pass = 0; pass < 250; pass++) {
                visited.set(0);
                snapshots.set(0);
                teams.refresh();
                assertTrue(visited.get() <= 256,
                        "scoreboard preparation must not traverse an entire 10000-entry snapshot in one pass: " + visited.get());
                assertTrue(snapshots.get() <= 1, "a pass must acquire at most one scoreboard snapshot: " + snapshots.get());
                assertTrue(viewer.getScoreboard().getTeam("Vanishpp_Vanished").hasEntry(subject.getName()));
            }
            Objective shown = viewer.getScoreboard().getObjective("points");
            for (int i = 0; i < 10000; i++) assertEquals(i + 1, shown.getScore("entry" + i).getScore());
            objective.getScore("entry9999").setScore(42);
            for (int pass = 0; pass < 250; pass++) teams.refresh();
            assertEquals(42, shown.getScore("entry9999").getScore(), "later cycles must keep mirroring changes");
        } finally { teams.close(); }
    }

    private <T> Set<T> counted(Set<T> values, AtomicInteger visited) {
        return new AbstractSet<>() {
            public int size() { return values.size(); }
            public Iterator<T> iterator() {
                Iterator<T> source = values.iterator();
                return new Iterator<>() {
                    public boolean hasNext() { return source.hasNext(); }
                    public T next() { visited.incrementAndGet(); return source.next(); }
                };
            }
        };
    }

    private Scoreboard countedSnapshots(Scoreboard delegate, AtomicInteger snapshots) {
        return (Scoreboard) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Scoreboard.class},
                (proxy, method, args) -> {
                    if (Set.of("getEntries", "getObjectives", "getTeams").contains(method.getName())) snapshots.incrementAndGet();
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }

    // Delegate implemented scoreboard behavior; supply the formatting methods missing in MockBukkit 3.
    private Scoreboard formattingScoreboard(Scoreboard delegate) {
        Map<Objective, Objective> objectives = new IdentityHashMap<>();
        return (Scoreboard) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Scoreboard.class},
                (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(delegate, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                    if (result instanceof Objective objective)
                        return objectives.computeIfAbsent(objective, this::formattingObjective);
                    if (method.getName().equals("getObjectives")) {
                        Set<Objective> wrapped = new HashSet<>();
                        for (Objective objective : delegate.getObjectives()) wrapped.add(objectives.computeIfAbsent(objective, this::formattingObjective));
                        return wrapped;
                    }
                    return result;
                });
    }

    private Objective formattingObjective(Objective delegate) {
        Map<Score, Score> scores = new IdentityHashMap<>();
        Object[] format = {null};
        return (Objective) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Objective.class},
                (proxy, method, args) -> {
                    if (readOnlySources.contains(delegate)) {
                        if (method.getName().equals("getCriteria")) return "health";
                        if (method.getName().equals("isModifiable")) return false;
                    }
                    if (method.getName().equals("numberFormat")) {
                        if (args == null || args.length == 0) return format[0];
                        format[0] = args[0];
                        return null;
                    }
                    Object result;
                    try { result = method.invoke(delegate, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                    return result instanceof Score score ? scores.computeIfAbsent(score, value -> formattingScore(value, delegate)) : result;
                });
    }

    private Score formattingScore(Score delegate, Objective objective) {
        Map<String, Object> properties = new HashMap<>();
        return (Score) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Score.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setScore") && objective.getCriteria().equals("health"))
                        throw new IllegalStateException("Cannot modify read-only score");
                    if (method.getName().equals("numberFormat") || method.getName().equals("customName")) {
                        if (args == null || args.length == 0) return properties.get(method.getName());
                        properties.put(method.getName(), args[0]);
                        return null;
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }
}
