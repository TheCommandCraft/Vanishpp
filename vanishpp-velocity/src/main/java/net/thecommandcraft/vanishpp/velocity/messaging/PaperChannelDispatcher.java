package net.thecommandcraft.vanishpp.velocity.messaging;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot;
import net.thecommandcraft.vanishpp.common.protocol.VppChannel;
import net.thecommandcraft.vanishpp.common.protocol.VppMessage;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket;
import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sends plugin messages from the Velocity proxy to Paper servers.
 *
 * <p>Plugin messaging requires an online player as the channel carrier.
 * Messages destined for servers with no online players are queued and
 * flushed the next time a player connects to that server.</p>
 */
public class PaperChannelDispatcher {

    private final ProxyServer proxy;
    private final MinecraftChannelIdentifier channel;

    /** Pending messages for servers that have no online players right now. */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<byte[]>> pendingQueue = new ConcurrentHashMap<>();

    public PaperChannelDispatcher(ProxyServer proxy) {
        this.proxy = proxy;
        this.channel = MinecraftChannelIdentifier.from(VppChannel.CHANNEL);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Sends a CONFIG_PUSH to a specific server with overrides already applied for that server. */
    public void pushConfigTo(RegisteredServer server, ProxyConfigSnapshot snapshot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("config", snapshot.toJson());
        send(server, VppMessage.CONFIG_PUSH, payload.toString());
    }

    /** Pushes the config to all currently registered servers. */
    public void pushConfigToAll(ProxyServer proxy, Map<String, ProxyConfigSnapshot> perServerSnapshots) {
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            ProxyConfigSnapshot snapshot = perServerSnapshots.getOrDefault(name,
                    perServerSnapshots.getOrDefault("__default__", new ProxyConfigSnapshot()));
            pushConfigTo(server, snapshot);
        }
    }

    /**
     * Broadcasts a vanish state change to all servers EXCEPT the origin.
     *
     * @param uuid         the affected player's UUID
     * @param vanished     true = vanished, false = unvanished
     * @param originServer the server that sent the original VANISH_EVENT (do not echo back)
     * @param playerName   display name of the affected player (for cross-server broadcast messages)
     */
    public void broadcastVanishSync(UUID uuid, boolean vanished, String originServer, String playerName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("vanished", vanished);
        payload.addProperty("serverName", originServer);
        payload.addProperty("playerName", playerName);
        byte[] data = VppPacket.encode(VppMessage.VANISH_SYNC, payload.toString());

        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.getServerInfo().getName().equals(originServer)) continue;
            sendRaw(server, data);
        }
    }

    /** Sends the full vanished player set to a specific server (response to STATE_QUERY). */
    public void sendStateResponse(RegisteredServer server, Set<NetworkVanishState> allVanished) {
        JsonObject payload = new JsonObject();
        payload.add("vanished", serializeStates(allVanished));
        send(server, VppMessage.STATE_RESPONSE, payload.toString());
    }

    /** Sends a PLAYER_LIST_RESPONSE to a specific server. */
    public void sendPlayerListResponse(RegisteredServer server, String requestId, Set<NetworkVanishState> players) {
        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", requestId);
        payload.add("players", serializeStates(players));
        send(server, VppMessage.PLAYER_LIST_RESPONSE, payload.toString());
    }

    /**
     * Sends a PROXY_UPDATE_NOTIFY to a server so its staff are informed that the
     * Velocity proxy plugin needs updating.
     */
    public void sendProxyUpdateNotify(RegisteredServer server, String currentVersion,
                                      String latestVersion, String downloadUrl) {
        JsonObject payload = new JsonObject();
        payload.addProperty("currentVersion", currentVersion);
        payload.addProperty("latestVersion", latestVersion);
        payload.addProperty("downloadUrl", downloadUrl);
        send(server, VppMessage.PROXY_UPDATE_NOTIFY, payload.toString());
    }

    /** Sends a PONG to a server to confirm this is a VanishPP proxy. */
    public void sendPong(RegisteredServer server, String proxyVersion) {
        JsonObject payload = new JsonObject();
        payload.addProperty("proxyVersion", proxyVersion);
        send(server, VppMessage.PONG, payload.toString());
    }

    /**
     * Flushes any queued messages for a server that now has a player online.
     * Call this on ServerConnectedEvent.
     */
    public void flushQueue(String serverName) {
        ConcurrentLinkedDeque<byte[]> queue = pendingQueue.remove(serverName);
        if (queue == null || queue.isEmpty()) return;

        proxy.getServer(serverName).ifPresent(server -> {
            byte[] msg;
            while ((msg = queue.poll()) != null) {
                sendRaw(server, msg);
            }
        });
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void send(RegisteredServer server, VppMessage type, String json) {
        sendRaw(server, VppPacket.encode(type, json));
    }

    private void sendRaw(RegisteredServer server, byte[] data) {
        Optional<Player> carrier = server.getPlayersConnected().stream().findFirst();
        if (carrier.isPresent()) {
            carrier.get().getCurrentServer().ifPresent(conn -> conn.sendPluginMessage(channel, data));
        } else {
            // Queue for later delivery
            pendingQueue
                    .computeIfAbsent(server.getServerInfo().getName(), k -> new ConcurrentLinkedDeque<>())
                    .add(data);
        }
    }

    private JsonArray serializeStates(Set<NetworkVanishState> states) {
        JsonArray arr = new JsonArray();
        for (NetworkVanishState s : states) {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", s.uuid().toString());
            obj.addProperty("playerName", s.playerName());
            obj.addProperty("serverName", s.serverName());
            obj.addProperty("vanishLevel", s.vanishLevel());
            arr.add(obj);
        }
        return arr;
    }
}
