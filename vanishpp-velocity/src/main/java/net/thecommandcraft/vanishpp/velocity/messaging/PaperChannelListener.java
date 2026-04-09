package net.thecommandcraft.vanishpp.velocity.messaging;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot;
import net.thecommandcraft.vanishpp.common.protocol.VppChannel;
import net.thecommandcraft.vanishpp.common.protocol.VppMessage;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket.DecodedPacket;
import net.thecommandcraft.vanishpp.velocity.ProxyStateManager;
import net.thecommandcraft.vanishpp.velocity.VanishppVelocity;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all incoming plugin messages from Paper servers.
 */
public class PaperChannelListener {

    private final VanishppVelocity plugin;
    private final ProxyStateManager stateManager;
    private final PaperChannelDispatcher dispatcher;
    private final VelocityConfigManager configManager;

    public PaperChannelListener(VanishppVelocity plugin, ProxyStateManager stateManager,
                                PaperChannelDispatcher dispatcher, VelocityConfigManager configManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;
        this.dispatcher = dispatcher;
        this.configManager = configManager;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(VppChannel.CHANNEL)) return;
        if (!(event.getSource() instanceof ServerConnection serverConn)) return;

        // Mark handled so Velocity does not forward it to players
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        DecodedPacket packet;
        try {
            packet = VppPacket.decode(event.getData());
        } catch (Exception e) {
            plugin.getLogger().warn("Received malformed VppPacket from {}: {}", serverConn.getServerInfo().getName(), e.getMessage());
            return;
        }

        String serverName = serverConn.getServerInfo().getName();
        RegisteredServer server = serverConn.getServer();
        JsonObject json = JsonParser.parseString(packet.jsonPayload()).getAsJsonObject();

        switch (packet.type()) {
            case HELLO              -> handleHello(server, serverName, json);
            case VANISH_EVENT       -> handleVanishEvent(serverName, json);
            case STATE_QUERY        -> handleStateQuery(server);
            case CONFIG_REQUEST     -> handleConfigRequest(server, serverName);
            case RELOAD_REQUEST     -> handleReloadRequest(json);
            case PLAYER_LIST_QUERY  -> handlePlayerListQuery(server, json);
            case CONFIG_SYNC        -> handleConfigSync(serverName, json);
            default -> plugin.getLogger().debug("Unhandled message type {} from {}", packet.type(), serverName);
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    /** Paper says hello — respond with PONG + immediate config push + full state. */
    private void handleHello(RegisteredServer server, String serverName, JsonObject json) {
        String version = json.has("pluginVersion") ? json.get("pluginVersion").getAsString() : "?";
        plugin.getLogger().info("VanishPP Paper plugin connected: {} (plugin v{})", serverName, version);

        // PONG
        dispatcher.sendPong(server, plugin.getProxy().getVersion().getVersion());

        // Config push with server-specific overrides applied
        ProxyConfigSnapshot snapshot = configManager.getSnapshot().applyOverride(serverName);
        dispatcher.pushConfigTo(server, snapshot);

        // Full state sync
        dispatcher.sendStateResponse(server, stateManager.getAllVanishedPlayers());

        // If a proxy update is already known, push it immediately to the newly connected server
        plugin.getUpdateChecker().notifyServer(server);
    }

    /** A player vanished or unvanished on a Paper server — update state and broadcast to all other servers. */
    private void handleVanishEvent(String serverName, JsonObject json) {
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());
        String playerName = json.has("playerName") ? json.get("playerName").getAsString() : "";
        boolean vanished  = json.get("vanished").getAsBoolean();
        int     level     = json.has("vanishLevel") ? json.get("vanishLevel").getAsInt() : 1;

        stateManager.setVanished(uuid, playerName, serverName, vanished, level);

        // Broadcast to all other connected Paper servers
        dispatcher.broadcastVanishSync(uuid, vanished, serverName);

        plugin.getLogger().debug("VanishEvent: {} {} on {}", playerName, vanished ? "vanished" : "unvanished", serverName);
    }

    /** Paper wants the full vanished player set. */
    private void handleStateQuery(RegisteredServer server) {
        dispatcher.sendStateResponse(server, stateManager.getAllVanishedPlayers());
    }

    /** Paper explicitly requests an up-to-date config push. */
    private void handleConfigRequest(RegisteredServer server, String serverName) {
        ProxyConfigSnapshot snapshot = configManager.getSnapshot().applyOverride(serverName);
        dispatcher.pushConfigTo(server, snapshot);
    }

    /** /vanishreload was run on a Paper server — reload our config and push to all. */
    private void handleReloadRequest(JsonObject json) {
        String requestedBy = json.has("requestedBy") ? json.get("requestedBy").getAsString() : "unknown";
        plugin.getLogger().info("Config reload requested by {} — reloading and pushing to all servers.", requestedBy);

        configManager.reload();

        // Build per-server snapshots and push to all
        Map<String, ProxyConfigSnapshot> perServer = new HashMap<>();
        for (var server : plugin.getProxy().getAllServers()) {
            String name = server.getServerInfo().getName();
            perServer.put(name, configManager.getSnapshot().applyOverride(name));
        }
        dispatcher.pushConfigToAll(plugin.getProxy(), perServer);
    }

    /**
     * Paper sends a CONFIG_SYNC — apply key-value pairs in-memory and push updated
     * config to all servers so the change propagates network-wide.
     */
    private void handleConfigSync(String fromServer, JsonObject json) {
        if (!json.has("entries")) return;
        JsonObject entries = json.getAsJsonObject("entries");
        Map<String, String> patch = new HashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : entries.entrySet()) {
            patch.put(e.getKey(), e.getValue().getAsString());
        }
        configManager.applyRuntimePatch(patch);
        plugin.getLogger().info("CONFIG_SYNC from {} — {} key(s) applied, pushing to all servers.", fromServer, patch.size());

        // Push updated snapshot to every connected server
        Map<String, ProxyConfigSnapshot> perServer = new HashMap<>();
        for (var server : plugin.getProxy().getAllServers()) {
            String name = server.getServerInfo().getName();
            perServer.put(name, configManager.getSnapshot().applyOverride(name));
        }
        dispatcher.pushConfigToAll(plugin.getProxy(), perServer);
    }

    /** Paper wants the cross-server vanished player list. */
    private void handlePlayerListQuery(RegisteredServer server, JsonObject json) {
        String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
        dispatcher.sendPlayerListResponse(server, requestId, stateManager.getAllVanishedPlayers());
    }
}
