package net.thecommandcraft.vanishpp.velocity;

import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;
import net.thecommandcraft.vanishpp.velocity.hooks.LuckPermsHook;
import net.thecommandcraft.vanishpp.velocity.storage.ProxySqlStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns all persistent Vanish++ state on the proxy side.
 * Acts as an in-memory cache with write-through to {@link ProxySqlStorage}.
 * All mutations are thread-safe.
 */
public class ProxyStateManager {

    private final VanishppVelocity plugin;
    private final VelocityConfigManager configManager;
    private ProxySqlStorage sqlStorage;
    private final Set<UUID> knownVisible = ConcurrentHashMap.newKeySet();
    private final net.thecommandcraft.vanishpp.common.state.OrderedWrites writes;
    private boolean sqlAuthoritative;
    private final String session = UUID.randomUUID().toString();
    private long revision;
    /** Null when LuckPerms isn't present on the proxy (optional dependency). */
    private LuckPermsHook luckPermsHook;

    /** UUID → full network state for all currently vanished players. */
    private final ConcurrentHashMap<UUID, NetworkVanishState> vanishedPlayers = new ConcurrentHashMap<>();

    public ProxyStateManager(VanishppVelocity plugin, VelocityConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.writes = new net.thecommandcraft.vanishpp.common.state.OrderedWrites(
                task -> plugin.getProxy().getScheduler().buildTask(plugin, task).schedule(),
                error -> plugin.getLogger().error("State write failed", error));
    }

    public synchronized void init() {
        loadLocal();
        String storageType = configManager.getStorageType();
        if (storageType.equals("MYSQL") || storageType.equals("POSTGRESQL")) {
            sqlStorage = new ProxySqlStorage(configManager, plugin.getLogger());
            try {
                sqlStorage.init();
                Set<UUID> persisted = sqlStorage.getVanishedPlayers();
                for (UUID uuid : persisted) {
                    if (!knownVisible.contains(uuid) && !vanishedPlayers.containsKey(uuid)) {
                        int level = sqlStorage.getVanishLevel(uuid);
                        vanishedPlayers.put(uuid, new NetworkVanishState(uuid, "", "unknown", level));
                    }
                }
                for (UUID uuid : knownVisible) writes.submit(() -> sqlStorage.setVanished(uuid, false));
                for (UUID uuid : vanishedPlayers.keySet()) writes.submit(() -> sqlStorage.setVanished(uuid, true));
                sqlAuthoritative = true;
                saveLocal();
                plugin.getLogger().info("ProxyStateManager loaded {} vanished player(s) from DB.", vanishedPlayers.size());
            } catch (Exception e) {
                plugin.getLogger().error("Failed to init SQL storage — proxy will run without persistence.", e);
                sqlAuthoritative = false;
                sqlStorage.shutdown();
                sqlStorage = null;
            }
        } else {
            plugin.getLogger().info("Storage type is {}. Proxy will persist state in state.yml.", storageType);
        }
    }

    public void shutdown() {
        writes.close();
        if (sqlStorage != null) sqlStorage.shutdown();
    }

    /** Set once LuckPerms is confirmed present (see VanishppVelocity#onProxyInitialize). */
    public void setLuckPermsHook(LuckPermsHook hook) {
        this.luckPermsHook = hook;
    }

    // ── Vanish state ─────────────────────────────────────────────────────────

    public synchronized void setVanished(UUID uuid, String playerName, String serverName, boolean vanished, int vanishLevel) {
        NetworkVanishState previous = vanishedPlayers.get(uuid);
        boolean wasVisible = knownVisible.contains(uuid);
        revision++;
        if (vanished) {
            knownVisible.remove(uuid);
            vanishedPlayers.put(uuid, new NetworkVanishState(uuid, playerName, serverName, vanishLevel));
        } else {
            vanishedPlayers.remove(uuid);
            knownVisible.add(uuid);
        }
        try { saveLocal(); }
        catch (RuntimeException e) {
            if (previous == null) vanishedPlayers.remove(uuid); else vanishedPlayers.put(uuid, previous);
            if (wasVisible) knownVisible.add(uuid); else knownVisible.remove(uuid);
            revision--;
            throw e;
        }
        if (sqlStorage != null) {
            writes.submit(() -> sqlStorage.setVanished(uuid, vanished));
        }
        if (luckPermsHook != null) luckPermsHook.setVanished(uuid);
    }

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.containsKey(uuid);
    }

    /**
     * Updates the server name for a vanished player (e.g. on server switch).
     * No-op if the player is not currently vanished.
     */
    public synchronized void updateServer(UUID uuid, String serverName) {
        vanishedPlayers.computeIfPresent(uuid, (k, old) ->
                new NetworkVanishState(old.uuid(), old.playerName(), serverName, old.vanishLevel()));
    }

    public Set<NetworkVanishState> getAllVanishedPlayers() {
        return new HashSet<>(vanishedPlayers.values());
    }

    public Set<NetworkVanishState> getVanishedOnServer(String serverName) {
        Set<NetworkVanishState> result = new HashSet<>();
        for (NetworkVanishState state : vanishedPlayers.values()) {
            if (state.serverName().equals(serverName)) result.add(state);
        }
        return result;
    }

    public NetworkVanishState getState(UUID uuid) {
        return vanishedPlayers.get(uuid);
    }

    // ── Vanish levels ─────────────────────────────────────────────────────────

    public int getVanishLevel(UUID uuid) {
        NetworkVanishState state = vanishedPlayers.get(uuid);
        if (state != null) return state.vanishLevel();
        if (sqlStorage != null) return sqlStorage.getVanishLevel(uuid);
        return configManager.getSnapshot().defaultVanishLevel;
    }

    public synchronized void setVanishLevel(UUID uuid, int level) {
        vanishedPlayers.computeIfPresent(uuid, (k, old) ->
                new NetworkVanishState(old.uuid(), old.playerName(), old.serverName(), level));
        saveLocal();
        if (sqlStorage != null) {
            writes.submit(() -> sqlStorage.setVanishLevel(uuid, level));
        }
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    public boolean getRule(UUID uuid, String rule) {
        boolean defaultValue = configManager.getSnapshot().defaultRules.getOrDefault(rule, false);
        if (sqlStorage != null) return sqlStorage.getRule(uuid, rule, defaultValue);
        return defaultValue;
    }

    public void setRule(UUID uuid, String rule, boolean value) {
        if (sqlStorage != null) {
            writes.submit(() -> sqlStorage.setRule(uuid, rule, value));
        }
    }

    public Map<String, Object> getRules(UUID uuid) {
        if (sqlStorage != null) return sqlStorage.getRules(uuid);
        return new HashMap<>();
    }

    // ── Acknowledgements ──────────────────────────────────────────────────────

    public boolean hasAcknowledged(UUID uuid, String notificationId) {
        if (sqlStorage != null) return sqlStorage.hasAcknowledged(uuid, notificationId);
        return false;
    }

    public void addAcknowledgement(UUID uuid, String notificationId) {
        if (sqlStorage != null) {
            writes.submit(() -> sqlStorage.addAcknowledgement(uuid, notificationId));
        }
    }
    public synchronized boolean bootstrap(Collection<NetworkVanishState> states) {
        if (sqlAuthoritative) return false;
        boolean changed = false;
        for (NetworkVanishState state : states) {
            if (!knownVisible.contains(state.uuid()) && vanishedPlayers.putIfAbsent(state.uuid(), state) == null) changed = true;
        }
        if (changed) { revision++; saveLocal(); }
        return changed;
    }

    public Set<UUID> getKnownVisible() { return Set.copyOf(knownVisible); }
    public boolean isSqlAuthoritative() { return sqlAuthoritative; }
    public String getSession() { return session; }
    public long getRevision() { return revision; }

    private void loadLocal() {
        java.nio.file.Path path = configManager.getDataDirectory().resolve("state.yml");
        if (!java.nio.file.Files.exists(path)) return;
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(path)) {
            Object value = new org.yaml.snakeyaml.Yaml().load(reader);
            if (!(value instanceof Map<?, ?> data)) throw new IllegalStateException("Invalid state.yml");
            for (Map.Entry<?, ?> entry : data.entrySet()) {
                UUID uuid = UUID.fromString(entry.getKey().toString());
                Map<?, ?> state = (Map<?, ?>) entry.getValue();
                if (Boolean.TRUE.equals(state.get("vanished"))) {
                    vanishedPlayers.put(uuid, new NetworkVanishState(uuid, state.get("name").toString(),
                            state.get("server").toString(), ((Number) state.get("level")).intValue()));
                } else knownVisible.add(uuid);
            }
        } catch (Exception e) { throw new IllegalStateException("Could not load proxy state; refusing an empty snapshot", e); }
    }

    private void saveLocal() {
        Map<String, Object> data = new TreeMap<>();
        for (NetworkVanishState state : vanishedPlayers.values()) {
            data.put(state.uuid().toString(), Map.of("vanished", true, "name", state.playerName(),
                    "server", state.serverName(), "level", state.vanishLevel()));
        }
        for (UUID uuid : knownVisible) data.put(uuid.toString(), Map.of("vanished", false));
        java.nio.file.Path directory = configManager.getDataDirectory();
        java.nio.file.Path temporary = directory.resolve("state.yml.tmp");
        try {
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(temporary, new org.yaml.snakeyaml.Yaml().dump(data), java.nio.charset.StandardCharsets.UTF_8);
            try {
                java.nio.file.Files.move(temporary, directory.resolve("state.yml"),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temporary, directory.resolve("state.yml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not persist proxy state", e); }
    }

}
