package net.thecommandcraft.vanishpp.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface StorageProvider {

    void init() throws Exception;

    void shutdown();

    // Vanish State
    boolean isVanished(UUID uuid);

    void setVanished(UUID uuid, boolean vanished);

    Set<UUID> getVanishedPlayers();

    // Rules
    boolean getRule(UUID uuid, String rule, boolean defaultValue);

    void setRule(UUID uuid, String rule, Object value);

    Map<String, Object> getRules(UUID uuid);

    default Map<String, Object> getRecoveryRules(UUID uuid) { return getRules(uuid); }

    // Metadata / Acknowledgements
    boolean hasAcknowledged(UUID uuid, String notificationId);

    void addAcknowledgement(UUID uuid, String notificationId);

    void removeAcknowledgement(UUID uuid, String notificationId);

    String EXPIRY_PREFIX = "__expiry__";

    void setTemporaryRule(UUID uuid, String rule, boolean value, String token);

    default boolean expireRule(UUID uuid, String rule, String token) {
        synchronized (this) {
            if (!token.equals(getRules(uuid).get(EXPIRY_PREFIX + rule))) return false;
            setRule(uuid, rule, Boolean.parseBoolean(token.split(":", 3)[1]));
            return true;
        }
    }

    // Permission Levels (Advanced Tracking)
    int getVanishLevel(UUID uuid);

    default int getRecoveryVanishLevel(UUID uuid) { return getVanishLevel(uuid); }

    void setVanishLevel(UUID uuid, int level);

    // Cleanup
    void removePlayerData(UUID uuid);

    /** Copy a stable history snapshot; implementations with paged storage override this. */
    default void copyHistoryTo(StorageProvider target) {
        for (VanishHistoryEntry entry : getAllHistory(1, Integer.MAX_VALUE)) target.addHistoryEntry(entry);
    }

    // Migration support
    /** Returns all UUIDs with any stored data (vanish state, rules, levels, or acknowledgements). */
    Set<UUID> getAllKnownPlayers();

    /** Returns all notification IDs acknowledged by this player. */
    Set<String> getAcknowledgements(UUID uuid);

    default Set<String> getRecoveryAcknowledgements(UUID uuid) { return getAcknowledgements(uuid); }

    // ── Rule Presets ──────────────────────────────────────────────────────────

    void saveRulePreset(UUID uuid, String presetName, Map<String, Boolean> rules);

    Map<String, Boolean> loadRulePreset(UUID uuid, String presetName);

    Set<String> listRulePresets(UUID uuid);

    void deleteRulePreset(UUID uuid, String presetName);

    // ── Per-player Preferences ────────────────────────────────────────────────

    boolean getAutoVanishOnJoin(UUID uuid);

    void setAutoVanishOnJoin(UUID uuid, boolean value);

    // ── Vanish History ────────────────────────────────────────────────────────

    void addHistoryEntry(VanishHistoryEntry entry);

    List<VanishHistoryEntry> getPlayerHistory(UUID uuid, int page, int perPage);

    List<VanishHistoryEntry> getAllHistory(int page, int perPage);

    default UUID resolveHistoryPlayer(String name) {
        return getAllHistory(1, Integer.MAX_VALUE).stream().filter(entry -> entry.getPlayerName().equalsIgnoreCase(name))
                .map(VanishHistoryEntry::getPlayerUuid).findFirst().orElse(null);
    }

    int pruneHistory(int retentionDays);

    // ── Vanish Statistics ─────────────────────────────────────────────────────

    VanishStats getStats(UUID uuid);

    void recordVanishSession(UUID uuid, long durationMs);

    /** Overwrites the stored stats for a player. Used only during storage migration. */
    void setStats(UUID uuid, VanishStats stats);
}
