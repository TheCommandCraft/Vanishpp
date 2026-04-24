package net.thecommandcraft.vanishpp.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.protocol.VppChannel;
import net.thecommandcraft.vanishpp.common.protocol.VppMessage;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket;
import net.thecommandcraft.vanishpp.common.protocol.VppPacket.DecodedPacket;
import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages the connection between this Paper server and the VanishPP Velocity proxy.
 *
 * <p>On enable, sends a HELLO message via the first online player.
 * If a PONG is received within 5 seconds the server is in <em>proxy mode</em>
 * and all cross-server state operations route through the proxy.
 * If no PONG arrives within 5 seconds the server stays in standalone mode
 * and existing behaviour is unchanged.</p>
 */
public class ProxyBridge implements PluginMessageListener {

    private final Vanishpp plugin;
    private volatile boolean proxyDetected = false;
    private volatile boolean handshakeComplete = false;
    private volatile String serverName;

    /** Pending player-list request callbacks, keyed by request ID. */
    private final ConcurrentHashMap<String, CompletableFuture<List<NetworkVanishState>>> pendingListRequests
            = new ConcurrentHashMap<>();

    /** Outbound packets queued when no player is online to act as carrier. */
    private final java.util.concurrent.ConcurrentLinkedDeque<byte[]> pendingPackets
            = new java.util.concurrent.ConcurrentLinkedDeque<>();

    // Proxy update state — set when PROXY_UPDATE_NOTIFY is received from the proxy
    private volatile boolean proxyUpdateAvailable = false;
    private volatile String proxyCurrentVersion;
    private volatile String proxyLatestVersion;
    private volatile String proxyDownloadUrl;

    private static final long HANDSHAKE_TIMEOUT_TICKS = 100L; // 5 seconds (20 ticks/s)
    private static final long HELLO_RETRY_TICKS       = 100L; // retry interval when no player online

    public ProxyBridge(Vanishpp plugin) {
        this.plugin = plugin;
        this.serverName = plugin.getConfigManager().getConfig()
                .getString("proxy.server-name", "").trim();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, VppChannel.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, VppChannel.CHANNEL, this);
        scheduleHello(0L);
    }

    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, VppChannel.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, VppChannel.CHANNEL);
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    private void scheduleHello(long delayTicks) {
        plugin.getVanishScheduler().runLaterGlobal(() -> {
            if (handshakeComplete) return;
            Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (carrier == null) {
                // No players yet — retry
                scheduleHello(HELLO_RETRY_TICKS);
                return;
            }
            sendHello(carrier);
            scheduleHandshakeTimeout();
        }, delayTicks);
    }

    private void sendHello(Player carrier) {
        String name = resolveServerName();
        JsonObject payload = new JsonObject();
        payload.addProperty("serverName", name);
        payload.addProperty("pluginVersion", plugin.getDescription().getVersion());
        carrier.sendPluginMessage(plugin, VppChannel.CHANNEL, VppPacket.encode(VppMessage.HELLO, payload.toString()));
    }

    private void scheduleHandshakeTimeout() {
        plugin.getVanishScheduler().runLaterGlobal(() -> {
            if (!handshakeComplete) {
                plugin.getLogger().info("[Proxy] No VanishPP proxy detected within timeout — running in standalone mode.");
                proxyDetected = false;
            }
        }, HANDSHAKE_TIMEOUT_TICKS);
    }

    // ── Incoming messages ─────────────────────────────────────────────────────

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!VppChannel.CHANNEL.equals(channel)) return;
        DecodedPacket packet;
        try {
            packet = VppPacket.decode(message);
        } catch (Exception e) {
            plugin.getLogger().warning("[Proxy] Malformed packet: " + e.getMessage());
            return;
        }

        JsonObject json = JsonParser.parseString(packet.jsonPayload()).getAsJsonObject();

        switch (packet.type()) {
            case PONG                -> handlePong(json);
            case CONFIG_PUSH         -> handleConfigPush(json);
            case VANISH_SYNC         -> handleVanishSync(json);
            case STATE_RESPONSE      -> handleStateResponse(json);
            case PLAYER_LIST_RESPONSE-> handlePlayerListResponse(json);
            case PROXY_UPDATE_NOTIFY -> handleProxyUpdateNotify(json);
            default -> plugin.getLogger().fine("[Proxy] Unhandled packet type: " + packet.type());
        }
    }

    private void handlePong(JsonObject json) {
        if (handshakeComplete) return;
        handshakeComplete = true;
        proxyDetected = true;
        String proxyVersion = json.has("proxyVersion") ? json.get("proxyVersion").getAsString() : "?";
        plugin.getLogger().info("[Proxy] VanishPP proxy detected (proxy v" + proxyVersion + ") — running in proxy mode.");
        // Request initial full state sync
        sendPacket(VppPacket.encode(VppMessage.STATE_QUERY, "{}"));
    }

    private void handleConfigPush(JsonObject json) {
        String configJson = json.has("config") ? json.get("config").getAsString() : "{}";
        plugin.getProxyConfigCache().update(configJson, resolveServerName());
    }

    private void handleVanishSync(JsonObject json) {
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());
        boolean vanished = json.get("vanished").getAsBoolean();
        String playerName = json.has("playerName") ? json.get("playerName").getAsString() : "";
        String originServer = json.has("serverName") ? json.get("serverName").getAsString() : "";
        plugin.getVanishScheduler().runGlobal(() -> {
            plugin.handleNetworkVanishSync(uuid, vanished);
            if (!playerName.isEmpty() && plugin.getConfigManager().proxyBroadcastEnabled) {
                int level = plugin.getStorageProvider().getVanishLevel(uuid);
                if (level >= plugin.getConfigManager().proxyBroadcastMinLevel) {
                    String msgKey = vanished ? "staff.proxy-broadcast-vanish" : "staff.proxy-broadcast-unvanish";
                    String msg = plugin.getConfigManager().getLanguageManager().getMessage(msgKey)
                            .replace("%player%", playerName)
                            .replace("%server%", originServer);
                    for (Player staff : Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("vanishpp.see")) {
                            plugin.getMessageManager().sendMessage(staff, msg);
                        }
                    }
                }
            }
        });
    }

    private void handleStateResponse(JsonObject json) {
        if (!json.has("vanished")) return;
        JsonArray arr = json.getAsJsonArray("vanished");
        List<NetworkVanishState> states = parseStates(arr);
        plugin.getVanishScheduler().runGlobal(() -> plugin.applyNetworkVanishState(states));
    }

    private void handleProxyUpdateNotify(JsonObject json) {
        proxyCurrentVersion = json.has("currentVersion") ? json.get("currentVersion").getAsString() : "?";
        proxyLatestVersion  = json.has("latestVersion")  ? json.get("latestVersion").getAsString()  : "?";
        proxyDownloadUrl    = json.has("downloadUrl")    ? json.get("downloadUrl").getAsString()    : "";
        proxyUpdateAvailable = true;

        plugin.getLogger().warning("[Proxy] Vanish++ Velocity has an update available: "
                + proxyLatestVersion + " (running " + proxyCurrentVersion + ")");
        plugin.getLogger().warning("[Proxy] Download: " + proxyDownloadUrl);
    }

    private void handlePlayerListResponse(JsonObject json) {
        String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
        CompletableFuture<List<NetworkVanishState>> future = pendingListRequests.remove(requestId);
        if (future == null) return;
        JsonArray arr = json.has("players") ? json.getAsJsonArray("players") : new JsonArray();
        future.complete(parseStates(arr));
    }

    // ── Outgoing messages ─────────────────────────────────────────────────────

    /** Informs the proxy that a player vanished or unvanished on this server. */
    public void sendVanishEvent(UUID uuid, String playerName, boolean vanished, int vanishLevel) {
        if (!proxyDetected) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("playerName", playerName);
        payload.addProperty("vanished", vanished);
        payload.addProperty("vanishLevel", vanishLevel);
        sendPacket(VppPacket.encode(VppMessage.VANISH_EVENT, payload.toString()));
    }

    /** Asks the proxy for the cross-server vanished player list asynchronously. */
    public void requestPlayerList(Consumer<List<NetworkVanishState>> callback) {
        if (!proxyDetected) {
            callback.accept(Collections.emptyList());
            return;
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<List<NetworkVanishState>> future = new CompletableFuture<>();
        pendingListRequests.put(requestId, future);

        future.orTimeout(3, TimeUnit.SECONDS)
              .whenComplete((result, ex) -> {
                  pendingListRequests.remove(requestId);
                  if (ex != null) {
                      plugin.getLogger().warning("[Proxy] Player list request timed out.");
                      callback.accept(Collections.emptyList());
                  } else {
                      callback.accept(result);
                  }
              });

        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", requestId);
        sendPacket(VppPacket.encode(VppMessage.PLAYER_LIST_QUERY, payload.toString()));
    }

    /**
     * Sends a CONFIG_SYNC to the proxy with a map of dotted config keys → values.
     * Velocity will apply them in-memory and push the updated config to all servers.
     */
    public void sendConfigSync(java.util.Map<String, String> entries) {
        if (!proxyDetected) return;
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        com.google.gson.JsonObject entriesObj = new com.google.gson.JsonObject();
        entries.forEach(entriesObj::addProperty);
        payload.add("entries", entriesObj);
        sendPacket(VppPacket.encode(VppMessage.CONFIG_SYNC, payload.toString()));
    }

    /**
     * Asks the proxy to deliver a chat message to a specific player by UUID on whatever
     * server they are currently connected to. Returns true if the request was queued/sent.
     */
    public boolean sendPlayerMessage(UUID uuid, String message) {
        if (!proxyDetected) return false;
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("message", message);
        sendPacket(VppPacket.encode(VppMessage.PLAYER_MESSAGE, payload.toString()));
        return true;
    }

    /** Sends a RELOAD_REQUEST to the proxy (triggered by /vanishreload on this server). */
    public void sendReloadRequest(String requestedBy) {
        if (!proxyDetected) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("requestedBy", requestedBy);
        sendPacket(VppPacket.encode(VppMessage.RELOAD_REQUEST, payload.toString()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Sends a raw packet via the first available online player, or queues it for later. */
    public void sendPacket(byte[] data) {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier != null) {
            carrier.sendPluginMessage(plugin, VppChannel.CHANNEL, data);
        } else {
            pendingPackets.addLast(data);
        }
    }

    /** Flushes any queued packets now that a carrier player is available. Call on PlayerJoinEvent. */
    public void flushPendingPackets(Player carrier) {
        byte[] data;
        while ((data = pendingPackets.pollFirst()) != null) {
            carrier.sendPluginMessage(plugin, VppChannel.CHANNEL, data);
        }
    }

    private String resolveServerName() {
        if (serverName == null || serverName.isEmpty()) {
            serverName = plugin.getServer().getIp() + ":" + plugin.getServer().getPort();
        }
        return serverName;
    }

    private List<NetworkVanishState> parseStates(JsonArray arr) {
        List<NetworkVanishState> result = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            try {
                result.add(NetworkVanishState.of(
                        obj.get("uuid").getAsString(),
                        obj.has("playerName") ? obj.get("playerName").getAsString() : "",
                        obj.has("serverName") ? obj.get("serverName").getAsString() : "",
                        obj.has("vanishLevel") ? obj.get("vanishLevel").getAsInt() : 1
                ));
            } catch (Exception ignored) {}
        }
        return result;
    }

    public boolean isProxyDetected()        { return proxyDetected; }
    public String getServerName()           { return resolveServerName(); }
    public boolean isProxyUpdateAvailable() { return proxyUpdateAvailable; }
    public String getProxyCurrentVersion()  { return proxyCurrentVersion; }
    public String getProxyLatestVersion()   { return proxyLatestVersion; }
    public String getProxyDownloadUrl()     { return proxyDownloadUrl; }
}
