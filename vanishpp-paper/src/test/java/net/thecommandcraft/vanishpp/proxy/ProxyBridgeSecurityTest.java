package net.thecommandcraft.vanishpp.proxy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.protocol.VppChannel;
import net.thecommandcraft.vanishpp.common.protocol.VppMessage;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProxyBridgeSecurityTest {

    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock carrier;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        carrier = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void controlMessages_requireEnabledBridgeAndCompletedHandshake() {
        ProxyBridge standalone = plugin.getProxyBridge();
        boolean standaloneRegistered = server.getMessenger().isIncomingChannelRegistered(plugin, VppChannel.CHANNEL)
                || server.getMessenger().isOutgoingChannelRegistered(plugin, VppChannel.CHANNEL);
        receive(standalone, VppMessage.PONG, "{}");
        boolean standaloneDetected = standalone.isProxyDetected();
        standalone.shutdown();

        plugin.getConfigManager().getConfig().set("proxy.enabled", true);
        ProxyBridge bridge = new ProxyBridge(plugin);
        bridge.init();
        assertTrue(server.getMessenger().isIncomingChannelRegistered(plugin, VppChannel.CHANNEL));
        receive(bridge, VppMessage.PONG, "{}");
        boolean unsolicitedPong = bridge.isProxyDetected();
        UUID staff = UUID.randomUUID();
        plugin.handleNetworkVanishSync(staff, true);
        receive(bridge, VppMessage.VANISH_SYNC, "{\"uuid\":\"" + staff + "\",\"vanished\":false}");
        server.getScheduler().performOneTick();
        boolean preHandshakeSync = plugin.getRawVanishedPlayers().contains(staff);
        plugin.handleNetworkVanishSync(staff, true);
        receive(bridge, VppMessage.STATE_RESPONSE, "{\"vanished\":[]}");
        receive(bridge, VppMessage.CONFIG_PUSH, "{\"config\":\"{}\"}");
        server.getScheduler().performOneTick();
        boolean preHandshakeState = plugin.getRawVanishedPlayers().contains(staff);
        boolean preHandshakeConfig = plugin.getProxyConfigCache().isReady();

        receive(bridge, VppMessage.PONG, "{}");
        assertTrue(bridge.isProxyDetected(), "an enabled bridge must still connect");
        plugin.handleNetworkVanishSync(staff, true);
        receive(bridge, VppMessage.VANISH_SYNC, "{\"uuid\":\"" + staff + "\",\"vanished\":false}");
        server.getScheduler().performOneTick();
        assertFalse(plugin.getRawVanishedPlayers().contains(staff), "connected proxy sync must still apply");
        plugin.handleNetworkVanishSync(staff, true);
        receive(bridge, VppMessage.STATE_RESPONSE, "{\"vanished\":[]}");
        receive(bridge, VppMessage.CONFIG_PUSH, "{\"config\":\"{}\"}");
        server.getScheduler().performOneTick();
        assertFalse(plugin.getRawVanishedPlayers().contains(staff), "connected proxy snapshot must still apply");
        assertTrue(plugin.getProxyConfigCache().isReady(), "connected proxy config must still apply");
        bridge.shutdown();
        plugin.handleNetworkVanishSync(staff, true);
        receive(bridge, VppMessage.PONG, "{}");
        receive(bridge, VppMessage.STATE_RESPONSE, "{\"vanished\":[]}");
        server.getScheduler().performOneTick();
        boolean shutdownPreservedState = plugin.getRawVanishedPlayers().contains(staff);

        assertAll(
                () -> assertFalse(standaloneRegistered, "standalone servers must not register the proxy channel"),
                () -> assertFalse(standaloneDetected, "standalone servers must ignore forged PONGs"),
                () -> assertFalse(unsolicitedPong, "PONG must answer an outstanding HELLO"),
                () -> assertTrue(preHandshakeSync, "VANISH_SYNC must not unvanish staff before handshake"),
                () -> assertTrue(preHandshakeState, "STATE_RESPONSE must not unvanish staff before handshake"),
                () -> assertFalse(preHandshakeConfig, "CONFIG_PUSH must not apply before handshake"),
                () -> assertFalse(bridge.isProxyDetected(), "shutdown must invalidate the proxy session"),
                () -> assertTrue(shutdownPreservedState, "shutdown must reject further control messages"));
    }

    private void receive(ProxyBridge bridge, VppMessage type, String json) {
        bridge.onPluginMessageReceived(VppChannel.CHANNEL, carrier, VppPacket.encode(type, json));
    }
}
