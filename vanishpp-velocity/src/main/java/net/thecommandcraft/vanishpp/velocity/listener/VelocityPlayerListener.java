package net.thecommandcraft.vanishpp.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
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

    /** When a player switches servers, update their tracked server name and flush any queued messages. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String serverName = event.getServer().getServerInfo().getName();

        // Update vanish state server name if this player is vanished
        stateManager.updateServer(event.getPlayer().getUniqueId(), serverName);

        // Flush queued messages for this server now that it has a player
        dispatcher.flushQueue(serverName);
    }

    /** After the player is fully connected, flush again (post-connect is safer for message delivery). */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(conn ->
                dispatcher.flushQueue(conn.getServerInfo().getName()));
    }
}
