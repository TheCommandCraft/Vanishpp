package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.listeners.PlayerListener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ViewerTeamLifecycleTest {
    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock subject, viewer;

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
        Scoreboard original = viewer.getScoreboard();
        Objective score = original.registerNewObjective("points", "dummy", Component.text("Points"));
        score.getScore("public").setScore(5);
        Team team = original.registerNewTeam("builders");
        for (Team.Option option : Team.Option.values()) team.setOption(option, Team.OptionStatus.ALWAYS);
        team.prefix(Component.text("Before"));
        team.addEntry("public");
        plugin.applyVanishEffects(subject);
        assertEquals(5, viewer.getScoreboard().getObjective("points").getScore("public").getScore());
        score.getScore("public").setScore(9);
        score.displayName(Component.text("Updated"));
        team.prefix(Component.text("After"));
        team.addEntry("Visible");
        plugin.reapplyTeamEntry(subject);
        Scoreboard shown = viewer.getScoreboard();
        assertEquals(9, shown.getObjective("points").getScore("public").getScore(),
                "private overlay must continue showing original scoreboard updates");
        assertEquals(Component.text("Updated"), shown.getObjective("points").displayName());
        assertEquals(Component.text("After"), shown.getTeam("builders").prefix());
        assertTrue(shown.getTeam("builders").hasEntry("Visible"));
        score.unregister();
        team.unregister();
        plugin.reapplyTeamEntry(subject);
        assertNull(shown.getObjective("points"));
        assertNull(shown.getTeam("builders"));
        assertTrue(shown.getTeam("Vanishpp_Vanished").hasEntry(subject.getName()));
    }
}
