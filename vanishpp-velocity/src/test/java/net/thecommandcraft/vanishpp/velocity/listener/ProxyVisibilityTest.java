package net.thecommandcraft.vanishpp.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.velocity.*;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProxyVisibilityTest {
    @TempDir Path directory;

    @Test
    void proxyPingExcludesOnlineVanishedCountAndSamples() throws Exception {
        UUID hidden = UUID.randomUUID(), visible = UUID.randomUUID(), offline = UUID.randomUUID();
        List<Player> online = List.of(player(hidden), player(visible));
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProxyServer.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getAllPlayers" -> online;
                    case "getPlayerCount" -> online.size();
                    case "getPlayer" -> online.stream().filter(p -> p.getUniqueId().equals(args[0])).findFirst();
                    default -> null;
                });
        var logger = LoggerFactory.getLogger(getClass());
        VanishppVelocity plugin = new VanishppVelocity(proxy, logger, directory);
        VelocityConfigManager config = new VelocityConfigManager(directory, logger);
        config.load();
        var field = VanishppVelocity.class.getDeclaredField("configManager");
        field.setAccessible(true);
        field.set(plugin, config);
        ProxyStateManager states = new ProxyStateManager(plugin, config);
        states.setVanished(hidden, "Hidden", "server", true, 10);
        states.setVanished(offline, "Offline", "server", true, 10);
        VelocityPlayerListener listener = new VelocityPlayerListener(plugin, states, null);
        ServerPing ping = ServerPing.builder().version(new ServerPing.Version(767, "test"))
                .description(Component.text("test")).onlinePlayers(2).maximumPlayers(20)
                .samplePlayers(new ServerPing.SamplePlayer("Hidden", hidden), new ServerPing.SamplePlayer("Visible", visible)).build();
        InboundConnection connection = (InboundConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{InboundConnection.class}, (self, method, args) -> null);
        ProxyPingEvent event = new ProxyPingEvent(connection, ping);
        for (var method : listener.getClass().getMethods()) {
            if (method.isAnnotationPresent(Subscribe.class) && Arrays.equals(method.getParameterTypes(), new Class<?>[]{ProxyPingEvent.class}))
                method.invoke(listener, event);
        }
        assertEquals(1, event.getPing().getPlayers().orElseThrow().getOnline(),
                "proxy ping must exclude online vanished players without subtracting offline state");
        assertEquals(List.of(visible), event.getPing().getPlayers().orElseThrow().getSample().stream()
                .map(ServerPing.SamplePlayer::getId).toList(), "proxy samples must exclude hidden identities");
    }

    private Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (self, method, args) -> method.getName().equals("getUniqueId") ? id : null);
    }
}
