package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.commands.VanishFollowCommand;
import net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot;
import net.thecommandcraft.vanishpp.listeners.PlayerListener;
import net.thecommandcraft.vanishpp.proxy.ProxyBridge;
import net.thecommandcraft.vanishpp.storage.*;
import net.thecommandcraft.vanishpp.utils.VanishScheduler;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StateDurabilityTest {
    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock player;
    private ControlledScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        plugin.getConfigManager().enableFly = false;
        player = server.addPlayer();
        server.getScheduler().cancelTasks(plugin);
        scheduler = new ControlledScheduler();
        field(plugin, "vanishScheduler", scheduler);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void failedSqlReadsAreNotSuccessfulVisibleResults() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new java.sql.SQLException("read unavailable"));
        SqlStorage sql = new SqlStorage(plugin, source);
        assertAll(
                () -> assertThrows(RuntimeException.class, () -> sql.isVanished(player.getUniqueId()),
                        "failed player reads must be distinguishable from visible"),
                () -> assertThrows(RuntimeException.class, sql::getVanishedPlayers,
                        "failed snapshots must be distinguishable from an empty vanished set"));
    }

    @Test
    void proxyLoginDoesNotReplaceNetworkStateWithMissingLocalRecord() throws Exception {
        ProxyBridge proxy = mock(ProxyBridge.class);
        when(proxy.isProxyDetected()).thenReturn(true);
        field(plugin, "proxyBridge", proxy);
        plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        assertFalse(plugin.getStorageProvider().isVanished(player.getUniqueId()), "local record is absent before queued writes");
        login();
        assertTrue(plugin.isVanished(player), "proxy vanish must survive login on a backend with no local record");
    }

    @Test
    void freshEmptyProxySnapshotDoesNotErasePersistedVanish() {
        plugin.getStorageProvider().setVanished(player.getUniqueId(), true);
        plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        plugin.applyNetworkVanishState(List.of());
        scheduler.drainAsync();
        assertAll(
                () -> assertTrue(plugin.isVanished(player), "fresh empty proxy snapshot must preserve known hidden state"),
                () -> assertTrue(plugin.getStorageProvider().isVanished(player.getUniqueId()), "bootstrap must not persist an unvanish"));
    }

    @Test
    void reorderedWorkersCannotPersistAnOlderToggle() {
        plugin.applyVanishEffects(player);
        plugin.removeVanishEffects(player);
        while (!scheduler.async.isEmpty()) scheduler.async.removeLast().run();
        assertFalse(plugin.getStorageProvider().isVanished(player.getUniqueId()),
                "latest unvanish must persist even when workers are started in reverse order");
    }

    @Test
    void cancellingDelayedUnvanishNeverRemovesEquippedArmour() {
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.nightvision");
        ItemStack[] armour = {new ItemStack(Material.DIAMOND_BOOTS), new ItemStack(Material.DIAMOND_LEGGINGS),
                new ItemStack(Material.DIAMOND_CHESTPLATE), new ItemStack(Material.DIAMOND_HELMET)};
        player.getInventory().setArmorContents(armour);
        plugin.applyVanishEffects(player);
        scheduler.later.clear();
        plugin.removeVanishEffects(player);
        scheduler.runDelayedPass();
        scheduler.cancelAllTasks();
        assertArrayEquals(armour, player.getInventory().getArmorContents(),
                "cancelling between delayed callbacks must never delete equipped armour");
    }

    @Test
    void spectatorRestorationSurvivesSessionCacheLossAndStorageReopen() throws Exception {
        player.setGameMode(GameMode.CREATIVE);
        plugin.saveGamemodeForRestore(player);
        player.setGameMode(GameMode.SPECTATOR);
        scheduler.drainAsync();
        plugin.cleanupPlayerCache(player.getUniqueId());
        plugin.pendingGamemodeRestore.clear();
        YamlStorage reopened = new YamlStorage(plugin);
        reopened.init();
        field(plugin, "storageProvider", reopened);
        plugin.restoreSavedGamemode(player);
        assertEquals(GameMode.CREATIVE, player.getGameMode(), "original gamemode must survive disconnect and restart");
    }

    @Test
    void temporaryRuleCannotOverwriteANewerPermanentDecision() {
        plugin.getRuleManager().setRule(player, "can_chat", true);
        plugin.scheduleRuleRevert(player, "can_chat", false, 1);
        plugin.getRuleManager().setRule(player, "can_chat", true);
        scheduler.runDelayedPass();
        assertTrue(plugin.getRuleManager().getRule(player, "can_chat"),
                "old expiry must not overwrite a newer decision, even when the value is unchanged");
    }

    @Test
    void expiredTemporaryRuleSurvivesReload() {
        plugin.getRuleManager().setRule(player, "can_chat", true);
        plugin.scheduleRuleRevert(player, "can_chat", false, 0);
        scheduler.later.clear();
        plugin.reloadPluginConfig();
        scheduler.runDelayedPass();
        scheduler.fireTimers();
        scheduler.drainAsync();
        assertFalse(plugin.getRuleManager().getRule(player, "can_chat"), "expired temporary rule must revert after reload");
    }

    @Test
    void reloadKeepsFollowingAMovingTarget() {
        FollowingPlayer follower = new FollowingPlayer(server);
        server.addPlayer(follower);
        new VanishFollowCommand(plugin);
        plugin.spectateFollowTargets.put(follower.getUniqueId(), player.getUniqueId());
        scheduler.fireTimers();
        int before = follower.teleports;
        assertTrue(before > 0, "follow timer control must move the follower before reload");
        plugin.reloadPluginConfig();
        player.teleport(player.getLocation().add(12, 0, 0));
        scheduler.fireTimers();
        assertTrue(follower.teleports > before, "follow timer must still move the follower after reload");
        assertEquals(player.getLocation(), follower.lastDestination);
    }

    @Test
    void serverOverridesHaveStablePrecedenceAndAreResolvedOnce() {
        ProxyConfigSnapshot config = new ProxyConfigSnapshot();
        config.serverOverrides.put("survival", Map.of("invisibility-features.god-mode", false));
        config.serverOverrides.put("all", Map.of("invisibility-features.god-mode", true));
        ProxyConfigSnapshot resolved = config.applyOverride("survival");
        assertFalse(resolved.godMode, "specific server must win even when all is later in YAML");
        plugin.getProxyConfigCache().update(resolved.toJson(), "127.0.0.1:25565");
        assertFalse(plugin.getProxyConfigCache().get().godMode, "Paper must not reapply all over a resolved server snapshot");
    }

    @Test
    void migrationCopiesPreferencesPresetsHistoryAndStatistics() throws Exception {
        UUID id = player.getUniqueId();
        StorageProvider source = plugin.getStorageProvider();
        source.setAutoVanishOnJoin(id, true);
        source.saveRulePreset(id, "quiet", Map.of("can_chat", false));
        source.addHistoryEntry(VanishHistoryEntry.vanish(id, player.getName(), "lobby", "test"));
        source.setStats(id, new VanishStats(42000, 3, 21000));
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage target = new SqlStorage(plugin, dataSource);
        target.init();
        try {
            var method = Vanishpp.class.getDeclaredMethod("migrateIfNeeded", StorageProvider.class, String.class);
            method.setAccessible(true);
            method.invoke(plugin, target, "MYSQL");
            assertAll(
                    () -> assertTrue(target.getAutoVanishOnJoin(id), "migration must copy preferences"),
                    () -> assertEquals(Map.of("can_chat", false), target.loadRulePreset(id, "quiet"), "migration must copy presets"),
                    () -> assertEquals(1, target.getAllHistory(1, 20).size(), "migration must copy history"),
                    () -> assertEquals(42000, target.getStats(id).getTotalVanishTimeMs(), "migration must copy statistics"));
        } finally {
            target.shutdown();
        }
    }

    @Test
    void shutdownRecordsFinalSessionBeforeClosingStorage() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        AtomicBoolean closed = new AtomicBoolean();
        List<Long> recorded = new ArrayList<>();
        doAnswer(call -> { closed.set(true); return null; }).when(storage).shutdown();
        doAnswer(call -> {
            if (closed.get()) throw new IllegalStateException("storage already closed");
            recorded.add(call.getArgument(1));
            return null;
        }).when(storage).recordVanishSession(eq(player.getUniqueId()), anyLong());
        field(plugin, "storageProvider", storage);
        plugin.vanishStartTimes.put(player.getUniqueId(), System.currentTimeMillis() - 5000);
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(1, recorded.size(), "final session must be written before storage closes");
        assertTrue(recorded.getFirst() >= 5000);
    }

    @Test
    void warningAcknowledgementLoadsBeforeJoinWarnings() throws Exception {
        plugin.setWarningIgnored(player, true);
        plugin.cleanupPlayerCache(player.getUniqueId());
        YamlStorage reopened = new YamlStorage(plugin);
        reopened.init();
        field(plugin, "storageProvider", reopened);
        login();
        assertTrue(plugin.isWarningIgnored(player), "persisted warning acknowledgement must load on login");
    }

    private void login() {
        PlayerListener listener = new PlayerListener(plugin);
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId()));
        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));
    }

    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static class FollowingPlayer extends PlayerMock {
        int teleports;
        Location lastDestination;
        FollowingPlayer(ServerMock server) { super(server, "Follower"); }
        @Override public CompletableFuture<Boolean> teleportAsync(Location location) {
            teleports++;
            lastDestination = location.clone();
            return CompletableFuture.completedFuture(true);
        }
    }

    private static class ControlledScheduler implements VanishScheduler {
        final List<Runnable> async = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final List<Runnable> timers = new ArrayList<>();
        public void runGlobal(Runnable task) { task.run(); }
        public void runLaterGlobal(Runnable task, long ticks) { later.add(task); }
        public void runTimerGlobal(Runnable task, long delay, long period) { timers.add(task); }
        public void runEntity(Entity entity, Runnable task, Runnable retired) { task.run(); }
        public void runEntityNow(Entity entity, Runnable task) { task.run(); }
        public void runAsync(Runnable task) { async.add(task); }
        public void cancelAllTasks() { later.clear(); timers.clear(); async.clear(); }
        void drainAsync() { while (!async.isEmpty()) async.removeFirst().run(); }
        void runDelayedPass() { List<Runnable> due = List.copyOf(later); later.clear(); due.forEach(Runnable::run); }
        void fireTimers() { List.copyOf(timers).forEach(Runnable::run); }
    }
}
