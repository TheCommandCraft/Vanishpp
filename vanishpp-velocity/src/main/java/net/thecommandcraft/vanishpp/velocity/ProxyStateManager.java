package net.thecommandcraft.vanishpp.velocity;

import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;
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

    /** UUID → full network state for all currently vanished players. */
    private final ConcurrentHashMap<UUID, NetworkVanishState> vanishedPlayers = new ConcurrentHashMap<>();

    public ProxyStateManager(VanishppVelocity plugin, VelocityConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void init() {
        String storageType = configManager.getStorageType();
        if (storageType.equals("MYSQL") || storageType.equals("POSTGRESQL")) {
            sqlStorage = new ProxySqlStorage(configManager, plugin.getLogger());
            try {
                sqlStorage.init();
                // Pre-load vanished set from DB into memory
                for (UUID uuid : sqlStorage.getVanishedPlayers()) {
                    int level = sqlStorage.getVanishLevel(uuid);
                    vanishedPlayers.put(uuid, new NetworkVanishState(uuid, "", "unknown", level));
                }
                plugin.getLogger().info("ProxyStateManager loaded {} vanished player(s) from DB.", vanishedPlayers.size());
            } catch (Exception e) {
                plugin.getLogger().error("Failed to init SQL storage — proxy will run without persistence.", e);
                sqlStorage = null;
            }
        } else {
            plugin.getLogger().info("Storage type is {}. Proxy will use in-memory state only (no cross-restart persistence).", storageType);
        }
    }

    public void shutdown() {
        if (sqlStorage != null) sqlStorage.shutdown();
    }

    // ── Vanish state ─────────────────────────────────────────────────────────

    public void setVanished(UUID uuid, String playerName, String serverName, boolean vanished, int vanishLevel) {
        if (vanished) {
            vanishedPlayers.put(uuid, new NetworkVanishState(uuid, playerName, serverName, vanishLevel));
        } else {
            vanishedPlayers.remove(uuid);
        }
        if (sqlStorage != null) {
            plugin.getProxy().getScheduler().buildTask(plugin, () -> sqlStorage.setVanished(uuid, vanished)).schedule();
        }
    }

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.containsKey(uuid);
    }

    /**
     * Updates the server name for a vanished player (e.g. on server switch).
     * No-op if the player is not currently vanished.
     */
    public void updateServer(UUID uuid, String serverName) {
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

    public void setVanishLevel(UUID uuid, int level) {
        vanishedPlayers.computeIfPresent(uuid, (k, old) ->
                new NetworkVanishState(old.uuid(), old.playerName(), old.serverName(), level));
        if (sqlStorage != null) {
            plugin.getProxy().getScheduler().buildTask(plugin, () -> sqlStorage.setVanishLevel(uuid, level)).schedule();
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
            plugin.getProxy().getScheduler().buildTask(plugin, () -> sqlStorage.setRule(uuid, rule, value)).schedule();
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
            plugin.getProxy().getScheduler().buildTask(plugin, () -> sqlStorage.addAcknowledgement(uuid, notificationId)).schedule();
        }
    }
}
