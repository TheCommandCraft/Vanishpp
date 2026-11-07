package net.thecommandcraft.vanishpp.velocity.messaging;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.scheduler.*;
import net.thecommandcraft.vanishpp.common.protocol.*;
import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import net.thecommandcraft.vanishpp.velocity.*;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;
import net.thecommandcraft.vanishpp.velocity.listener.VelocityPlayerListener;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ProxyProtocolTest {
    @TempDir Path directory;
    private final UUID id = UUID.randomUUID();
    private final List<VppPacket.DecodedPacket> sent = new ArrayList<>();
    private final List<Runnable> timeouts = new ArrayList<>();
    private ServerConnection current;
    private ServerConnection source;
    private ServerConnection destination;
    private Player player;
    private VanishppVelocity plugin;
    private ProxyStateManager states;
    private PaperChannelListener channel;
    private PaperChannelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        RegisteredServer a = server("a"), b = server("b");
        source = connection(a); destination = connection(b); current = source;
        player = stub(Player.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getUsername" -> "Staff";
            case "getCurrentServer" -> Optional.ofNullable(current);
            case "isActive" -> true;
            default -> null;
        });
        Scheduler scheduler = stub(Scheduler.class, (method, args) -> {
            if (!method.getName().equals("buildTask")) return null;
            Runnable task = (Runnable) args[1];
            return taskBuilder(task);
        });
        ProxyServer proxy = stub(ProxyServer.class, (method, args) -> switch (method.getName()) {
            case "getPlayer" -> Optional.of(player);
            case "getAllPlayers" -> List.of(player);
            case "getAllServers" -> List.of(a, b);
            case "getServer" -> Optional.of(args[0].equals("a") ? a : b);
            case "getScheduler" -> scheduler;
            default -> null;
        });
        var logger = LoggerFactory.getLogger(getClass());
        var config = new VelocityConfigManager(directory, logger);
        config.load();
        plugin = new VanishppVelocity(proxy, logger, directory);
        states = new ProxyStateManager(plugin, config);
        states.init();
        dispatcher = new PaperChannelDispatcher(proxy);
        channel = new PaperChannelListener(plugin, states, dispatcher, config);
        joinQuery(source);
    }

    /** A backend only asks for a joining player's state once its Vanish++ handshake has finished. */
    private void joinQuery(ServerConnection from) {
        JsonObject query = new JsonObject();
        query.addProperty("joiningUuid", id.toString()); query.addProperty("requestId", "join");
        event(from, VppMessage.STATE_QUERY, query);
    }

    @Test
    void transferFromABackendWithoutAFinishedHandshakeIsNotHeld() throws Exception {
        current = connection(server("plain"));
        AtomicBoolean resumed = new AtomicBoolean();
        var transfer = beginTransfer(resumed);
        assertTrue(resumed.get(), "a backend that never finished the handshake must not hold the switch");
        assertTrue(transfer.getResult().isAllowed(), "a backend that never finished the handshake must not deny the switch");
        current = connection(source.getServer());
        resumed.set(false);
        transfer = beginTransfer(resumed);
        assertTrue(resumed.get() && transfer.getResult().isAllowed(),
                "a new connection to a restarted backend must finish the handshake again before it holds switches");
        assertTrue(sent.stream().noneMatch(packet -> packet.type() == VppMessage.TRANSFER_PREPARE),
                "only connected backends receive a transfer barrier request");
    }

    @Test
    void fallbackFromABackendWithoutAFinishedHandshakeIsAllowed() throws Exception {
        RegisteredServer plain = server("plain");
        current = connection(plain);
        var transfer = new ServerPreConnectEvent(player, destination.getServer(), plain);
        current = null;
        AtomicBoolean resumed = new AtomicBoolean();
        beginTransfer(transfer, resumed);
        assertTrue(resumed.get());
        assertTrue(transfer.getResult().isAllowed(), "a kick from a backend without Vanish++ must still reach the fallback server");
    }

    @AfterEach
    void close() { states.shutdown(); }

    @Test
    void bootstrapImportsUnknownPlayersButNeverOverridesKnownVisibleDecisions() {
        UUID unknown = UUID.randomUUID();
        states.setVanished(id, "Staff", "a", false, 1);
        assertTrue(states.bootstrap(List.of(new NetworkVanishState(id, "Staff", "a", 1),
                new NetworkVanishState(unknown, "Other", "a", 3))), "unknown persisted state must import on a fresh proxy");
        assertTrue(states.isVanished(unknown), "unknown-player import is the positive control");
        assertFalse(states.isVanished(id), "a stale backend bootstrap must not overwrite a known visible decision");
    }

    @Test
    void delayedClientSequencesCannotUndoNewerDecisions() {
        event(source, VppMessage.VANISH_EVENT, decision("a-session", 2, true));
        assertTrue(states.isVanished(id));
        event(source, VppMessage.VANISH_EVENT, decision("a-session", 1, false));
        assertTrue(states.isVanished(id), "older client sequence must not overwrite a newer vanish");
        event(source, VppMessage.VANISH_EVENT, decision("a-session", 3, false));
        assertFalse(states.isVanished(id), "newer client sequence must still apply");
        event(source, VppMessage.VANISH_EVENT, decision("a-session", 2, true));
        assertFalse(states.isVanished(id), "duplicate old vanish must not overwrite a newer unvanish");
    }

    @Test
    void transferWaitsForAcceptedSourceDecisionAndRejectsLateSourceUpdates() throws Exception {
        AtomicBoolean resumed = new AtomicBoolean();
        var transfer = beginTransfer(resumed);
        assertFalse(resumed.get(), "transfer must wait while the source's accepted toggle is still queued");
        var prepare = sent.stream().filter(packet -> packet.type().name().equals("TRANSFER_PREPARE")).findFirst();
        assertTrue(prepare.isPresent(), "source must receive a transfer barrier request while its carrier is attached");
        JsonObject ready = decision("a-session", 1, true);
        ready.addProperty("requestId", JsonParser.parseString(prepare.orElseThrow().jsonPayload())
                .getAsJsonObject().get("requestId").getAsString());
        event(source, VppMessage.valueOf("TRANSFER_READY"), ready);
        assertTrue(resumed.get(), "settled source must release the transfer");
        assertTrue(states.isVanished(id), "source's accepted vanish must be authoritative before the destination can query");
        current = destination;
        event(destination, VppMessage.VANISH_EVENT, decision("b-session", 1, false));
        assertFalse(states.isVanished(id), "destination may accept a newer decision");
        event(source, VppMessage.VANISH_EVENT, decision("a-session", 2, true));
        assertFalse(states.isVanished(id), "late old-server updates must not overwrite the destination's decision");
    }

    @Test
    void unconfirmedTransferIsDeniedOnTimeout() throws Exception {
        AtomicBoolean resumed = new AtomicBoolean();
        var transfer = beginTransfer(resumed);
        assertFalse(resumed.get(), "unconfirmed transfer must remain paused");
        List.copyOf(timeouts).forEach(Runnable::run);
        assertTrue(resumed.get(), "timeout must finish the waiting event");
        assertFalse(transfer.getResult().isAllowed(), "timeout must deny the switch instead of revealing stale state at the destination");
        assertTrue(sent.stream().anyMatch(packet -> packet.type().name().equals("TRANSFER_CANCEL")), "timeout must release the source hold");
    }

    @Test
    void transferCompletionRequiresTheMatchingSourceAndRequest() throws Exception {
        AtomicBoolean resumed = new AtomicBoolean();
        beginTransfer(resumed);
        assertFalse(resumed.get(), "transfer must wait for an authenticated completion");
        JsonObject ready = decision("a-session", 1, true);
        ready.addProperty("requestId", "wrong-request");
        event(source, VppMessage.valueOf("TRANSFER_READY"), ready);
        assertFalse(resumed.get(), "wrong request must not release the transfer");
        var prepare = sent.stream().filter(packet -> packet.type().name().equals("TRANSFER_PREPARE")).findFirst().orElseThrow();
        ready.addProperty("requestId", JsonParser.parseString(prepare.jsonPayload()).getAsJsonObject().get("requestId").getAsString());
        event(destination, VppMessage.valueOf("TRANSFER_READY"), ready);
        assertFalse(resumed.get(), "destination must not impersonate the source");
        assertFalse(states.isVanished(id), "rejected completions must not mutate proxy state");
        event(source, VppMessage.valueOf("TRANSFER_READY"), ready);
        assertTrue(resumed.get(), "matching source completion is the positive control");
        assertTrue(states.isVanished(id));
    }

    private ServerPreConnectEvent beginTransfer(AtomicBoolean resumed) throws Exception {
        return beginTransfer(new ServerPreConnectEvent(player, destination.getServer()), resumed);
    }

    private ServerPreConnectEvent beginTransfer(ServerPreConnectEvent transfer, AtomicBoolean resumed) throws Exception {
        var listener = new VelocityPlayerListener(plugin, states, dispatcher);
        Continuation continuation = stub(Continuation.class, (method, args) -> { resumed.set(true); return null; });
        boolean found = false;
        for (Method method : listener.getClass().getMethods()) {
            if (method.isAnnotationPresent(Subscribe.class) && Arrays.equals(method.getParameterTypes(),
                    new Class<?>[]{ServerPreConnectEvent.class, Continuation.class})) {
                method.invoke(listener, transfer, continuation); found = true;
            }
        }
        if (!found) resumed.set(true);
        return transfer;
    }

    @Test
    void lostSourceConnectionCannotReleaseAnUnconfirmedFallbackTransfer() throws Exception {
        var transfer = new ServerPreConnectEvent(player, destination.getServer(), source.getServer());
        current = null; // A backend kick can clear currentServer before the fallback event.
        AtomicBoolean resumed = new AtomicBoolean();
        beginTransfer(transfer, resumed);
        assertTrue(resumed.get(), "a lost source must finish the event without waiting for an impossible reply");
        assertFalse(transfer.getResult().isAllowed(), "lost source must deny an unconfirmed fallback instead of exposing stale state");
    }

    @Test
    void acceptedTransferReleasesSourceWhenALaterListenerDeniesIt() throws Exception {
        AtomicBoolean resumed = new AtomicBoolean();
        var transfer = beginTransfer(resumed);
        event(source, VppMessage.valueOf("TRANSFER_READY"), readyDecision());
        assertTrue(resumed.get(), "READY must resume remaining pre-connect listeners");
        transfer.setResult(ServerPreConnectEvent.ServerResult.denied());
        List.copyOf(timeouts).forEach(Runnable::run);
        assertTrue(sent.stream().anyMatch(packet -> packet.type().name().equals("TRANSFER_CANCEL")),
                "a downstream denied transfer must release its source fence with authenticated cancellation");
    }

    @Test
    void failedDestinationConnectionReleasesTheAcceptedSourceFence() throws Exception {
        AtomicBoolean resumed = new AtomicBoolean();
        beginTransfer(resumed);
        event(source, VppMessage.valueOf("TRANSFER_READY"), readyDecision());
        assertTrue(resumed.get());
        var failed = new com.velocitypowered.api.event.player.KickedFromServerEvent(player, destination.getServer(), null, true,
                com.velocitypowered.api.event.player.KickedFromServerEvent.Notify.create(net.kyori.adventure.text.Component.text("failed")));
        var listener = new VelocityPlayerListener(plugin, states, dispatcher);
        for (Method method : listener.getClass().getMethods()) {
            if (method.isAnnotationPresent(Subscribe.class) && Arrays.equals(method.getParameterTypes(), new Class<?>[]{failed.getClass()}))
                method.invoke(listener, failed);
        }
        assertTrue(sent.stream().anyMatch(packet -> packet.type().name().equals("TRANSFER_CANCEL")),
                "failed destination connection must cancel the accepted source fence");
    }

    @Test
    void denialTimerCanRunWhileTransferPersistenceIsBlocked() throws Exception {
        AtomicBoolean resumed = new AtomicBoolean();
        var transfer = beginTransfer(resumed);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var worker = java.util.concurrent.CompletableFuture.runAsync(() -> states.getTransfers().complete(source, readyDecision(), () -> {
            entered.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return true;
        }));
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "blocked persistence stimulus must start");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> List.copyOf(timeouts).forEach(Runnable::run),
                    "denial timer must not wait on the persistence monitor");
            assertTrue(resumed.get(), "blocked persistence must not prevent denial from completing the event");
            assertFalse(transfer.getResult().isAllowed());
        } finally { release.countDown(); worker.get(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    private JsonObject readyDecision() {
        var prepare = sent.stream().filter(packet -> packet.type().name().equals("TRANSFER_PREPARE")).reduce((first, second) -> second).orElseThrow();
        JsonObject ready = decision("a-session", 1, true);
        ready.addProperty("requestId", JsonParser.parseString(prepare.jsonPayload()).getAsJsonObject().get("requestId").getAsString());
        return ready;
    }

    private JsonObject decision(String session, long sequence, boolean hidden) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", id.toString()); json.addProperty("playerName", "Staff");
        json.addProperty("vanished", hidden); json.addProperty("clientSession", session);
        json.addProperty("sequence", sequence); return json;
    }

    private void event(ServerConnection from, VppMessage type, JsonObject json) {
        channel.onPluginMessage(new PluginMessageEvent(from, player,
                MinecraftChannelIdentifier.from(VppChannel.CHANNEL), VppPacket.encode(type, json.toString())));
    }

    private RegisteredServer server(String name) {
        return stub(RegisteredServer.class, (method, args) -> switch (method.getName()) {
            case "getServerInfo" -> new ServerInfo(name, new InetSocketAddress("localhost", 25565));
            case "getPlayersConnected" -> List.of();
            case "sendPluginMessage" -> { sent.add(VppPacket.decode((byte[]) args[1])); yield true; }
            default -> null;
        });
    }

    private ServerConnection connection(RegisteredServer server) {
        return stub(ServerConnection.class, (method, args) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getServerInfo" -> server.getServerInfo();
            case "getPlayer" -> player;
            case "sendPluginMessage" -> { sent.add(VppPacket.decode((byte[]) args[1])); yield true; }
            default -> null;
        });
    }

    private Scheduler.TaskBuilder taskBuilder(Runnable task) {
        return (Scheduler.TaskBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Scheduler.TaskBuilder.class}, (self, method, args) -> {
                    if (method.getName().equals("schedule")) { timeouts.add(task); return stub(ScheduledTask.class, (m, a) -> null); }
                    return self;
                });
    }

    private <T> T stub(Class<T> type, BiFunction<Method, Object[], Object> behavior) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName();
            return behavior.apply(method, args);
        }));
    }
}
