package net.thecommandcraft.vanishpp.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface StorageProvider {

    void init() throws Exception;

    void shutdown();

    // ── Vanish State ──────────────────────────────────��───────────────────────
    boolean isVanished(UUID uuid);

    void setVanished(UUID uuid, boolean vanished);

    Set<UUID> getVanishedPlayers();

    // ── Rules ─────────────────────────────────────────────────────────────────
    boolean getRule(UUID uuid, String rule, boolean defaultValue);

    void setRule(UUID uuid, String rule, Object value);

    Map<String, Object> getRules(UUID uuid);

    // ── Rule Presets ──────────────────────────────────────────────────────────
    /** Saves a named rule preset for a player (overwrites if the name exists). */
    void saveRulePreset(UUID uuid, String presetName, Map<String, Boolean> rules);

    /** Returns the rules in a saved preset, or null if the preset does not exist. */
    Map<String, Boolean> loadRulePreset(UUID uuid, String presetName);

    /** Returns all preset names saved by this player. */
    Set<String> listRulePresets(UUID uuid);

    /** Deletes a preset. No-op if it does not exist. */
    void deleteRulePreset(UUID uuid, String presetName);

    // ── Preferences ───────────────────────────────────────────────────────────
    /** Per-player "always join vanished" toggle. */
    boolean getAutoVanishOnJoin(UUID uuid);

    void setAutoVanishOnJoin(UUID uuid, boolean value);

    // ── Metadata / Acknowledgements ───────────────────────────────────────────
    boolean hasAcknowledged(UUID uuid, String notificationId);

    void addAcknowledgement(UUID uuid, String notificationId);

    // ── Permission Levels (Advanced Tracking) ────────────────────────��───────
    int getVanishLevel(UUID uuid);

    void setVanishLevel(UUID uuid, int level);

    // ── Vanish History ────────────────────────────────────────────────────────
    /** Appends one audit-log entry. */
    void addHistoryEntry(VanishHistoryEntry entry);

    /**
     * Returns the N most-recent history entries for a specific player,
     * offset by {@code (page-1)*perPage}.
     */
    List<VanishHistoryEntry> getPlayerHistory(UUID uuid, int page, int perPage);

    /**
     * Returns the N most-recent history entries across all players,
     * offset by {@code (page-1)*perPage}.
     */
    List<VanishHistoryEntry> getAllHistory(int page, int perPage);

    /** Removes entries older than {@code retentionDays} days. Returns number deleted. */
    int pruneHistory(int retentionDays);

    // ── Vanish Statistics ─────────────────────────────────────────────────────
    VanishStats getStats(UUID uuid);

    /** Called when a vanish session ends to record its duration (ms). */
    void recordVanishSession(UUID uuid, long durationMs);

    // ── Cleanup ───────────────────────────────────────────────────────────────
    void removePlayerData(UUID uuid);

    // ── Migration support ─────────────────────────────────────────────────────
    /** Returns all UUIDs with any stored data (vanish state, rules, levels, or acknowledgements). */
    Set<UUID> getAllKnownPlayers();

    /** Returns all notification IDs acknowledged by this player. */
    Set<String> getAcknowledgements(UUID uuid);
}
