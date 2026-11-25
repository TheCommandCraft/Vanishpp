package net.thecommandcraft.vanishpp.proxy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.protocol.VppChannel;
import net.thecommandcraft.vanishpp.common.protocol.VppMessage;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProxyBridgeLifecycleTest {

    private CarrierServer server;
    private Vanishpp plugin;
    private ProxyBridge bridge;
    private RecordingPlayer carrier;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new CarrierServer());
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        bridge = new ProxyBridge(plugin);
        bridge.init();
        carrier = new RecordingPlayer(server);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void handshakeTimeout_closesChannelsAndRejectsLateMessages() {
        server.addPlayer(carrier);
        server.getScheduler().performOneTick();
        assertEquals(VppMessage.HELLO, carrier.packets.getFirst().type());
        server.getScheduler().performTicks(100);
        boolean incomingClosed = !server.getMessenger().isIncomingChannelRegistered(plugin, VppChannel.CHANNEL);
        boolean outgoingClosed = !server.getMessenger().isOutgoingChannelRegistered(plugin, VppChannel.CHANNEL);

        UUID staff = UUID.randomUUID();
        plugin.handleNetworkVanishSync(staff, true);
        receive(VppMessage.PONG, "{}");
        receive(VppMessage.STATE_RESPONSE, "{\"vanished\":[],\"visible\":[\"" + staff + "\"]}");
        receive(VppMessage.CONFIG_PUSH, "{\"config\":\"{}\"}");
        server.getScheduler().performOneTick();
        boolean latePongRejected = !bridge.isProxyDetected();
        boolean statePreserved = plugin.getRawVanishedPlayers().contains(staff);
        boolean configIgnored = !plugin.getProxyConfigCache().isReady();

        bridge.shutdown();
        bridge.init();
        server.getScheduler().performOneTick();
        receive(VppMessage.PONG, "{}");
        server.getScheduler().performTicks(100);
        assertTrue(bridge.isProxyDetected(), "a completed handshake must survive the timeout");
        assertTrue(server.getMessenger().isIncomingChannelRegistered(plugin, VppChannel.CHANNEL));
        assertTrue(server.getMessenger().isOutgoingChannelRegistered(plugin, VppChannel.CHANNEL));
        assertAll(
                () -> assertTrue(incomingClosed, "timeout must unregister the incoming channel"),
                () -> assertTrue(outgoingClosed, "timeout must unregister the outgoing channel"),
                () -> assertTrue(latePongRejected, "timeout must reject a late PONG"),
                () -> assertTrue(statePreserved, "timeout must reject late state changes"),
                () -> assertTrue(configIgnored, "timeout must reject late configuration"));
    }

    @Test
    void shutdown_discardsQueuedPacketsAndRejectsNewSends() {
        bridge.sendPacket(packet("queued-control"));
        server.addPlayer(carrier);
        bridge.flushPendingPackets(carrier);
        assertEquals(List.of("queued-control"), carrier.payloads(), "an active bridge must flush queued packets");
        carrier.packets.clear();
        carrier.disconnect();

        bridge.sendPacket(packet("stale"));
        bridge.shutdown();
        bridge.sendPacket(packet("after-shutdown"));
        server.addPlayer(carrier);
        bridge.init();
        bridge.flushPendingPackets(carrier);
        List<String> afterRestart = carrier.payloads();
        carrier.packets.clear();

        bridge.sendPacket(packet("live-control"));
        assertEquals(List.of("live-control"), carrier.payloads(), "an active bridge must send through an online carrier");
        carrier.packets.clear();
        bridge.shutdown();
        bridge.sendPacket(packet("late-live"));
        assertAll(
                () -> assertTrue(afterRestart.isEmpty(), "shutdown must discard queued and future packets: " + afterRestart),
                () -> assertTrue(carrier.packets.isEmpty(), "shutdown must prevent fresh sends through an online carrier"));
    }

    @Test
    void shutdown_stopsDelayedHelloWhenACarrierArrives() {
        server.getScheduler().performOneTick();
        bridge.shutdown();
        server.addPlayer(carrier);
        server.getScheduler().performTicks(100);
        assertTrue(carrier.packets.isEmpty(), "shutdown must stop the delayed HELLO retry");

        bridge.init();
        server.getScheduler().performOneTick();
        assertEquals(VppMessage.HELLO, carrier.packets.getFirst().type(), "an active retry must still send HELLO");
    }

    @Test
    void shutdown_stopsFlushWhenAnInFlightSenderQueuesAfterShutdown() {
        // Model shutdown between sendPacket's active check and its carrier lookup completing.
        server.beforeCarrierLookup = bridge::shutdown;
        bridge.sendPacket(packet("in-flight"));
        bridge.flushPendingPackets(carrier);
        assertTrue(carrier.packets.isEmpty(), "an inactive bridge must not flush an in-flight packet");

        server.addPlayer(carrier);
        bridge.init();
        bridge.flushPendingPackets(carrier);
        assertEquals(List.of("in-flight"), carrier.payloads(), "the fixture must actually have queued the in-flight packet");
    }

    // Ticket: behind Velocity everyone joining an empty backend was kicked with "Proxy vanish state is unavailable".
    // Velocity registers the channel only after the backend's join, so the join query sent through the joiner
    // itself was dropped by Paper and never answered.
    @Test
    void loneJoiner_queryWaitsForChannelRegistrationAndIsAnswered() {
        server.addPlayer(carrier);
        server.getScheduler().performOneTick();
        receive(VppMessage.PONG, "{\"session\":\"net\"}");
        assertTrue(bridge.isProxyDetected(), "control: the handshake must complete");
        carrier.disconnect();

        RecordingPlayer joiner = new RecordingPlayer(server, "Joiner");
        joiner.listening = false;
        server.addPlayer(joiner);
        List<Boolean> answers = new ArrayList<>();
        bridge.requestJoinState(joiner, answers::add);
        server.getScheduler().performOneTick();
        assertTrue(joiner.packets.isEmpty(), "nothing can reach the proxy before the channel is registered");

        joiner.listening = true;
        bridge.onChannelRegistered(joiner);
        server.getScheduler().performOneTick();
        var query = joiner.packets.stream().filter(p -> p.type() == VppMessage.STATE_QUERY).findFirst();
        assertTrue(query.isPresent(), "the join query must be sent once the channel is registered: " + joiner.packets);
        String requestId = com.google.gson.JsonParser.parseString(query.get().jsonPayload()).getAsJsonObject()
                .get("requestId").getAsString();
        bridge.onPluginMessageReceived(VppChannel.CHANNEL, joiner, VppPacket.encode(VppMessage.STATE_RESPONSE,
                "{\"vanished\":[],\"visible\":[],\"session\":\"net\",\"revision\":1,\"requestId\":\"" + requestId
                        + "\",\"joiningUuid\":\"" + joiner.getUniqueId() + "\",\"joiningVanished\":false}"));
        server.getScheduler().performTicks(120);
        assertEquals(List.of(false), answers, "the join must be answered by the proxy");
        assertTrue(joiner.isOnline(), "the joiner must not be kicked");
    }

    @Test
    void handshake_waitsForAPlayerWhoseChannelIsRegistered() {
        carrier.listening = false;
        server.addPlayer(carrier);
        server.getScheduler().performTicks(5);
        assertFalse(bridge.isProxyDetected());
        carrier.listening = true;
        bridge.onChannelRegistered(carrier);
        server.getScheduler().performOneTick();
        assertEquals(VppMessage.HELLO, carrier.packets.getFirst().type(), "HELLO must go out as soon as the channel is registered");
    }

    private byte[] packet(String value) {
        return VppPacket.encode(VppMessage.STATE_QUERY, value);
    }

    private void receive(VppMessage type, String json) {
        bridge.onPluginMessageReceived(VppChannel.CHANNEL, carrier, VppPacket.encode(type, json));
    }

    private static class CarrierServer extends ServerMock {
        private Runnable beforeCarrierLookup;

        @Override
        public Collection<? extends PlayerMock> getOnlinePlayers() {
            Runnable action = beforeCarrierLookup;
            beforeCarrierLookup = null;
            if (action != null) action.run();
            return super.getOnlinePlayers();
        }
    }

    /** Like Paper: a plugin message only goes out once the player's connection has registered the channel. */
    private static class RecordingPlayer extends PlayerMock {
        private final List<VppPacket.DecodedPacket> packets = new ArrayList<>();
        private boolean listening = true;

        private RecordingPlayer(ServerMock server) {
            this(server, "Carrier");
        }

        private RecordingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public java.util.Set<String> getListeningPluginChannels() {
            return listening ? java.util.Set.of(VppChannel.CHANNEL) : java.util.Set.of();
        }

        @Override
        public void sendPluginMessage(Plugin source, String channel, byte[] message) {
            if (VppChannel.CHANNEL.equals(channel) && listening) packets.add(VppPacket.decode(message));
        }

        private List<String> payloads() {
            return packets.stream().map(VppPacket.DecodedPacket::jsonPayload).toList();
        }
    }
}
