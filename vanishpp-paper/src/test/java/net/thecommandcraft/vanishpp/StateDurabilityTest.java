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
    private RecordingPlayer player;
    private ControlledScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        plugin.getConfigManager().enableFly = false;
        player = new RecordingPlayer(server);
        server.addPlayer(player);
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
        SqlStorage sql = sqlStorage(source);
        assertAll(
                () -> assertThrows(RuntimeException.class, () -> sql.isVanished(player.getUniqueId()),
                        "failed player reads must be distinguishable from visible"),
                () -> assertThrows(RuntimeException.class, sql::getVanishedPlayers,
                        "failed snapshots must be distinguishable from an empty vanished set"));
    }

    @Test
    void unavailableSqlInitializationDoesNotBecomeAuthoritativeYaml() throws Exception {
        plugin.getConfigManager().getConfig().set("storage.type", "MYSQL");
        field(plugin, "storageProvider", null);
        try (var construction = mockConstruction(SqlStorage.class, (storage, context) -> {
            doThrow(new java.sql.SQLException("database unavailable")).when(storage).init();
            when(storage.isVanished(player.getUniqueId())).thenThrow(new IllegalStateException("unavailable")).thenReturn(true);
        })) {
            var initialize = Vanishpp.class.getDeclaredMethod("initStorage");
            initialize.setAccessible(true);
            initialize.invoke(plugin);
            assertThrows(RuntimeException.class, () -> plugin.getStorageProvider().isVanished(player.getUniqueId()),
                    "failed SQL initialization must not turn missing local YAML into an authoritative unvanish");
            assertTrue(plugin.getStorageProvider().isVanished(player.getUniqueId()), "the configured provider must remain available for recovery");
        }
    }

    @Test
    void failedLoginReadKeepsKnownStateAndRetries() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenThrow(new IllegalStateException("still unavailable"))
                .thenReturn(true);
        field(plugin, "storageProvider", storage);
        plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        login();
        assertTrue(plugin.isVanished(player), "failed prelogin must retain known hidden state");
        assertDoesNotThrow(scheduler::drainAsync, "failed fallback read must schedule a retry instead of abandoning reconciliation");
        scheduler.runDelayedPass();
        scheduler.drainAsync();
        assertTrue(plugin.isVanished(player));
        verify(storage, atLeast(3)).isVanished(player.getUniqueId());
    }

    @Test
    void failedLoginReadAdmitsPlayersHiddenUntilTheReadSucceeds() throws Exception {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        field(plugin, "storageProvider", storage);
        PlayerListener listener = new PlayerListener(plugin);
        var prelogin = new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId());
        listener.onAsyncPreLogin(prelogin);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, prelogin.getLoginResult(),
                "a failed vanish state read must not lock out a player");
        PlayerJoinEvent join = new PlayerJoinEvent(player, Component.text("joined"));
        listener.onJoin(join);
        assertFalse(observer.canSee(player), "a player whose stored state is unknown must join hidden");
        assertNull(join.joinMessage(), "the join announcement must wait for the stored state");
        scheduler.drainAsync();
        assertTrue(observer.canSee(player), "a successful read must reveal a player stored as visible");
        assertFalse(plugin.isVanished(player));
        scheduler.drainAsync();
        verify(storage, never()).setVanished(eq(player.getUniqueId()), anyBoolean());
    }

    @Test
    void recoveredAutoVanishHidesAHeldPlayerWithoutShowingThem() throws Exception {
        WatchingPlayer observer = new WatchingPlayer(server);
        server.addPlayer(observer);
        observer.setOp(false);
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        when(storage.getAutoVanishOnJoin(player.getUniqueId())).thenReturn(true);
        field(plugin, "storageProvider", storage);
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.vanish");
        login();
        observer.shown.clear();
        scheduler.drainAsync();
        assertTrue(plugin.isVanished(player), "auto-vanish must apply once the stored preference can be read");
        assertFalse(observer.shown.contains(player.getUniqueId()), "a held auto-vanishing player must never be shown");
        assertFalse(observer.canSee(player));
    }

    @Test
    void manualUnvanishDuringTheHoldIsNotUndoneByRecoveredAutoVanish() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        when(storage.getAutoVanishOnJoin(player.getUniqueId())).thenReturn(true);
        field(plugin, "storageProvider", storage);
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.vanish");
        login();
        plugin.removeVanishEffects(player);
        scheduler.drainAsync();
        assertFalse(plugin.isVanished(player), "a choice made during the hold must outrank the recovered auto-vanish preference");
    }

    @Test
    void unvanishDuringTheHoldOfAPreviouslyHiddenPlayerIsNotUndoneByRecoveredAutoVanish() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        when(storage.getAutoVanishOnJoin(player.getUniqueId())).thenReturn(true);
        field(plugin, "storageProvider", storage);
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.vanish");
        plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        login();
        plugin.removeVanishEffects(player);
        scheduler.drainAsync();
        assertFalse(plugin.isVanished(player), "a choice made during the hold must outrank the recovered auto-vanish preference");
    }

    @Test
    void recoveredAutoVanishKeepsAPreviouslyHiddenHeldPlayerHidden() throws Exception {
        WatchingPlayer observer = new WatchingPlayer(server);
        server.addPlayer(observer);
        observer.setOp(false);
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        when(storage.getAutoVanishOnJoin(player.getUniqueId())).thenReturn(true);
        field(plugin, "storageProvider", storage);
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.vanish");
        plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        login();
        observer.shown.clear();
        scheduler.drainAsync();
        assertTrue(plugin.isVanished(player), "recovered auto-vanish must keep a player hidden before the hold hidden");
        assertFalse(observer.shown.contains(player.getUniqueId()), "a held auto-vanishing player must never be shown");
        assertFalse(observer.canSee(player));
    }

    @Test
    void aCancelledVanishEventStopsRecoveredAutoVanish() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        when(storage.getAutoVanishOnJoin(player.getUniqueId())).thenReturn(true);
        field(plugin, "storageProvider", storage);
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.vanish");
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void deny(net.thecommandcraft.vanishpp.api.events.VanishEvent event) { event.setCancelled(true); }
        }, plugin);
        login();
        scheduler.drainAsync();
        assertFalse(plugin.isVanished(player), "another plugin cancelling the vanish must keep the recovered visible state");
    }

    @Test
    void repeatedTogglesDuringTheHoldBroadcastNoFakeJoinOrQuit() throws Exception {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        plugin.getConfigManager().broadcastFakeJoin = true;
        plugin.getConfigManager().broadcastFakeQuit = true;
        plugin.getConfigManager().fakeJoinMessage = "fake-join %player%";
        plugin.getConfigManager().fakeQuitMessage = "fake-quit %player%";
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        field(plugin, "storageProvider", storage);
        login();
        while (observer.nextMessage() != null) {}
        plugin.unvanishPlayer(player, player);
        plugin.vanishPlayer(player, player);
        plugin.unvanishPlayer(player, player);
        assertFalse(plugin.isVanished(player));
        assertNull(observer.nextMessage(), "no join or quit may be announced while the real join is still withheld");
        scheduler.drainAsync();
        StringBuilder received = new StringBuilder();
        for (String next; (next = observer.nextMessage()) != null; ) received.append(next).append('\n');
        assertFalse(received.toString().contains("fake-"), "the released join must not be preceded by fake announcements: " + received);
        assertTrue(received.toString().contains("joined"), "the withheld join announcement must be sent once the join completes: " + received);
        plugin.vanishPlayer(player, player);
        assertEquals("fake-quit " + player.getName(), observer.nextMessage(), "vanishing after the join completes must announce a fake quit");
        plugin.unvanishPlayer(player, player);
        assertEquals("fake-join " + player.getName(), observer.nextMessage(), "unvanishing after the join completes must announce a fake join");
    }

    @Test
    void proxySnapshotsDoNotLiftTheHoldWhileTheLoginReadFails() throws Exception {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.getAutoVanishOnJoin(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        field(plugin, "storageProvider", storage);
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin);
        for (String name : List.of("active", "helloSent", "handshakeComplete", "proxyDetected")) {
            var field = ProxyBridge.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bridge, true);
        }
        field(plugin, "proxyBridge", bridge);
        login();
        var query = player.packets.stream().filter(packet -> packet.type()
                == net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_QUERY).findFirst().orElseThrow();
        var reply = new com.google.gson.JsonObject();
        reply.add("vanished", new com.google.gson.JsonArray());
        reply.addProperty("requestId", com.google.gson.JsonParser.parseString(query.jsonPayload())
                .getAsJsonObject().get("requestId").getAsString());
        reply.addProperty("joiningUuid", player.getUniqueId().toString());
        reply.addProperty("joiningVanished", false);
        reply.addProperty("session", "proxy-session");
        reply.addProperty("revision", 0);
        stateResponse(bridge, reply);
        var snapshot = new com.google.gson.JsonObject();
        snapshot.add("vanished", new com.google.gson.JsonArray());
        snapshot.addProperty("authoritative", true);
        snapshot.addProperty("session", "proxy-session");
        snapshot.addProperty("revision", 1);
        stateResponse(bridge, snapshot);
        assertFalse(observer.canSee(player), "a proxy snapshot must not lift the hold while the stored state is unknown");
        scheduler.drainAsync();
        assertTrue(observer.canSee(player), "the hold lifts once the stored state is read");
    }

    @Test
    void networkDecisionsAfterAChoiceDuringTheHoldStillApply() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.getAutoVanishOnJoin(player.getUniqueId()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(false);
        field(plugin, "storageProvider", storage);
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin);
        for (String name : List.of("active", "helloSent", "handshakeComplete", "proxyDetected")) {
            var field = ProxyBridge.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bridge, true);
        }
        field(plugin, "proxyBridge", bridge);
        login();
        var query = player.packets.stream().filter(packet -> packet.type()
                == net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_QUERY).findFirst().orElseThrow();
        var reply = new com.google.gson.JsonObject();
        reply.add("vanished", new com.google.gson.JsonArray());
        reply.addProperty("requestId", com.google.gson.JsonParser.parseString(query.jsonPayload())
                .getAsJsonObject().get("requestId").getAsString());
        reply.addProperty("joiningUuid", player.getUniqueId().toString());
        reply.addProperty("joiningVanished", false);
        stateResponse(bridge, reply);
        plugin.applyVanishEffects(player);
        plugin.handleNetworkVanishSync(player.getUniqueId(), false);
        assertFalse(plugin.isVanished(player), "a network decision after a choice made during the hold must apply");
        scheduler.drainAsync();
        assertFalse(plugin.isVanished(player), "the late storage answer must not restore the superseded hold");
    }

    @Test
    void aJoinWhoseLoginReadExpiredStaysHiddenUntilARead() throws Exception {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        PlayerListener listener = new PlayerListener(plugin);
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId()));
        Map<UUID, Object> attempts = listenerField(listener, "loginAttempts");
        var attempt = Class.forName(PlayerListener.class.getName() + "$LoginAttempt").getDeclaredConstructor(long.class);
        attempt.setAccessible(true);
        attempts.put(player.getUniqueId(), attempt.newInstance(System.nanoTime() - java.util.concurrent.TimeUnit.MINUTES.toNanos(20)));
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent("Other", InetAddress.getLoopbackAddress(), UUID.randomUUID()));
        PlayerJoinEvent join = new PlayerJoinEvent(player, Component.text("joined"));
        listener.onJoin(join);
        assertFalse(observer.canSee(player), "a join whose login read expired must stay hidden until its state is read");
        assertNull(join.joinMessage(), "the join announcement must wait for the stored state");
        scheduler.drainAsync();
        assertTrue(observer.canSee(player), "the read lifts the hold");
    }

    @Test
    void aJoinWithoutALoginReadStaysHiddenUntilARead() throws Exception {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        PlayerListener listener = new PlayerListener(plugin);
        PlayerJoinEvent join = new PlayerJoinEvent(player, Component.text("joined"));
        listener.onJoin(join);
        assertFalse(observer.canSee(player), "a join with no login read must stay hidden until its state is read");
        assertNull(join.joinMessage(), "the join announcement must wait for the stored state");
        scheduler.drainAsync();
        assertTrue(observer.canSee(player), "the read lifts the hold");
    }

    @Test
    void anOlderLoginReadCannotOverwriteAReplacementLogin() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        PlayerListener listener = new PlayerListener(plugin);
        AtomicBoolean replaced = new AtomicBoolean();
        when(storage.isVanished(player.getUniqueId())).thenAnswer(call -> {
            if (replaced.compareAndSet(false, true)) {
                listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId()));
                return false;
            }
            throw new IllegalStateException("unavailable");
        });
        field(plugin, "storageProvider", storage);
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId()));
        Map<?, ?> reads = listenerField(listener, "preFetchedVanishState");
        assertFalse(reads.containsKey(player.getUniqueId()), "the replacement login's failed read must survive the older attempt finishing");
    }

    @Test
    void deniedAndAbandonedLoginsForgetTheirFailedRead() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.isVanished(any())).thenThrow(new IllegalStateException("unavailable"));
        field(plugin, "storageProvider", storage);
        PlayerListener listener = new PlayerListener(plugin);
        Map<UUID, Object> attempts = listenerField(listener, "loginAttempts");
        var denied = new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId());
        denied.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text("whitelist"));
        listener.onAsyncPreLogin(denied);
        assertFalse(attempts.containsKey(player.getUniqueId()), "a login another plugin denied must not record an attempt");
        UUID abandoned = UUID.randomUUID();
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent("Gone", InetAddress.getLoopbackAddress(), abandoned));
        assertTrue(attempts.containsKey(abandoned), "the attempt is kept for the join that follows");
        var attempt = Class.forName(PlayerListener.class.getName() + "$LoginAttempt").getDeclaredConstructor(long.class);
        attempt.setAccessible(true);
        attempts.put(abandoned, attempt.newInstance(System.nanoTime() - java.util.concurrent.TimeUnit.MINUTES.toNanos(20)));
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId()));
        assertFalse(attempts.containsKey(abandoned), "an attempt that never joined must be forgotten");
        assertTrue(attempts.containsKey(player.getUniqueId()), "expiry retires only the abandoned attempt");
    }

    private static void stateResponse(ProxyBridge bridge, com.google.gson.JsonObject json) {
        bridge.onPluginMessageReceived("vanishpp:proxy", null, net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_RESPONSE, json.toString()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T listenerField(PlayerListener listener, String name) throws Exception {
        Field field = PlayerListener.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(listener);
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
    void firstCarrierJoinStaysHiddenUntilItsProxyReply() throws Exception {
        PlayerMock observer = server.addPlayer();
        observer.setOp(false);
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin);
        for (String name : List.of("active", "helloSent", "handshakeComplete", "proxyDetected")) {
            var field = ProxyBridge.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bridge, true);
        }
        field(plugin, "proxyBridge", bridge);
        login();
        assertFalse(observer.canSee(player), "first carrier must stay hidden until its own proxy state reply");
        var query = player.packets.stream().filter(packet -> packet.type()
                == net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_QUERY).findFirst().orElseThrow();
        var request = com.google.gson.JsonParser.parseString(query.jsonPayload()).getAsJsonObject();
        var reply = new com.google.gson.JsonObject();
        reply.add("vanished", new com.google.gson.JsonArray());
        reply.addProperty("requestId", request.get("requestId").getAsString());
        reply.addProperty("joiningUuid", player.getUniqueId().toString());
        reply.addProperty("joiningVanished", false);
        reply.addProperty("session", "proxy-session");
        reply.addProperty("revision", 0);
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_RESPONSE, reply.toString()));
        assertFalse(plugin.isVanished(player), "an authoritative visible reply must release the provisional hide");
        assertTrue(observer.canSee(player), "confirmed visible player must become visible after the reply");
    }

    @Test
    void missingProxyJoinReplyRequiresReconnectInsteadOfReveal() throws Exception {
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin);
        for (String name : List.of("active", "helloSent", "handshakeComplete", "proxyDetected")) {
            var field = ProxyBridge.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bridge, true);
        }
        field(plugin, "proxyBridge", bridge);
        login();
        scheduler.runDelayedPass();
        assertFalse(player.isOnline(), "missing proxy state must require reconnect instead of revealing an unknown player");
        assertFalse(plugin.getStorageProvider().isVanished(player.getUniqueId()), "provisional hide must not become persisted vanish");
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
    void delayedProxyMessageCannotUndoANewerRevision() throws Exception {
        ProxyBridge bridge = new ProxyBridge(plugin);
        for (String name : List.of("active", "helloSent", "handshakeComplete", "proxyDetected")) {
            var field = ProxyBridge.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bridge, true);
        }
        String payload = "{\"uuid\":\"" + player.getUniqueId() + "\",\"session\":\"proxy-session\",";
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.VANISH_SYNC,
                        payload + "\"revision\":2,\"vanished\":true}"));
        assertTrue(plugin.isVanished(player), "newer network revision must apply");
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.VANISH_SYNC,
                        payload + "\"revision\":1,\"vanished\":false}"));
        assertTrue(plugin.isVanished(player), "delayed proxy revision must not unvanish a newer hidden state");
    }

    @Test
    void proxySnapshotCannotUndoAToggleWhoseWriteIsStillQueued() throws Exception {
        ProxyBridge bridge = new ProxyBridge(plugin);
        for (String name : List.of("active", "helloSent", "handshakeComplete", "proxyDetected")) {
            var field = ProxyBridge.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bridge, true);
        }
        field(plugin, "proxyBridge", bridge);
        plugin.applyVanishEffects(player);
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_RESPONSE,
                        "{\"session\":\"proxy-session\",\"revision\":0,\"authoritative\":true,\"vanished\":[],\"visible\":[]}"));
        assertTrue(plugin.isVanished(player), "older proxy snapshot must not undo a locally queued vanish");
        scheduler.drainAsync();
        var sent = player.packets.stream().filter(packet -> packet.type()
                == net.thecommandcraft.vanishpp.common.protocol.VppMessage.VANISH_EVENT).findFirst().orElseThrow();
        var payload = com.google.gson.JsonParser.parseString(sent.jsonPayload()).getAsJsonObject();
        payload.addProperty("session", "proxy-session");
        payload.addProperty("revision", 1);
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.VANISH_SYNC, payload.toString()));
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.VANISH_SYNC,
                        "{\"uuid\":\"" + player.getUniqueId() + "\",\"session\":\"proxy-session\",\"revision\":2,\"vanished\":false}"));
        assertFalse(plugin.isVanished(player), "acknowledgement must release the guard for subsequent proxy decisions");
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
        plugin.scheduleRuleRevert(player, "can_chat", false, 0);
        plugin.getRuleManager().setRule(player, "can_chat", true);
        scheduler.drainAsync();
        scheduler.runDelayedPass();
        scheduler.drainAsync();
        assertTrue(plugin.getRuleManager().getRule(player, "can_chat"),
                "old expiry must not overwrite a newer decision, even when the value is unchanged");
    }

    @Test
    void invalidTemporaryDurationDoesNotChangeTheRule() {
        player.setOp(true);
        plugin.getRuleManager().setRule(player, "can_chat", false);
        server.dispatchCommand(player, "vrules can_chat true not-a-number");
        assertFalse(plugin.getRuleManager().getRule(player, "can_chat"), "invalid temporary duration must leave the rule unchanged");
    }

    @Test
    void negativeTemporaryDurationDoesNotChangeTheRule() {
        player.setOp(true);
        plugin.getRuleManager().setRule(player, "can_chat", false);
        server.dispatchCommand(player, "vrules can_chat true -1");
        assertFalse(plugin.getRuleManager().getRule(player, "can_chat"), "negative temporary duration must leave the rule unchanged");
    }

    @Test
    void expiredTemporaryRuleSurvivesReload() {
        plugin.getRuleManager().setRule(player, "can_chat", true);
        plugin.scheduleRuleRevert(player, "can_chat", false, 0);
        scheduler.drainAsync();
        scheduler.later.clear();
        plugin.reloadPluginConfig();
        scheduler.drainAsync();
        scheduler.runDelayedPass();
        scheduler.fireTimers();
        scheduler.drainAsync();
        assertFalse(plugin.getRuleManager().getRule(player, "can_chat"), "expired temporary rule must revert after reload");
    }

    @Test
    void reloadKeepsFollowingAMovingTarget() {
        FollowingPlayer follower = new FollowingPlayer(server);
        server.addPlayer(follower);
        plugin.applyVanishEffects(follower);
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
    void paperDoesNotResolveAnAlreadyResolvedSnapshotAgain() {
        ProxyConfigSnapshot config = new ProxyConfigSnapshot();
        config.serverOverrides.put("all", Map.of("invisibility-features.god-mode", true));
        config.serverOverrides.put("survival", Map.of("invisibility-features.god-mode", false));
        ProxyConfigSnapshot resolved = config.applyOverride("survival");
        assertFalse(resolved.godMode, "Velocity resolution control");
        plugin.getProxyConfigCache().update(resolved.toJson(), "127.0.0.1:25565");
        assertFalse(plugin.getProxyConfigCache().get().godMode, "Paper must apply server overrides exactly once");
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
        SqlStorage target = sqlStorage(dataSource);
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
    void migrationBackToYamlReadsTheStillOpenPreviousSqlProvider(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:reverse_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage source = sqlStorage(dataSource);
        source.init();
        UUID id = player.getUniqueId();
        source.setAutoVanishOnJoin(id, true);
        source.saveRulePreset(id, "quiet", Map.of("can_chat", false));
        source.addHistoryEntry(VanishHistoryEntry.vanish(id, player.getName(), "lobby", "test"));
        source.setStats(id, new VanishStats(42000, 3, 21000));
        field(plugin, "storageProvider", source);
        Vanishpp destination = mock(Vanishpp.class);
        when(destination.getDataFolder()).thenReturn(directory.toFile());
        when(destination.getLogger()).thenReturn(plugin.getLogger());
        YamlStorage target = new YamlStorage(destination);
        target.init();
        var method = Vanishpp.class.getDeclaredMethod("migrateIfNeeded", StorageProvider.class, String.class);
        method.setAccessible(true);
        method.invoke(plugin, target, "YAML");
        assertAll(
                () -> assertTrue(target.getAutoVanishOnJoin(id), "SQL to YAML migration must copy preference-only players"),
                () -> assertEquals(Map.of("can_chat", false), target.loadRulePreset(id, "quiet")),
                () -> assertEquals(1, target.getAllHistory(1, 20).size()),
                () -> assertEquals(42000, target.getStats(id).getTotalVanishTimeMs()));
    }

    @Test
    void sqlTemporaryRuleDoesNotOverwriteAnotherProvidersNewerDecision() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:expiry_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage source = sqlStorage(dataSource);
        source.init();
        field(plugin, "storageProvider", source);
        plugin.getRuleManager().setRule(player, "can_chat", true);
        plugin.scheduleRuleRevert(player, "can_chat", false, 0);
        scheduler.drainAsync();
        SqlStorage otherServer = sqlStorage(dataSource);
        otherServer.init();
        otherServer.setRule(player.getUniqueId(), "can_chat", true);
        scheduler.runDelayedPass();
        scheduler.drainAsync();
        assertTrue(otherServer.getRule(player.getUniqueId(), "can_chat", false),
                "SQL expiry must not undo a newer decision from another provider");
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
        scheduler.drainAsync();
        plugin.cleanupPlayerCache(player.getUniqueId());
        YamlStorage reopened = new YamlStorage(plugin);
        reopened.init();
        field(plugin, "storageProvider", reopened);
        login();
        assertTrue(plugin.isWarningIgnored(player), "persisted warning acknowledgement must load on login");
    }

    @Test
    void reEnablingWarningsRemovesThePersistedAcknowledgement() {
        plugin.setWarningIgnored(player, true);
        plugin.setWarningIgnored(player, false);
        scheduler.drainAsync();
        assertFalse(plugin.getStorageProvider().hasAcknowledged(player.getUniqueId(), "protocol-lib-warning"),
                "re-enabling warnings must persist the newer preference");
    }

    @Test
    void visibleProxyReplyPreservesOrdinarySpectatorFlightAndHooks() throws Exception {
        var hooks = mock(net.thecommandcraft.vanishpp.hooks.WebhookManager.class);
        field(plugin, "webhookManager", hooks);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        plugin.holdProxyJoin(player);
        plugin.completeProxyJoin(player, false);
        assertAll(
                () -> assertEquals(GameMode.SPECTATOR, player.getGameMode(), "visible join must preserve ordinary spectator mode"),
                () -> assertTrue(player.getAllowFlight(), "visible join must preserve existing flight permission"),
                () -> assertTrue(player.isFlying(), "visible join must preserve existing flight"),
                () -> verifyNoInteractions(hooks));
    }

    @Test
    void gamemodeRestorationDoesNotReadOrWriteStorageOnTheEntityThread() throws Exception {
        StorageProvider storage = spy(plugin.getStorageProvider());
        field(plugin, "storageProvider", storage);
        player.setGameMode(GameMode.CREATIVE);
        plugin.saveGamemodeForRestore(player);
        player.setGameMode(GameMode.SPECTATOR);
        plugin.pendingGamemodeRestore.clear();
        plugin.restoreSavedGamemode(player);
        assertEquals(GameMode.CREATIVE, player.getGameMode());
        verifyNoInteractions(storage);
    }

    @Test
    void failedRuleReadCannotEraseSavedGamemode() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:gamemode_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage sql = sqlStorage(database);
        sql.init();
        field(plugin, "storageProvider", sql);
        player.setGameMode(GameMode.CREATIVE);
        plugin.saveGamemodeForRestore(player);
        scheduler.drainAsync();
        player.setGameMode(GameMode.SPECTATOR);
        plugin.pendingGamemodeRestore.clear();
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("temporary read failure"));
        var sourceField = SqlStorage.class.getDeclaredField("dataSource");
        sourceField.setAccessible(true);
        sourceField.set(sql, unavailable);
        assertDoesNotThrow(() -> plugin.restoreSavedGamemode(player));
        sourceField.set(sql, database);
        assertEquals(GameMode.CREATIVE, player.getGameMode(), "failed rule reads must not erase the saved original gamemode");
    }

    @Test
    void temporaryRuleStorageRunsOnlyOnAnAsyncWorker() throws Exception {
        StorageProvider storage = spy(plugin.getStorageProvider());
        List<String> contexts = new ArrayList<>();
        doAnswer(call -> { contexts.add(scheduler.context); return call.callRealMethod(); })
                .when(storage).setTemporaryRule(any(), anyString(), anyBoolean(), anyString());
        doAnswer(call -> { contexts.add(scheduler.context); return call.callRealMethod(); })
                .when(storage).expireRule(any(), anyString(), anyString());
        field(plugin, "storageProvider", storage);
        plugin.setTemporaryRule(player, "can_chat", true, false, 0);
        scheduler.drainAsync();
        scheduler.runDelayedPass();
        scheduler.drainAsync();
        assertEquals(List.of("async", "async"), contexts, "temporary-rule persistence and expiry must both run off the event thread");
    }

    @Test
    void expiredRuleRefreshesThePlayerOnItsEntityScheduler() {
        plugin.applyVanishEffects(player);
        scheduler.drainAsync();
        plugin.setTemporaryRule(player, "can_chat", true, false, 0);
        scheduler.drainAsync();
        List<String> contexts = new ArrayList<>();
        player.invisibilityObserver = () -> contexts.add(scheduler.context);
        scheduler.runDelayedPass();
        scheduler.drainAsync();
        assertFalse(contexts.isEmpty(), "expiry must actually refresh the vanished player");
        assertTrue(contexts.stream().allMatch("entity"::equals), "expiry must mutate the player only on its entity scheduler: " + contexts);
    }

    @Test
    void failedRecoveryScanKeepsTheExistingExpiryAlive() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:scan_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage sql = sqlStorage(database);
        sql.init();
        field(plugin, "storageProvider", sql);
        plugin.setTemporaryRule(player, "can_chat", true, false, 0);
        scheduler.drainAsync();
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("temporary recovery failure"));
        var sourceField = SqlStorage.class.getDeclaredField("dataSource");
        sourceField.setAccessible(true);
        sourceField.set(sql, unavailable);
        assertDoesNotThrow(() -> { plugin.storageRecovered(sql); scheduler.drainAsync(); },
                "recovery must retain its previous generation and retry unavailable reads");
        sourceField.set(sql, database);
        scheduler.runDelayedPass();
        scheduler.drainAsync();
        assertFalse(sql.getRule(player.getUniqueId(), "can_chat", true), "failed scan must not cancel the only live expiry");
    }

    @Test
    void repeatedRecoveryReplacesLongExpiryTasks() {
        plugin.setTemporaryRule(player, "can_chat", true, false, 86400);
        scheduler.drainAsync();
        int before = scheduler.later.size();
        for (int i = 0; i < 5; i++) {
            plugin.storageRecovered(plugin.getStorageProvider());
            scheduler.drainAsync();
        }
        assertEquals(before, scheduler.later.size(), "recovery must cancel replaced long-deadline tasks instead of retaining epoch no-ops");
    }

    @Test
    void failedFinalSessionSaveDoesNotAbortOtherSavesOrCleanup() throws Exception {
        StorageProvider storage = mock(StorageProvider.class);
        UUID other = UUID.randomUUID();
        List<UUID> attempted = new ArrayList<>();
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            attempted.add(id);
            if (id.equals(player.getUniqueId())) throw new IllegalStateException("final statistics unavailable");
            return null;
        }).when(storage).recordVanishSession(any(), anyLong());
        field(plugin, "storageProvider", storage);
        ProxyBridge bridge = mock(ProxyBridge.class);
        RedisStorage redis = mock(RedisStorage.class);
        field(plugin, "proxyBridge", bridge);
        field(plugin, "redisStorage", redis);
        plugin.vanishStartTimes.put(player.getUniqueId(), System.currentTimeMillis() - 10000);
        plugin.vanishStartTimes.put(other, System.currentTimeMillis() - 10000);
        assertAll(
                () -> assertDoesNotThrow(plugin::onDisable, "one failed final save must not abort shutdown"),
                () -> assertTrue(attempted.containsAll(List.of(player.getUniqueId(), other)), "each final session must be attempted"),
                () -> verify(storage).shutdown(),
                () -> verify(bridge).shutdown(),
                () -> verify(redis).shutdown(),
                () -> assertTrue(scheduler.cancelled, "scheduler cleanup must still run"));
    }

    @Test
    void coldFirstCarrierCompletesHelloBeforeItsJoinTimeout() throws Exception {
        player.disconnect();
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin);
        field(plugin, "proxyBridge", bridge);
        bridge.init();
        scheduler.runDelayedPass(); // Empty server: HELLO retry is now five seconds away.
        player = new RecordingPlayer(server);
        server.addPlayer(player);
        login();
        scheduler.runImmediate();
        assertTrue(player.packets.stream().anyMatch(packet -> packet.type()
                == net.thecommandcraft.vanishpp.common.protocol.VppMessage.HELLO),
                "first carrier must start HELLO immediately instead of racing the five-second join timeout");
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.PONG, "{\"session\":\"cold-proxy\"}"));
        var query = player.packets.stream().filter(packet -> packet.type()
                == net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_QUERY)
                .map(packet -> com.google.gson.JsonParser.parseString(packet.jsonPayload()).getAsJsonObject())
                .filter(json -> json.has("requestId")).findFirst();
        assertTrue(query.isPresent(), "PONG must release the pending targeted join query");
        var reply = new com.google.gson.JsonObject();
        reply.add("vanished", new com.google.gson.JsonArray());
        reply.addProperty("requestId", query.orElseThrow().get("requestId").getAsString());
        reply.addProperty("joiningUuid", player.getUniqueId().toString());
        reply.addProperty("joiningVanished", false);
        reply.addProperty("session", "cold-proxy");
        reply.addProperty("revision", 0);
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_RESPONSE, reply.toString()));
        assertFalse(plugin.isVanished(player), "targeted cold reply must release the provisional hold");
        assertTrue(player.isOnline(), "a completed cold handshake must not kick the carrier");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void redisRecoveryCannotOverwriteAQueuedLocalDecision(boolean desired) throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:redis_pending_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage sql = sqlStorage(database);
        sql.init();
        field(plugin, "storageProvider", sql);
        if (!desired) { plugin.applyVanishEffects(player); scheduler.drainAsync(); }
        if (desired) plugin.applyVanishEffects(player); else plugin.removeVanishEffects(player);
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var stopped = new java.util.concurrent.CountDownLatch(1);
        var subscriber = new java.util.concurrent.atomic.AtomicReference<redis.clients.jedis.JedisPubSub>();
        var jedis = mock(redis.clients.jedis.Jedis.class);
        doAnswer(call -> {
            subscriber.set(call.getArgument(0)); subscribed.countDown();
            stopped.await(5, java.util.concurrent.TimeUnit.SECONDS); return null;
        }).when(jedis).subscribe(any(redis.clients.jedis.JedisPubSub.class), eq("vanishpp:sync"));
        doAnswer(call -> { stopped.countDown(); return null; }).when(jedis).disconnect();
        try (var pools = mockConstruction(redis.clients.jedis.JedisPool.class,
                (pool, context) -> when(pool.getResource()).thenReturn(jedis))) {
            RedisStorage redis = new RedisStorage(plugin);
            redis.init();
            try {
                assertTrue(subscribed.await(5, java.util.concurrent.TimeUnit.SECONDS), "subscriber fixture must connect");
                subscriber.get().onSubscribe("vanishpp:sync", 1);
                scheduler.drainAsync();
                assertAll(
                        () -> assertEquals(desired, plugin.isVanished(player), "recovery snapshot must not undo a locally queued decision"),
                        () -> assertEquals(desired, sql.isVanished(player.getUniqueId()), "latest local decision must remain persisted"));
            } finally { redis.shutdown(); stopped.countDown(); }
        }
    }

    @Test
    void historyMigrationUsesOneStableSnapshotBeyondAPageBoundary() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:history_snapshot_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage writer = sqlStorage(database);
        writer.init();
        var stamp = java.time.Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 600; i++) writer.addHistoryEntry(new VanishHistoryEntry(player.getUniqueId(), "Staff",
                VanishHistoryEntry.Action.VANISH, stamp, "lobby", "entry-" + i, 0));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        DataSource intercepted = mock(DataSource.class);
        when(intercepted.getConnection()).thenAnswer(call -> {
            java.sql.Connection real = database.getConnection();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                    (self, method, args) -> {
                        Object result = invokeJdbc(method, real, args);
                        if (!method.getName().equals("prepareStatement") || !((String) args[0]).startsWith("SELECT * FROM vpp_history")) return result;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                                (statement, operation, values) -> {
                                    Object rows = invokeJdbc(operation, result, values);
                                    if (!operation.getName().equals("executeQuery")) return rows;
                                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.ResultSet.class},
                                            (cursor, move, parameters) -> {
                                                Object value = invokeJdbc(move, rows, parameters);
                                                if (move.getName().equals("next") && Boolean.TRUE.equals(value) && reads.incrementAndGet() == 500)
                                                    writer.addHistoryEntry(new VanishHistoryEntry(player.getUniqueId(), "Staff", VanishHistoryEntry.Action.VANISH,
                                                            stamp.plusSeconds(1), "lobby", "concurrent", 0));
                                                return value;
                                            });
                                });
                    });
        });
        SqlStorage source = sqlStorage(intercepted);
        source.init();
        field(plugin, "storageProvider", source);
        StorageProvider target = mock(StorageProvider.class);
        List<String> copied = new ArrayList<>();
        doAnswer(call -> { copied.add(((VanishHistoryEntry) call.getArgument(0)).getReason()); return null; })
                .when(target).addHistoryEntry(any());
        var migrate = Vanishpp.class.getDeclaredMethod("migrateIfNeeded", StorageProvider.class, String.class);
        migrate.setAccessible(true);
        migrate.invoke(plugin, target, "YAML");
        assertAll(
                () -> assertEquals(601, writer.getAllHistory(1, 1000).size(), "concurrent-write stimulus must actually execute"),
                () -> assertEquals(600, copied.size(), "migration must read a stable history snapshot instead of shifting OFFSET pages"),
                () -> assertEquals(600, new HashSet<>(copied).size(), "every tied-timestamp history row must be copied exactly once"));
    }

    private static Object invokeJdbc(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
    }

    @Test
    void reloadAndRecoveryPerformStorageWorkOnlyOnAsyncWorkers() throws Exception {
        StorageProvider storage = spy(plugin.getStorageProvider());
        List<String> contexts = new ArrayList<>();
        doAnswer(call -> { contexts.add(scheduler.context); return call.callRealMethod(); })
                .when(storage).getAllKnownPlayers();
        doAnswer(call -> { contexts.add(scheduler.context); return call.callRealMethod(); })
                .when(storage).setVanished(any(), anyBoolean());
        field(plugin, "storageProvider", storage);
        plugin.storageRecovered(storage);
        scheduler.drainAsync();
        plugin.applyVanishEffects(player);
        try (var constructed = mockConstruction(YamlStorage.class, (created, context) -> {
            doAnswer(call -> { contexts.add(scheduler.context); return null; }).when(created).init();
        })) {
            plugin.reloadPluginConfig();
            scheduler.drainAsync();
        }
        assertFalse(contexts.isEmpty(), "storage work must actually run");
        assertTrue(contexts.stream().allMatch("async"::equals), "reload and recovery must not drain or scan storage on event/global threads: " + contexts);
    }

    @Test
    void transferReplyWaitsForStorageAndFreezesNewDecisionsUntilCancelled() throws Exception {
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin);
        field(plugin, "proxyBridge", bridge);
        bridge.init(); scheduler.runImmediate();
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.PONG, "{\"session\":\"transfer-proxy\"}"));
        plugin.applyVanishEffects(player);
        String payload = "{\"uuid\":\"" + player.getUniqueId() + "\",\"requestId\":\"transfer-1\"}";
        bridge.onPluginMessageReceived("vanishpp:proxy", player, rawPacket(14, payload));
        assertTrue(player.packets.stream().noneMatch(packet -> packet.type().name().equals("TRANSFER_READY")),
                "transfer reply must wait for accepted storage work");
        scheduler.drainAsync();
        var ready = player.packets.stream().filter(packet -> packet.type().name().equals("TRANSFER_READY")).findFirst();
        assertTrue(ready.isPresent(), "settled source must send its final decision before losing the carrier");
        assertTrue(com.google.gson.JsonParser.parseString(ready.orElseThrow().jsonPayload()).getAsJsonObject().get("vanished").getAsBoolean());
        plugin.removeVanishEffects(player);
        assertTrue(plugin.isVanished(player), "source must hold its decision while the transfer is in flight");
        bridge.onPluginMessageReceived("vanishpp:proxy", player, rawPacket(16, payload));
        plugin.removeVanishEffects(player);
        assertFalse(plugin.isVanished(player), "cancelled transfer must release the decision hold");
    }

    private static byte[] rawPacket(int ordinal, String json) {
        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(body.length + 5).put((byte) ordinal).putInt(body.length).put(body).array();
    }

    @Test
    void unavailableSqlRetryDoesNotConnectOnTheEventThread() throws Exception {
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("database unavailable"));
        SqlStorage sql = sqlStorage(unavailable);
        assertThrows(IllegalStateException.class, () -> sql.isVanished(player.getUniqueId()));
        verifyNoInteractions(unavailable);
        scheduler.drainAsync();
        verify(unavailable, atLeastOnce()).getConnection();
    }

    @Test
    void failedSqlFinalStatisticsAreReportedToTheShutdownCaller() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:final_failure_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage sql = sqlStorage(database);
        sql.init();
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("final save unavailable"));
        var sourceField = SqlStorage.class.getDeclaredField("dataSource");
        sourceField.setAccessible(true);
        sourceField.set(sql, unavailable);
        assertThrows(IllegalStateException.class, () -> sql.recordVanishSession(player.getUniqueId(), 5000),
                "SQL must report a failed final save instead of letting shutdown mark it successful");
    }

    @Test
    void failedPostInitializationRuleScanKeepsTheLiveDeadline() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:rule_scan_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        AtomicBoolean failRules = new AtomicBoolean();
        DataSource intercepted = mock(DataSource.class);
        when(intercepted.getConnection()).thenAnswer(call -> {
            java.sql.Connection real = database.getConnection();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                    (self, method, args) -> {
                        if (failRules.get() && method.getName().equals("prepareStatement")
                                && ((String) args[0]).startsWith("SELECT rule_key, rule_value"))
                            throw new java.sql.SQLException("temporary rule scan failure");
                        return invokeJdbc(method, real, args);
                    });
        });
        SqlStorage sql = sqlStorage(intercepted);
        sql.init();
        field(plugin, "storageProvider", sql);
        plugin.setTemporaryRule(player, "can_chat", true, false, 0);
        scheduler.drainAsync();
        failRules.set(true);
        plugin.storageRecovered(sql);
        scheduler.drainAsync();
        failRules.set(false);
        scheduler.runDelayedPass();
        scheduler.drainAsync();
        assertFalse(sql.getRule(player.getUniqueId(), "can_chat", true),
                "a failed rules scan must not replace a live expiry with successful absence");
    }

    @Test
    void repeatedReloadCancelsReplacedLongDeadlineTasks() {
        plugin.setTemporaryRule(player, "can_chat", true, false, 86400);
        scheduler.drainAsync();
        for (int i = 0; i < 5; i++) { plugin.reloadPluginConfig(); scheduler.drainAsync(); }
        long longTasks = scheduler.later.stream().filter(task -> scheduler.delays.getOrDefault(task, 0L) > 1000000L).count();
        assertEquals(1L, longTasks, "repeated reload must retain exactly one live long-deadline task");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void interruptedProxyJoinNeverBecomesBootstrapState(boolean timeout) throws Exception {
        if (timeout) {
            plugin.getConfigManager().getConfig().set("proxy.enabled", true);
            ProxyBridge bridge = new ProxyBridge(plugin);
            field(plugin, "proxyBridge", bridge);
            bridge.init(); scheduler.runImmediate();
            bridge.onPluginMessageReceived("vanishpp:proxy", player,
                    net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                            net.thecommandcraft.vanishpp.common.protocol.VppMessage.PONG, "{\"session\":\"quit-proxy\"}"));
            plugin.holdProxyJoin(player);
            bridge.requestJoinState(player, hidden -> plugin.completeProxyJoin(player, hidden));
            scheduler.runDelayedPass();
            assertFalse(player.isOnline(), "timeout must kick the provisional join");
        } else plugin.holdProxyJoin(player);
        new PlayerListener(plugin).onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, Component.text("quit")));
        boolean staleHidden = plugin.isVanished(player);
        boolean staleBootstrap = plugin.getBootstrapVanishedPlayers().contains(player.getUniqueId());
        PlayerMock newer = server.addPlayer();
        plugin.holdProxyJoin(newer);
        plugin.applyVanishEffects(newer);
        new PlayerListener(plugin).onQuit(new org.bukkit.event.player.PlayerQuitEvent(newer, Component.text("quit")));
        assertAll(
                () -> assertFalse(staleHidden, "quit-before-reply must undo provisional vanished state"),
                () -> assertFalse(staleBootstrap, "interrupted provisional joins must never enter proxy bootstrap"),
                () -> assertTrue(plugin.isVanished(newer), "cleanup must preserve a real decision newer than the provisional hold"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void failedStateWriteRetriesWithoutAnotherToggleAndReleasesReconciliation(boolean desired) throws Exception {
        StorageProvider storage = spy(plugin.getStorageProvider());
        field(plugin, "storageProvider", storage);
        if (!desired) { plugin.applyVanishEffects(player); scheduler.drainAsync(); }
        doThrow(new IllegalStateException("transient write failure")).doCallRealMethod()
                .when(storage).setVanished(player.getUniqueId(), desired);
        if (desired) plugin.applyVanishEffects(player); else plugin.removeVanishEffects(player);
        scheduler.drainAsync();
        assertTrue(plugin.hasPendingStateWrite(player.getUniqueId()), "failed decision must remain pending until retried");
        scheduler.runDelayedPass(); scheduler.drainAsync();
        boolean persisted = storage.isVanished(player.getUniqueId());
        boolean pending = plugin.hasPendingStateWrite(player.getUniqueId());
        storage.setVanished(player.getUniqueId(), !desired); // A later remote decision after local persistence settles.
        plugin.reconcileSharedState(storage, () -> true);
        scheduler.drainAsync();
        assertAll(
                () -> assertEquals(desired, persisted, "transient failure must retry the latest accepted state without another toggle"),
                () -> assertFalse(pending, "successful retry must settle its pending marker"),
                () -> assertEquals(!desired, plugin.isVanished(player), "subsequent remote reconciliation must no longer be excluded"));
    }

    @Test
    void yamlReloadCannotFlushAnOldSnapshotOverANewAcknowledgement() throws Exception {
        plugin.getStorageProvider().setVanished(UUID.randomUUID(), true); // Nonempty destination: no migration copy.
        YamlStorage old = spy((YamlStorage) plugin.getStorageProvider());
        field(plugin, "storageProvider", old);
        AtomicBoolean accepted = new AtomicBoolean();
        doAnswer(call -> {
            plugin.setWarningIgnored(player, true); // Pause between replacement publication and old close.
            accepted.set(true);
            return call.callRealMethod();
        }).when(old).shutdown();
        plugin.reloadPluginConfig(); scheduler.drainAsync();
        // Reusing the same owner has no replacement/close boundary; exercise its normal write path instead.
        if (plugin.getStorageProvider() == old) { plugin.setWarningIgnored(player, true); accepted.set(true); }
        assertTrue(accepted.get(), "the concurrent/new-owner acknowledgement must actually be accepted");
        scheduler.drainAsync();
        YamlStorage reopened = new YamlStorage(plugin);
        reopened.init();
        assertTrue(reopened.hasAcknowledged(player.getUniqueId(), "protocol-lib-warning"),
                "old YAML shutdown must not overwrite a newer accepted acknowledgement");
        doNothing().when(old).shutdown();
    }

    @Test
    void activeSqlRecoveryIsUnavailableUntilYamlMigrationAndDeadlinesFinish() throws Exception {
        plugin.getStorageProvider().setVanished(player.getUniqueId(), true);
        plugin.setTemporaryRule(player, "can_chat", true, false, 0);
        scheduler.drainAsync();
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:active_recovery_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage schema = sqlStorage(database); schema.init();
        plugin.getConfigManager().getConfig().set("storage.type", "MYSQL");
        field(plugin, "storageProvider", null);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var pools = mockConstruction(com.zaxxer.hikari.HikariDataSource.class, (pool, context) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("initial SQL outage");
            when(pool.getConnection()).thenAnswer(call -> database.getConnection());
        })) {
            var initialize = Vanishpp.class.getDeclaredMethod("initStorage"); initialize.setAccessible(true); initialize.invoke(plugin);
            StorageProvider active = plugin.getStorageProvider();
            assertInstanceOf(SqlStorage.class, active, "fixture must use the actual active recovering provider");
            assertThrows(IllegalStateException.class, () -> active.isVanished(player.getUniqueId()));
            scheduler.run("async", scheduler.async.removeFirst()); // Initialize, but leave queued migration/restoration paused.
            assertAll(
                    () -> assertThrows(IllegalStateException.class, () -> active.isVanished(player.getUniqueId()),
                            "SQL must remain unavailable before migration/restoration publishes readiness"),
                    () -> {
                        scheduler.drainAsync();
                        assertTrue(active.isVanished(player.getUniqueId()), "recovery must import persisted YAML vanish before authoritative reads");
                        scheduler.runDelayedPass(); scheduler.drainAsync();
                        assertFalse(active.getRule(player.getUniqueId(), "can_chat", true), "migrated deadlines must execute after recovery");
                    });
        }
    }

    @Test
    void acceptedTransferKeepsItsDecisionFenceBeyondTenSeconds() throws Exception {
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin); field(plugin, "proxyBridge", bridge);
        bridge.init(); scheduler.runImmediate();
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.PONG, "{\"session\":\"long-transfer\"}"));
        plugin.applyVanishEffects(player);
        String payload = "{\"uuid\":\"" + player.getUniqueId() + "\",\"requestId\":\"long-1\"}";
        bridge.onPluginMessageReceived("vanishpp:proxy", player, rawPacket(14, payload));
        scheduler.drainAsync();
        assertTrue(player.packets.stream().anyMatch(packet -> packet.type().name().equals("TRANSFER_READY")), "source must be ready before the stalled-switch stimulus");
        scheduler.runDelayedPass(); // Includes every scheduled task, including the 200-tick unlock.
        plugin.removeVanishEffects(player);
        assertTrue(plugin.isVanished(player), "elapsed source ticks must not unlock an accepted but unfinished transfer");
        bridge.onPluginMessageReceived("vanishpp:proxy", player, rawPacket(16, payload));
        plugin.removeVanishEffects(player);
        assertFalse(plugin.isVanished(player), "authenticated cancellation must still release the source fence");
    }

    @Test
    void anAlreadySelectedRetryCannotOverwriteANewerAcceptedDecision() throws Exception {
        StorageProvider storage = spy(plugin.getStorageProvider());
        field(plugin, "storageProvider", storage);
        doThrow(new IllegalStateException("old write failed")).doCallRealMethod()
                .when(storage).setVanished(player.getUniqueId(), true);
        plugin.applyVanishEffects(player); scheduler.drainAsync();
        var retry = scheduler.later.stream().filter(task -> scheduler.delays.getOrDefault(task, 0L) == 5L).findFirst();
        assertTrue(retry.isPresent(), "failed state must schedule a retry");
        plugin.removeVanishEffects(player); scheduler.drainAsync();
        assertFalse(storage.isVanished(player.getUniqueId()), "newer decision must persist before the old selected callback runs");
        scheduler.run("global", retry.orElseThrow()); // Cancellation cannot revoke a callback already selected by a scheduler.
        scheduler.drainAsync();
        assertFalse(storage.isVanished(player.getUniqueId()), "superseded retry must not overwrite the latest accepted decision");
        assertFalse(plugin.hasPendingStateWrite(player.getUniqueId()));
    }

    @Test
    void acknowledgementAcceptedDuringMigrationReachesThePublishedProvider() throws Exception {
        plugin.getStorageProvider().setVanished(player.getUniqueId(), true);
        plugin.getConfigManager().getConfig().set("storage.type", "MYSQL");
        plugin.getConfigManager().getConfig().save(new java.io.File(plugin.getDataFolder(), "config.yml"));
        Set<String> copied = new HashSet<>();
        AtomicBoolean admitted = new AtomicBoolean();
        try (var providers = mockConstruction(SqlStorage.class, (target, context) -> {
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(target).migrateAtomically(any());
            doAnswer(call -> { copied.add(call.getArgument(1)); return null; }).when(target).addAcknowledgement(any(), anyString());
            doAnswer(call -> {
                if (admitted.compareAndSet(false, true)) plugin.setWarningIgnored(player, true);
                return null;
            }).when(target).setStats(any(), any()); // This player's acknowledgement snapshot has already been copied.
        })) {
            plugin.reloadPluginConfig(); scheduler.drainAsync();
            assertTrue(admitted.get(), "a new decision must be accepted during the migration copy");
            assertTrue(copied.contains("protocol-lib-warning"),
                    "acknowledgements accepted during migration must be ordered onto the new provider");
        }
    }

    @Test
    void failedAcknowledgementRecoveryDoesNotPublishSqlReadiness() throws Exception {
        plugin.setWarningIgnored(player, true); scheduler.drainAsync();
        plugin.getIgnoredWarningPlayers().clear();
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:ack_recovery_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        SqlStorage schema = sqlStorage(database); schema.init();
        plugin.getConfigManager().getConfig().set("storage.type", "MYSQL");
        field(plugin, "storageProvider", null);
        AtomicBoolean failRead = new AtomicBoolean(true), attempted = new AtomicBoolean();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var pools = mockConstruction(com.zaxxer.hikari.HikariDataSource.class, (pool, context) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("initial outage");
            when(pool.getConnection()).thenAnswer(call -> {
                java.sql.Connection real = database.getConnection();
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                        (self, method, args) -> {
                            if (method.getName().equals("prepareStatement") && failRead.get()
                                    && (((String) args[0]).startsWith("SELECT notification_id")
                                    || ((String) args[0]).startsWith("SELECT 1 FROM vpp_acknowledgements"))) {
                                attempted.set(true); throw new java.sql.SQLException("acknowledgement read unavailable");
                            }
                            return invokeJdbc(method, real, args);
                        });
            });
        })) {
            var initialize = Vanishpp.class.getDeclaredMethod("initStorage"); initialize.setAccessible(true); initialize.invoke(plugin);
            StorageProvider active = plugin.getStorageProvider();
            assertThrows(IllegalStateException.class, () -> active.isVanished(player.getUniqueId()));
            scheduler.drainAsync();
            assertTrue(attempted.get(), "recovery acknowledgement read must actually fail");
            assertAll(
                    () -> assertThrows(IllegalStateException.class, () -> active.isVanished(player.getUniqueId()),
                            "failed acknowledgement restoration must keep SQL recovery unavailable"),
                    () -> {
                        failRead.set(false); scheduler.runDelayedPass(); scheduler.drainAsync();
                        assertTrue(plugin.isWarningIgnored(player), "acknowledgement restoration must retry before readiness");
                    });
        }
    }

    @Test
    void transferReadyPreservesEffectiveLevelAndRejectsLaterRefreshes() throws Exception {
        player.setOp(false);
        plugin.getConfigManager().maxLevel = 10;
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.vanish.level.9");
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin); field(plugin, "proxyBridge", bridge);
        bridge.init(); scheduler.runImmediate();
        bridge.onPluginMessageReceived("vanishpp:proxy", player,
                net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(
                        net.thecommandcraft.vanishpp.common.protocol.VppMessage.PONG, "{\"session\":\"level-transfer\"}"));
        plugin.applyVanishEffects(player);
        String payload = "{\"uuid\":\"" + player.getUniqueId() + "\",\"requestId\":\"level-1\"}";
        bridge.onPluginMessageReceived("vanishpp:proxy", player, rawPacket(14, payload));
        scheduler.drainAsync();
        var ready = player.packets.stream().filter(packet -> packet.type().name().equals("TRANSFER_READY")).findFirst().orElseThrow();
        int level = com.google.gson.JsonParser.parseString(ready.jsonPayload()).getAsJsonObject().get("vanishLevel").getAsInt();
        player.packets.clear();
        bridge.sendVanishEvent(player.getUniqueId(), player.getName(), true, 10);
        boolean sentWhileFenced = !player.packets.isEmpty();
        bridge.onPluginMessageReceived("vanishpp:proxy", player, rawPacket(16, payload));
        bridge.sendVanishEvent(player.getUniqueId(), player.getName(), true, 10);
        assertAll(
                () -> assertEquals(9, level, "READY must retain effective permission visibility level instead of downgrading it to stored default"),
                () -> assertFalse(sentWhileFenced, "level refreshes must not create a newer source decision after READY"),
                () -> assertFalse(player.packets.isEmpty(), "authenticated cancellation must allow later level publication"));
    }

    private void login() {
        PlayerListener listener = new PlayerListener(plugin);
        listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId()));
        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));
    }

    private SqlStorage sqlStorage(DataSource source) throws Exception {
        var constructor = SqlStorage.class.getDeclaredConstructor(Vanishpp.class, DataSource.class);
        constructor.setAccessible(true);
        return constructor.newInstance(plugin, source);
    }

    private static void field(Object object, String name, Object value) throws Exception {
        Field field = Vanishpp.class.getDeclaredField(name);
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

    private static class WatchingPlayer extends PlayerMock {
        final List<UUID> shown = new ArrayList<>();
        WatchingPlayer(ServerMock server) { super(server, "Watcher"); }
        @Override public void showPlayer(org.bukkit.plugin.Plugin source, org.bukkit.entity.Player subject) {
            shown.add(subject.getUniqueId());
            super.showPlayer(source, subject);
        }
    }

    private static class RecordingPlayer extends PlayerMock {
        Runnable invisibilityObserver;
        @Override public void setInvisible(boolean invisible) {
            super.setInvisible(invisible);
            if (invisibilityObserver != null) invisibilityObserver.run();
        }
        final List<net.thecommandcraft.vanishpp.common.protocol.VppPacket.DecodedPacket> packets = new ArrayList<>();
        RecordingPlayer(ServerMock server) { super(server, "Staff"); }
        @Override public void sendPluginMessage(org.bukkit.plugin.Plugin source, String channel, byte[] message) {
            if (channel.equals("vanishpp:proxy")) packets.add(net.thecommandcraft.vanishpp.common.protocol.VppPacket.decode(message));
        }
    }

    private static class ControlledScheduler implements VanishScheduler {
        String context = "event";
        boolean cancelled;
        final List<Runnable> async = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final Map<Runnable, Long> delays = new HashMap<>();
        final List<Runnable> timers = new ArrayList<>();
        void run(String owner, Runnable task) { String old = context; context = owner; try { task.run(); } finally { context = old; } }
        public void runGlobal(Runnable task) { run("global", task); }
        public void runLaterGlobal(Runnable task, long ticks) { later.add(task); delays.put(task, ticks); }
        public void runTimerGlobal(Runnable task, long delay, long period) { timers.add(task); }
        public void runEntity(Entity entity, Runnable task, Runnable retired) { run("entity", task); }
        public void runEntityNow(Entity entity, Runnable task) { run("entity", task); }
        public void runAsync(Runnable task) { async.add(task); }
        public Runnable startLater(Runnable task, long ticks) { runLaterGlobal(task, ticks); return () -> { later.remove(task); delays.remove(task); }; }
        public void cancelAllTasks() { cancelled = true; later.clear(); timers.clear(); async.clear(); }
        void drainAsync() { while (!async.isEmpty()) run("async", async.removeFirst()); }
        void runDelayedPass() { List<Runnable> due = List.copyOf(later); later.clear(); due.forEach(task -> run("global", task)); }
        void runImmediate() {
            for (Runnable task : List.copyOf(later)) if (delays.getOrDefault(task, 100L) <= 1L) { later.remove(task); run("global", task); }
        }
        void fireTimers() { List.copyOf(timers).forEach(Runnable::run); }
    }
}
