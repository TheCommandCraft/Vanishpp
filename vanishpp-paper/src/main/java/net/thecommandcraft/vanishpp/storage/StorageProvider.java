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

    // Metadata / Acknowledgements
    boolean hasAcknowledged(UUID uuid, String notificationId);

    void addAcknowledgement(UUID uuid, String notificationId);

    // Permission Levels (Advanced Tracking)
    int getVanishLevel(UUID uuid);

    void setVanishLevel(UUID uuid, int level);

    // Cleanup
    void removePlayerData(UUID uuid);

    // Migration support
    /** Returns all UUIDs with any stored data (vanish state, rules, levels, or acknowledgements). */
    Set<UUID> getAllKnownPlayers();

    /** Returns all notification IDs acknowledged by this player. */
    Set<String> getAcknowledgements(UUID uuid);

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

    int pruneHistory(int retentionDays);

    // ── Vanish Statistics ─────────────────────────────────────────────────────

    VanishStats getStats(UUID uuid);

    void recordVanishSession(UUID uuid, long durationMs);
}
