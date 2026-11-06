package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.thecommandcraft.vanishpp.commands.*;
import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.listeners.PlayerListener;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;

import java.net.InetAddress;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisibilityTest {
    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock subject;
    private PlayerMock viewer;
    private PlayerListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        subject = server.addPlayer("HiddenStaff");
        viewer = server.addPlayer("Observer");
        listener = new PlayerListener(plugin);
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    private void hidden() { plugin.applyVanishEffects(subject); }

    private void lowRank() {
        hidden();
        plugin.getConfigManager().layeredPermsEnabled = true;
        subject.addAttachment(plugin, "vanishpp.vanish.level.10", true);
        viewer.addAttachment(plugin, "vanishpp.see", true);
        viewer.addAttachment(plugin, "vanishpp.see.level.1", true);
        assertFalse(plugin.getPermissionManager().canSee(viewer, subject));
    }

    @Test
    void audiencePreservesSelfWhileDenyingLowerRanks() {
        lowRank();
        assertTrue(plugin.getPermissionManager().canSee(subject, subject),
                "a vanished player must retain their own identity packets");
    }

    @Test
    void sharedScoreboardNeverContainsHiddenNames() {
        viewer.setOp(true);
        plugin.applyVanishEffects(subject);
        plugin.reapplyTeamEntry(subject);
        Team shared = server.getScoreboardManager().getMainScoreboard().getTeam("Vanishpp_Vanished");
        assertFalse(shared != null && shared.hasEntry(subject.getName()),
                "shared scoreboard must not broadcast a vanished name without packet filtering");
        Team staff = viewer.getScoreboard().getTeam("Vanishpp_Vanished");
        assertTrue(staff != null && staff.hasEntry(subject.getName()), "authorized staff need a private team");
    }

    @Test
    void suggestionsRequireCommandPermissionAndTargetVisibility() {
        var command = new VanishRulesCommand(plugin);
        assertTrue(command.onTabComplete(viewer, plugin.getCommand("vanishrules"), "vrules", new String[]{""}).isEmpty(),
                "unauthorized rules completion must be empty");
        lowRank();
        viewer.addAttachment(plugin, "vanishpp.rules", true);
        viewer.addAttachment(plugin, "vanishpp.rules.others", true);
        List<String> names = command.onTabComplete(viewer, plugin.getCommand("vanishrules"), "vrules", new String[]{""});
        assertFalse(names.contains(subject.getName()), "lower rank completion must hide higher rank names");
        assertTrue(names.contains(viewer.getName()), "visible player completion must remain available");
    }

    @Test
    void vanishedChatRespectsLevels() {
        lowRank();
        plugin.getRuleManager().setRule(subject, RuleManager.CAN_CHAT, true);
        Set<Audience> viewers = new HashSet<>(List.of(viewer, subject));
        AsyncChatEvent event = new AsyncChatEvent(false, subject, viewers, ChatRenderer.defaultRenderer(),
                Component.text("probe"), Component.text("probe"), null);
        listener.onChat(event);
        assertFalse(event.viewers().contains(viewer), "lower rank must not receive higher rank chat");
        assertTrue(event.viewers().contains(subject), "sender must retain their own chat");
    }

    @Test
    void replyRemainsProtectedAcrossRepeatedAttemptsAndPreVanishConversations() {
        listener.onPrivateMessageCommand(new PlayerCommandPreprocessEvent(subject, "/msg Observer hello"));
        hidden();
        PlayerCommandPreprocessEvent first = new PlayerCommandPreprocessEvent(viewer, "/r first");
        listener.onPrivateMessageCommand(first);
        assertTrue(first.isCancelled(), "reply to a now-hidden conversation partner must be blocked");
        PlayerCommandPreprocessEvent second = new PlayerCommandPreprocessEvent(viewer, "/r second");
        listener.onPrivateMessageCommand(second);
        assertTrue(second.isCancelled(), "repeated reply must not clear hidden-recipient protection");
        plugin.removeVanishEffects(subject);
        PlayerCommandPreprocessEvent visible = new PlayerCommandPreprocessEvent(viewer, "/r visible");
        listener.onPrivateMessageCommand(visible);
        assertFalse(visible.isCancelled(), "visible conversation must remain usable");
    }

    @Test
    void placeholdersOnlyNameSubjectsVisibleToTheirViewer() throws Exception {
        lowRank();
        Class<?> type = Class.forName("net.thecommandcraft.vanishpp.hooks.IntegrationManager$VanishExpansion");
        var constructor = type.getDeclaredConstructor(Vanishpp.class);
        constructor.setAccessible(true);
        PlaceholderExpansion expansion = (PlaceholderExpansion) constructor.newInstance(plugin);
        assertEquals("", expansion.onPlaceholderRequest(viewer, "vanished_list"),
                "viewer placeholder must not disclose higher rank names");
        assertEquals("0", expansion.onPlaceholderRequest(viewer, "vanished_count"));
        assertEquals("", expansion.onPlaceholderRequest(null, "vanished_list"));
        viewer.setOp(true);
        assertEquals(subject.getName(), expansion.onPlaceholderRequest(viewer, "vanished_list"));
    }

    @Test
    void incognitoTabIdentitySurvivesVanishRefreshAndRestores() {
        subject.setOp(true);
        var command = new IncognitoCommand(plugin);
        command.onCommand(subject, plugin.getCommand("vincognito"), "vincognito", new String[]{"Alias"});
        assertEquals("Alias", plain(subject.playerListName()), "incognito must replace the tab display identity");
        plugin.applyVanishEffects(subject);
        plugin.resyncVanishEffects(subject);
        assertFalse(plain(subject.playerListName()).contains(subject.getName()), "refresh must not restore the real tab name");
        command.onCommand(subject, plugin.getCommand("vincognito"), "vincognito", new String[]{"off"});
        assertTrue(plain(subject.playerListName()).contains(subject.getName()), "disabling incognito restores tab identity");
    }

    @Test
    void autoVanishIsEffectiveBeforeJoinReturns() throws Exception {
        subject.addAttachment(plugin, "vanishpp.vanish", true);
        plugin.getStorageProvider().setAutoVanishOnJoin(subject.getUniqueId(), true);
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(subject.getName(), InetAddress.getLoopbackAddress(), subject.getUniqueId()));
        PlayerJoinEvent join = new PlayerJoinEvent(subject, Component.text("real join"));
        listener.onJoin(join);
        assertTrue(plugin.isVanished(subject), "prefetched auto-vanish must apply during initial join handling");
        assertNull(join.joinMessage(), "auto-vanish must suppress the real join announcement");
        assertFalse(viewer.canSee(subject), "auto-vanish must hide the entity before join completes");
    }

    @Test
    void projectileCollisionKeepsOriginalEntityAndState() {
        hidden();
        plugin.getConfigManager().ignoreProjectiles = true;
        Projectile projectile = mock(Projectile.class);
        when(projectile.getVelocity()).thenReturn(new Vector(0, 0, 0));
        ProjectileHitEvent hit = new ProjectileHitEvent(projectile, subject);
        listener.onProjectileHit(hit);
        assertTrue(hit.isCancelled(), "hidden collision must be cancelled");
        assertEquals(0, mockingDetails(projectile).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("remove")).count(),
                "pass-through must retain the original projectile, including slow projectiles");
    }

    @Test
    void disabledPickupDoesNotConsumeExperienceOrbs() {
        hidden();
        plugin.getRuleManager().setRule(subject, RuleManager.CAN_PICKUP_ITEMS, false);
        PlayerPickupExperienceEvent event = new PlayerPickupExperienceEvent(subject, mock(ExperienceOrb.class));
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "disabled pickup must leave experience orbs unconsumed");
        plugin.getRuleManager().setRule(subject, RuleManager.CAN_PICKUP_ITEMS, true);
        PlayerPickupExperienceEvent allowed = new PlayerPickupExperienceEvent(subject, mock(ExperienceOrb.class));
        server.getPluginManager().callEvent(allowed);
        assertFalse(allowed.isCancelled(), "enabled pickup must still accept experience");
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
