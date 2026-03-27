package net.thecommandcraft.vanishpp.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Vanishpp Velocity — companion proxy plugin for Vanishpp cross-server sync.
 *
 * <h3>What this does</h3>
 * <ul>
 *   <li>Maintains an authoritative in-proxy set of currently vanished player UUIDs.</li>
 *   <li>Relays {@code VANISH} / {@code UNVANISH} messages from any backend to every other backend
 *       on the network instantly.</li>
 *   <li>On {@code ServerPreConnectEvent} (fires before the player's join lands on the new backend):
 *       pushes the switching player's vanish state to the target backend so
 *       {@code PlayerJoinEvent} already sees the correct state — eliminating the join-message
 *       race condition.</li>
 *   <li>On {@code ServerConnectedEvent}: pushes the full network-wide vanish state ({@code STATE:…})
 *       to the newly connected backend so any late-arriving server is immediately in sync.</li>
 *   <li>On {@code DisconnectEvent} (real proxy logout): removes the player from the proxy set.
 *       Backends keep their in-memory state so a fast reconnect to any server restores vanish.</li>
 * </ul>
 *
 * <h3>Race-condition guarantees</h3>
 * <ul>
 *   <li><b>Server switch while vanished</b>: {@code ServerPreConnectEvent} sends {@code VANISH}
 *       to the target backend before the player's login sequence begins. By the time the backend
 *       fires {@code PlayerJoinEvent}, the UUID is already in its {@code vanishedPlayers} set.</li>
 *   <li><b>Concurrent vanish toggles</b>: all state mutations go through the single proxy set
 *       ({@link ConcurrentHashMap}-backed) then broadcast sequentially. No lock inversion possible.</li>
 *   <li><b>New/restarted backend</b>: {@code ServerConnectedEvent} fires for every connecting
 *       player and re-sends the full {@code STATE} — a restarted backend catches up on first join.</li>
 * </ul>
 */
@Plugin(
        id = "vanishpp-velocity",
        name = "Vanishpp Velocity",
        version = "1.1.4",
        description = "Velocity companion for Vanishpp — cross-server vanish sync",
        authors = {"tcc"}
)
public class VanishppVelocity {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("vanishpp:sync");

    /** In-proxy authoritative set of all currently vanished players across the network. */
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public VanishppVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        logger.info("Vanishpp Velocity loaded. Channel vanishpp:sync registered.");
    }

    // -----------------------------------------------------------------------
    // Plugin message relay — backend A → proxy → all other backends
    // -----------------------------------------------------------------------

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;

        // Only process messages sent by a backend server (not another proxy plugin)
        if (!(event.getSource() instanceof ServerConnection source)) return;

        // Mark as handled so Velocity doesn't auto-forward it to the receiving player
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String msg = new String(event.getData(), StandardCharsets.UTF_8);

        if (msg.startsWith("VANISH:")) {
            UUID uuid = parseUUID(msg, 7);
            if (uuid == null) return;
            boolean added = vanishedPlayers.add(uuid);
            if (added) logger.fine("Network vanish: " + uuid);
            // Relay to every backend EXCEPT the sender
            broadcastToOthers(source, msg);

        } else if (msg.startsWith("UNVANISH:")) {
            UUID uuid = parseUUID(msg, 9);
            if (uuid == null) return;
            boolean removed = vanishedPlayers.remove(uuid);
            if (removed) logger.fine("Network unvanish: " + uuid);
            broadcastToOthers(source, msg);
        }
    }

    // -----------------------------------------------------------------------
    // Server switch — pre-seed the target backend before the player joins
    // -----------------------------------------------------------------------

    /**
     * Fires BEFORE the player's connection to the new backend is established.
     * Sending the {@code VANISH} message here means it arrives at the target backend
     * before (or at the same time as) the player's login sequence, so
     * {@code PlayerJoinEvent} on that backend already sees the vanished state.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!event.getResult().isAllowed()) return;

        event.getResult().getServer().ifPresent(targetServer -> {
            // Pre-seed the switching player's own vanish state to the target backend
            if (vanishedPlayers.contains(uuid)) {
                sendTo(targetServer, "VANISH:" + uuid);
            }
        });
    }

    /**
     * Fires after the player is fully connected to the new backend.
     * Sends the complete network-wide vanish state so the backend has everything,
     * including players vanished on OTHER servers that the player never visited.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (vanishedPlayers.isEmpty()) return;

        String state = "STATE:" + vanishedPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(","));

        sendTo(event.getServer(), state);
    }

    // -----------------------------------------------------------------------
    // Real proxy disconnects — keep proxy set tidy
    // -----------------------------------------------------------------------

    /**
     * Only fires on a real proxy logout (not on server switches).
     * Remove from proxy tracking; backends keep their in-memory state so a
     * fast reconnect to any server still restores the vanish correctly.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        vanishedPlayers.remove(event.getPlayer().getUniqueId());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void broadcastToOthers(ServerConnection exclude, String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        proxy.getAllServers().forEach(server -> {
            if (!server.getServerInfo().getName()
                    .equals(exclude.getServerInfo().getName())) {
                server.sendPluginMessage(CHANNEL, data);
            }
        });
    }

    private void sendTo(com.velocitypowered.api.proxy.server.RegisteredServer server, String message) {
        server.sendPluginMessage(CHANNEL, message.getBytes(StandardCharsets.UTF_8));
    }

    private UUID parseUUID(String msg, int offset) {
        try {
            return UUID.fromString(msg.substring(offset));
        } catch (IllegalArgumentException e) {
            logger.warning("Malformed UUID in vanishpp:sync message: '" + msg + "'");
            return null;
        }
    }
}
