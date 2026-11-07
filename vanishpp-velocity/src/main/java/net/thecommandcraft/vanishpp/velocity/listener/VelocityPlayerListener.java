package net.thecommandcraft.vanishpp.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import net.thecommandcraft.vanishpp.common.visibility.AudiencePolicy;
import net.thecommandcraft.vanishpp.velocity.ProxyStateManager;
import net.thecommandcraft.vanishpp.velocity.VanishppVelocity;
import net.thecommandcraft.vanishpp.velocity.messaging.PaperChannelDispatcher;

/**
 * Listens to Velocity player/server events.
 */
public class VelocityPlayerListener {

    private final VanishppVelocity plugin;
    private final ProxyStateManager stateManager;
    private final PaperChannelDispatcher dispatcher;

    public VelocityPlayerListener(VanishppVelocity plugin, ProxyStateManager stateManager,
                                  PaperChannelDispatcher dispatcher) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.dispatcher = dispatcher;
    }

    @Subscribe(order = com.velocitypowered.api.event.PostOrder.LAST)
    public void onServerPreConnect(com.velocitypowered.api.event.player.ServerPreConnectEvent event,
                                   com.velocitypowered.api.event.Continuation continuation) {
        stateManager.getTransfers().begin(event, continuation);
    }

    /** When a player switches servers, update their tracked server name and flush any queued messages. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        stateManager.getTransfers().connected(event.getPlayer().getUniqueId());
        String serverName = event.getServer().getServerInfo().getName();

        // Update vanish state server name if this player is vanished
        stateManager.updateServer(event.getPlayer().getUniqueId(), serverName);

        // Flush queued messages for this server now that it has a player
        dispatcher.flushQueue(serverName);
    }

    /** After the player is fully connected, flush again (post-connect is safer for message delivery). */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(conn -> {
            dispatcher.sendPong(conn.getServer(), plugin.getProxy().getVersion().getVersion(), stateManager.getSession());
            dispatcher.flushQueue(conn.getServerInfo().getName());
            dispatcher.sendStateResponse(conn.getServer(), stateManager);
        });
    }

    @Subscribe
    public void onServerKick(com.velocitypowered.api.event.player.KickedFromServerEvent event) {
        if (event.kickedDuringServerConnect())
            stateManager.getTransfers().failed(event.getPlayer().getUniqueId(), event.getServer());
    }

    @Subscribe
    public void onDisconnect(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        stateManager.getTransfers().connected(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (!plugin.getConfigManager().getSnapshot().adjustServerListCount) return;
        var players = event.getPing().getPlayers();
        if (players.isEmpty()) return;
        var sample = players.get().getSample().stream().filter(player -> !stateManager.isVanished(player.getId()))
                .toArray(com.velocitypowered.api.proxy.server.ServerPing.SamplePlayer[]::new);
        int count = AudiencePolicy.publicCount(plugin.getProxy().getAllPlayers(), player -> stateManager.isVanished(player.getUniqueId()));
        event.setPing(event.getPing().asBuilder().onlinePlayers(count).clearSamplePlayers().samplePlayers(sample).build());
    }
}
