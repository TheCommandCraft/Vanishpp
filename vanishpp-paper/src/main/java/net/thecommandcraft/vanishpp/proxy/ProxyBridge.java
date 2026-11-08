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
 * <p>When proxy.enabled is set, sends a HELLO message via the first online player.
 * If a PONG is received within 5 seconds the server is in <em>proxy mode</em>
 * and all cross-server state operations route through the proxy.
 * If no PONG arrives within 5 seconds the server stays in standalone mode
 * and existing behaviour is unchanged.</p>
 */
public class ProxyBridge implements PluginMessageListener {

    private final Vanishpp plugin;
    private final boolean configured;
    private volatile boolean active = false;
    private volatile boolean helloSent = false;
    private final java.util.concurrent.atomic.AtomicBoolean flushing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean retryScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean outboundSuspended;
    private volatile long sendGeneration;
    private int consecutiveSendFailures;
    private volatile boolean proxyDetected = false;
    private volatile boolean handshakeComplete = false;
    private volatile String serverName;
    private final String clientSession = UUID.randomUUID().toString();
    private final java.util.concurrent.atomic.AtomicLong outboundSequence = new java.util.concurrent.atomic.AtomicLong();
    private String networkSession;
    private final Map<UUID, Long> networkRevisions = new HashMap<>();
    private record PendingChange(long sequence, byte[] packet) {}
    private final Map<UUID, PendingChange> pendingChanges = new ConcurrentHashMap<>();
    private static final class Transfer {
        final String id;
        Runnable cancel = () -> {};
        Transfer(String id) { this.id = id; }
    }
    private final Map<UUID, Transfer> transfers = new ConcurrentHashMap<>();
    private record JoinRequest(UUID uuid, Consumer<Boolean> callback) {}
    private final Map<String, JoinRequest> pendingJoins = new ConcurrentHashMap<>();

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
    private volatile net.thecommandcraft.vanishpp.common.update.UpdatePriority proxyUpdatePriority =
            net.thecommandcraft.vanishpp.common.update.UpdatePriority.FEATURE;
    private volatile String proxyDownloadUrl;

    private static final long HANDSHAKE_TIMEOUT_TICKS = 100L; // 5 seconds (20 ticks/s)
    private static final long HELLO_RETRY_TICKS       = 100L; // retry interval when no player online
    private static final int PACKETS_PER_FLUSH = 64;
    private static final int MAX_SEND_FAILURES = 5;
    private static final long SEND_RETRY_TICKS = 20L;
    private static final long MAX_SEND_RETRY_TICKS = 200L;

    public ProxyBridge(Vanishpp plugin) {
        this.plugin = plugin;
        this.configured = plugin.getConfigManager().getConfig().getBoolean("proxy.enabled", false);
        this.serverName = plugin.getConfigManager().getConfig()
                .getString("proxy.server-name", "").trim();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init() {
        if (active || !configured) return;
        active = true;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, VppChannel.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, VppChannel.CHANNEL, this);
        scheduleHello(0L);
    }

    public void shutdown() {
        active = false;
        sendGeneration++;
        outboundSuspended = false;
        retryScheduled.set(false);
        consecutiveSendFailures = 0;
        helloSent = false;
        proxyDetected = false;
        handshakeComplete = false;
        networkSession = null;
        networkRevisions.clear();
        pendingChanges.clear();
        transfers.values().forEach(transfer -> transfer.cancel.run());
        transfers.clear();
        pendingPackets.clear();
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, VppChannel.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, VppChannel.CHANNEL);
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    private void scheduleHello(long delayTicks) {
        plugin.getVanishScheduler().runLaterGlobal(() -> {
            if (!active || handshakeComplete) return;
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
        var provider = plugin.getStorageProvider();
        Runnable build = () -> {
            try { sendHello(carrier, provider); }
            catch (RuntimeException e) { plugin.getLogger().warning("Proxy bootstrap settings unavailable; retrying: " + e.getMessage()); }
        };
        if (provider instanceof net.thecommandcraft.vanishpp.storage.SqlStorage) plugin.submitStorageWork(build);
        else build.run();
    }

    private void sendHello(Player carrier, net.thecommandcraft.vanishpp.storage.StorageProvider provider) {
        String name = resolveServerName();
        JsonObject payload = new JsonObject();
        payload.addProperty("serverName", name);
        payload.addProperty("pluginVersion", plugin.getDescription().getVersion());
        JsonArray restored = new JsonArray();
        for (UUID uuid : plugin.getBootstrapVanishedPlayers()) {
            JsonObject state = new JsonObject();
            state.addProperty("uuid", uuid.toString());
            state.addProperty("vanishLevel", provider.getRecoveryVanishLevel(uuid));
            restored.add(state);
        }
        payload.add("vanished", restored);
        byte[] packet = VppPacket.encode(VppMessage.HELLO, payload.toString());
        plugin.getVanishScheduler().runEntityNow(carrier, () -> {
            if (!active || handshakeComplete || !carrier.isOnline() || provider != plugin.getStorageProvider()) return;
            helloSent = true;
            carrier.sendPluginMessage(plugin, VppChannel.CHANNEL, packet);
        });
    }

    private void scheduleHandshakeTimeout() {
        plugin.getVanishScheduler().runLaterGlobal(() -> {
            if (active && !handshakeComplete) {
                plugin.getLogger().info("[Proxy] No VanishPP proxy detected within timeout — running in standalone mode.");
                shutdown();
            }
        }, HANDSHAKE_TIMEOUT_TICKS);
    }

    // ── Incoming messages ─────────────────────────────────────────────────────

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!active || !VppChannel.CHANNEL.equals(channel)) return;
        DecodedPacket packet;
        try {
            packet = VppPacket.decode(message);
        } catch (Exception e) {
            plugin.getLogger().warning("[Proxy] Malformed packet: " + e.getMessage());
            return;
        }

        if (packet.type() != VppMessage.PONG && !handshakeComplete) return;
        JsonObject json = JsonParser.parseString(packet.jsonPayload()).getAsJsonObject();

        switch (packet.type()) {
            case PONG                -> handlePong(json);
            case TRANSFER_PREPARE    -> prepareTransfer(json);
            case TRANSFER_CANCEL     -> cancelTransfer(json);
            case CONFIG_PUSH         -> handleConfigPush(json);
            case VANISH_SYNC         -> handleVanishSync(json);
            case STATE_RESPONSE      -> handleStateResponse(json);
            case PLAYER_LIST_RESPONSE-> handlePlayerListResponse(json);
            case PROXY_UPDATE_NOTIFY -> handleProxyUpdateNotify(json);
            default -> plugin.getLogger().fine("[Proxy] Unhandled packet type: " + packet.type());
        }
    }

    private void handlePong(JsonObject json) {
        if (!helloSent) return;
        String session = json.has("session") ? json.get("session").getAsString() : null;
        if (handshakeComplete && Objects.equals(networkSession, session)) return;
        if (session != null && !session.equals(networkSession)) {
            networkSession = session;
            networkRevisions.clear();
        }
        handshakeComplete = true;
        proxyDetected = true;
        String proxyVersion = json.has("proxyVersion") ? json.get("proxyVersion").getAsString() : "?";
        plugin.getLogger().info("[Proxy] VanishPP proxy detected (proxy v" + proxyVersion + ") — running in proxy mode.");
        // Request initial full state sync
        sendPacket(VppPacket.encode(VppMessage.STATE_QUERY, "{}"));
        pendingJoins.forEach(this::sendJoinQuery);
    }

    private void handleConfigPush(JsonObject json) {
        String configJson = json.has("config") ? json.get("config").getAsString() : "{}";
        plugin.getProxyConfigCache().update(configJson, resolveServerName());
    }

    private void handleVanishSync(JsonObject json) {
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());
        boolean vanished = json.get("vanished").getAsBoolean();
        plugin.getVanishScheduler().runGlobal(() -> {
            if (acceptRevision(uuid, json)) plugin.handleNetworkVanishSync(uuid, vanished);
        });
    }

    private void handleStateResponse(JsonObject json) {
        if (!json.has("vanished")) return;
        JsonArray arr = json.getAsJsonArray("vanished");
        List<NetworkVanishState> states = parseStates(arr);
        plugin.getVanishScheduler().runGlobal(() -> {
            Set<UUID> hidden = new HashSet<>();
            for (NetworkVanishState state : states) {
                hidden.add(state.uuid());
                if (acceptRevision(state.uuid(), json)) plugin.handleNetworkVanishSync(state.uuid(), true);
            }
            Set<UUID> visible = new HashSet<>();
            if (json.has("visible")) for (JsonElement entry : json.getAsJsonArray("visible")) visible.add(UUID.fromString(entry.getAsString()));
            if (json.has("authoritative") && json.get("authoritative").getAsBoolean()) {
                visible.addAll(plugin.getRawVanishedPlayers());
                visible.removeAll(hidden);
            }
            for (UUID uuid : visible) if (acceptRevision(uuid, json)) plugin.handleNetworkVanishSync(uuid, false);
            if (json.has("requestId") && json.has("joiningUuid") && json.has("joiningVanished")) {
                String requestId = json.get("requestId").getAsString();
                JoinRequest request = pendingJoins.get(requestId);
                if (request != null && request.uuid().toString().equals(json.get("joiningUuid").getAsString())
                        && (!json.has("session") || networkSession == null || networkSession.equals(json.get("session").getAsString()))
                        && pendingJoins.remove(requestId, request)) {
                    boolean accepted = acceptRevision(request.uuid(), json);
                    request.callback().accept(accepted ? json.get("joiningVanished").getAsBoolean() : plugin.isVanished(request.uuid()));
                }
            }
        });
    }

    private boolean acceptRevision(UUID uuid, JsonObject json) {
        if (pendingJoins.values().stream().anyMatch(request -> request.uuid().equals(uuid))) return false;
        PendingChange pending = pendingChanges.get(uuid);
        if (pending != null) {
            if (!json.has("clientSession") || !clientSession.equals(json.get("clientSession").getAsString())
                    || !json.has("sequence") || json.get("sequence").getAsLong() < pending.sequence()) return false;
            pendingChanges.remove(uuid, pending);
        }
        if (!json.has("session") || !json.has("revision")) return true;
        String session = json.get("session").getAsString();
        if (networkSession == null) networkSession = session;
        if (!networkSession.equals(session)) return false;
        long revision = json.get("revision").getAsLong();
        if (revision < networkRevisions.getOrDefault(uuid, -1L)) return false;
        networkRevisions.put(uuid, revision);
        return true;
    }

    private void handleProxyUpdateNotify(JsonObject json) {
        proxyCurrentVersion = json.has("currentVersion") ? json.get("currentVersion").getAsString() : "?";
        proxyLatestVersion  = json.has("latestVersion")  ? json.get("latestVersion").getAsString()  : "?";
        proxyDownloadUrl    = json.has("downloadUrl")    ? json.get("downloadUrl").getAsString()    : "";
        // Proxies older than 1.2.0 send no priority, so their updates count as feature updates.
        proxyUpdatePriority = net.thecommandcraft.vanishpp.common.update.UpdatePriority.fromConfig(
                json.has("priority") ? json.get("priority").getAsString() : null);
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
        byte[] packet = prepareVanishEvent(uuid, playerName, vanished, vanishLevel);
        if (packet != null) sendPacket(packet);
    }

    public byte[] prepareVanishEvent(UUID uuid, String playerName, boolean vanished, int vanishLevel) {
        if (isTransferPending(uuid)) return null;
        return createVanishEvent(uuid, playerName, vanished, vanishLevel);
    }

    private byte[] createVanishEvent(UUID uuid, String playerName, boolean vanished, int vanishLevel) {
        if (!active) return null;
        long sequence = outboundSequence.incrementAndGet();
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("playerName", playerName);
        payload.addProperty("vanished", vanished);
        payload.addProperty("vanishLevel", vanishLevel);
        payload.addProperty("clientSession", clientSession);
        payload.addProperty("sequence", sequence);
        byte[] packet = VppPacket.encode(VppMessage.VANISH_EVENT, payload.toString());
        pendingChanges.compute(uuid, (key, old) -> old == null || old.sequence() < sequence ? new PendingChange(sequence, packet) : old);
        return packet;
    }

    public void forgetPlayer(UUID uuid) {
        Transfer transfer = transfers.remove(uuid);
        if (transfer != null) transfer.cancel.run();
        pendingJoins.entrySet().removeIf(entry -> entry.getValue().uuid().equals(uuid));
    }

    public void requestJoinState(Player player, Consumer<Boolean> callback) {
        if (!active) init();
        String requestId = UUID.randomUUID().toString();
        JoinRequest request = new JoinRequest(player.getUniqueId(), callback);
        pendingJoins.entrySet().removeIf(entry -> entry.getValue().uuid().equals(player.getUniqueId()));
        pendingJoins.put(requestId, request);
        if (handshakeComplete) sendJoinQuery(requestId, request);
        else if (!helloSent) scheduleHello(0L);
        plugin.getVanishScheduler().runLaterGlobal(() -> {
            if (!pendingJoins.remove(requestId, request)) return;
            if (!plugin.isEnabled()) { plugin.abortProxyJoin(player.getUniqueId()); return; }
            plugin.getVanishScheduler().runEntity(player, () -> {
                if (player.isOnline()) player.kick(net.kyori.adventure.text.Component.text("Proxy vanish state is unavailable. Please reconnect shortly."));
                plugin.abortProxyJoin(player.getUniqueId());
            }, () -> plugin.abortProxyJoin(player.getUniqueId()));
        }, 100L);
    }

    public boolean isTransferPending(UUID uuid) { return transfers.containsKey(uuid); }

    private void prepareTransfer(JsonObject json) {
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());
        String id = json.get("requestId").getAsString();
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        plugin.getVanishScheduler().runEntityNow(player, () -> {
            if (!player.isOnline() || plugin.isProxyJoinPending(uuid)) return;
            Transfer transfer = new Transfer(id);
            Transfer old = transfers.put(uuid, transfer);
            if (old != null) old.cancel.run();
            settleTransfer(player, transfer);
        });
    }

    private void cancelTransfer(JsonObject json) {
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());
        Transfer transfer = transfers.get(uuid);
        if (transfer != null && transfer.id.equals(json.get("requestId").getAsString()) && transfers.remove(uuid, transfer))
            transfer.cancel.run();
    }

    private void settleTransfer(Player player, Transfer transfer) {
        UUID uuid = player.getUniqueId();
        long version = plugin.stateVersion(uuid);
        plugin.submitStorageWork(() -> {
            if (transfers.get(uuid) != transfer) return;
            if (plugin.hasPendingStateWrite(uuid)) {
                transfer.cancel = plugin.getVanishScheduler().startLater(() ->
                        plugin.getVanishScheduler().runEntity(player, () -> settleTransfer(player, transfer), null), 5L);
                return;
            }
            var provider = plugin.getStorageProvider();
            plugin.getVanishScheduler().runEntity(player, () -> {
                if (!active || !player.isOnline() || transfers.get(uuid) != transfer) return;
                if (version != plugin.stateVersion(uuid) || provider != plugin.getStorageProvider()) {
                    settleTransfer(player, transfer); return;
                }
                int level = plugin.getPermissionManager().vanishLevel(player);
                byte[] event = createVanishEvent(uuid, player.getName(), plugin.isVanished(uuid), level);
                JsonObject payload = JsonParser.parseString(VppPacket.decode(event).jsonPayload()).getAsJsonObject();
                payload.addProperty("requestId", transfer.id);
                player.sendPluginMessage(plugin, VppChannel.CHANNEL, VppPacket.encode(VppMessage.TRANSFER_READY, payload.toString()));
            }, null);
        });
    }

    public boolean isConfigured() { return configured; }

    private void sendJoinQuery(String requestId, JoinRequest request) {
        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", requestId);
        payload.addProperty("joiningUuid", request.uuid().toString());
        sendPacket(VppPacket.encode(VppMessage.STATE_QUERY, payload.toString()));
    }

    public void retryPendingChanges() {
        if (!active || !proxyDetected) return;
        for (PendingChange pending : pendingChanges.values()) sendPacket(pending.packet());
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
        if (!active || outboundSuspended) return;
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        pendingPackets.addLast(data);
        if (carrier != null) flushPendingPackets(carrier);
    }

    /** Flushes any queued packets now that a carrier player is available. Call on PlayerJoinEvent. */
    public void flushPendingPackets(Player carrier) {
        if (outboundSuspended || retryScheduled.get()) return;
        Runnable flush = () -> {
            if (!carrier.isOnline()) { if (active && !pendingPackets.isEmpty()) scheduleSendRetry(); return; }
            if (!active || outboundSuspended || retryScheduled.get()
                    || !flushing.compareAndSet(false, true)) return;
            try {
                for (int sent = 0; sent < PACKETS_PER_FLUSH && active; sent++) {
                    byte[] data = pendingPackets.pollFirst();
                    if (data == null) break;
                    try {
                        carrier.sendPluginMessage(plugin, VppChannel.CHANNEL, data);
                        consecutiveSendFailures = 0;
                    } catch (RuntimeException e) {
                        pendingPackets.addFirst(data);
                        consecutiveSendFailures++;
                        if (e instanceof IllegalArgumentException || consecutiveSendFailures >= MAX_SEND_FAILURES) {
                            outboundSuspended = true;
                            plugin.getLogger().severe("Proxy outbound suspended on " + VppChannel.CHANNEL + " after "
                                    + consecutiveSendFailures + " send failure(s); queued decisions are retained. Repair the channel and restart the server: "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                        } else {
                            if (consecutiveSendFailures == 1) plugin.getLogger().warning("Proxy send through "
                                    + carrier.getUniqueId() + " failed; retrying in order with backoff: "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                            scheduleSendRetry();
                        }
                        break;
                    }
                }
            } finally { flushing.set(false); }
            if (!pendingPackets.isEmpty() && !outboundSuspended && !retryScheduled.get())
                scheduleDrain(1L);
        };
        if (plugin.getVanishScheduler().owns(carrier)) flush.run();
        else plugin.getVanishScheduler().runEntity(carrier, flush, () -> {
            if (active && !pendingPackets.isEmpty()) scheduleSendRetry();
        });
    }

    private void scheduleSendRetry() {
        long delay = Math.min(MAX_SEND_RETRY_TICKS, SEND_RETRY_TICKS << Math.max(0, Math.min(consecutiveSendFailures - 1, 3)));
        scheduleDrain(delay);
    }

    private void scheduleDrain(long delayTicks) {
        if (!retryScheduled.compareAndSet(false, true)) return;
        long generation = sendGeneration;
        plugin.getVanishScheduler().runLaterGlobal(() -> {
            if (generation != sendGeneration) return;
            retryScheduled.set(false);
            if (!active || outboundSuspended) return;
            Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (carrier != null) flushPendingPackets(carrier);
        }, delayTicks);
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
    public net.thecommandcraft.vanishpp.common.update.UpdatePriority getProxyUpdatePriority() { return proxyUpdatePriority; }
}
