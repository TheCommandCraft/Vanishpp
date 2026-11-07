package net.thecommandcraft.vanishpp.velocity.messaging;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.thecommandcraft.vanishpp.common.protocol.VppChannel;
import net.thecommandcraft.vanishpp.common.protocol.VppMessage;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class PaperChannelListenerTest {

    @Test
    void proxyChannel_consumesClientMessagesBeforeCheckingTheirSource() {
        Player player = stub(Player.class);
        ServerConnection backend = (ServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServerConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> new ServerInfo("test", new InetSocketAddress("localhost", 25565));
                    case "getServer" -> stub(RegisteredServer.class);
                    default -> null;
                });
        PaperChannelListener listener = new PaperChannelListener(null, null, null, null);
        PluginMessageEvent client = new PluginMessageEvent(player, backend,
                MinecraftChannelIdentifier.from(VppChannel.CHANNEL),
                VppPacket.encode(VppMessage.STATE_RESPONSE, "{\"vanished\":[]}"));
        listener.onPluginMessage(client);

        PluginMessageEvent unrelated = new PluginMessageEvent(player, backend,
                MinecraftChannelIdentifier.from("other:channel"), new byte[0]);
        listener.onPluginMessage(unrelated);
        assertTrue(unrelated.getResult().isAllowed(), "unrelated channels must still forward");

        // An incomplete player message is ignored, but still exercises backend decoding and dispatch.
        PluginMessageEvent server = new PluginMessageEvent(backend, player,
                MinecraftChannelIdentifier.from(VppChannel.CHANNEL), VppPacket.encode(VppMessage.PLAYER_MESSAGE, "{}"));
        listener.onPluginMessage(server);
        assertFalse(server.getResult().isAllowed(), "backend messages must still be consumed");
        assertFalse(client.getResult().isAllowed(), "client proxy-channel messages must never reach Paper");
    }

    private <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> null));
    }
}
