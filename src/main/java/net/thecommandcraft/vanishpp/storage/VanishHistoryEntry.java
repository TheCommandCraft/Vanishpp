package net.thecommandcraft.vanishpp.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a single vanish/unvanish event for the audit log.
 */
public final class VanishHistoryEntry {

    public enum Action { VANISH, UNVANISH }

    private final UUID playerUuid;
    private final String playerName;
    private final Action action;
    private final Instant timestamp;
    private final String server;
    private final String reason;   // may be null
    private final long durationMs; // only set on UNVANISH; 0 on VANISH

    public VanishHistoryEntry(UUID playerUuid, String playerName, Action action,
                              Instant timestamp, String server, String reason, long durationMs) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.action = action;
        this.timestamp = timestamp;
        this.server = server;
        this.reason = reason;
        this.durationMs = durationMs;
    }

    /** Convenience factory for a vanish event (no duration yet). */
    public static VanishHistoryEntry vanish(UUID uuid, String name, String server, String reason) {
        return new VanishHistoryEntry(uuid, name, Action.VANISH,
                Instant.now(), server, reason, 0);
    }

    /** Convenience factory for an unvanish event with the session duration. */
    public static VanishHistoryEntry unvanish(UUID uuid, String name, String server,
                                              String reason, long durationMs) {
        return new VanishHistoryEntry(uuid, name, Action.UNVANISH,
                Instant.now(), server, reason, durationMs);
    }

    public UUID getPlayerUuid()  { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public Action getAction()    { return action; }
    public Instant getTimestamp() { return timestamp; }
    public String getServer()    { return server; }
    public String getReason()    { return reason; }
    public long getDurationMs()  { return durationMs; }

    /** Human-readable duration string, e.g. "1h 23m 45s". Only meaningful on UNVANISH entries. */
    public String formatDuration() {
        if (durationMs <= 0) return "—";
        long secs = durationMs / 1000;
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + playerName + " " + action
                + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : "")
                + (action == Action.UNVANISH ? " | duration: " + formatDuration() : "");
    }
}
