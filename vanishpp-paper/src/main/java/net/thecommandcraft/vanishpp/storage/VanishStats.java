package net.thecommandcraft.vanishpp.storage;

/**
 * Snapshot of vanish statistics for a single player.
 */
public final class VanishStats {

    private final long totalVanishTimeMs;
    private final int vanishCount;
    private final long longestSessionMs;

    public VanishStats(long totalVanishTimeMs, int vanishCount, long longestSessionMs) {
        this.totalVanishTimeMs = totalVanishTimeMs;
        this.vanishCount = vanishCount;
        this.longestSessionMs = longestSessionMs;
    }

    public static VanishStats empty() {
        return new VanishStats(0, 0, 0);
    }

    public long getTotalVanishTimeMs()  { return totalVanishTimeMs; }
    public int getVanishCount()         { return vanishCount; }
    public long getLongestSessionMs()   { return longestSessionMs; }

    /** Formatted total time, e.g. "3h 12m". */
    public String formatTotal() {
        return formatDuration(totalVanishTimeMs);
    }

    /** Formatted longest session, e.g. "45m 30s". */
    public String formatLongest() {
        return formatDuration(longestSessionMs);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long secs = ms / 1000;
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
