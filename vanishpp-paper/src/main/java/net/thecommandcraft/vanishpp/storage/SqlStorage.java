package net.thecommandcraft.vanishpp.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.thecommandcraft.vanishpp.Vanishpp;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public class SqlStorage implements StorageProvider {

    private final Vanishpp plugin;
    private DataSource dataSource;
    private final String type;
    private volatile long lastNotificationTime = 0;
    private static final long NOTIFICATION_COOLDOWN = 300000; // 5 minutes between notifications

    public SqlStorage(Vanishpp plugin, String type) {
        this.plugin = plugin;
        this.type = type.toLowerCase();
    }

    /** Package-private constructor for testing with a pre-configured DataSource (e.g. H2 in-memory). */
    SqlStorage(Vanishpp plugin, DataSource dataSource) {
        this.plugin = plugin;
        this.type = "mysql";
        this.dataSource = dataSource;
    }

    @Override
    public void init() throws Exception {
        if (this.dataSource == null) {
            HikariConfig config = new HikariConfig();
            String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
            String database = plugin.getConfig().getString("storage.mysql.database", "vanishpp");
            String username = plugin.getConfig().getString("storage.mysql.username", "root");
            String password = plugin.getConfig().getString("storage.mysql.password", "");
            boolean useSSL = plugin.getConfig().getBoolean("storage.mysql.use-ssl", false);

            if (type.equals("mysql")) {
                // Force driver class load in plugin classloader before HikariCP tries to find it
                Class.forName("com.mysql.cj.jdbc.Driver");
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSSL);
            } else if (type.equals("postgresql")) {
                Class.forName("org.postgresql.Driver");
                config.setDriverClassName("org.postgresql.Driver");
                config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database + "?ssl=" + useSSL);
            }

            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(plugin.getConfig().getInt("storage.mysql.pool-size", 10));
            config.setConnectionTimeout(5000);

            this.dataSource = new HikariDataSource(config);
        }

        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS vpp_vanished (uuid VARCHAR(36) PRIMARY KEY)");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_rules (uuid VARCHAR(36), rule_key VARCHAR(64), rule_value TEXT, PRIMARY KEY(uuid, rule_key))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_levels (uuid VARCHAR(36) PRIMARY KEY, level INT)");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_acknowledgements (uuid VARCHAR(36), notification_id VARCHAR(128), PRIMARY KEY(uuid, notification_id))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_schema_version (version INT PRIMARY KEY)");
            }
            // Seed schema version only if table is empty
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM vpp_schema_version");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (PreparedStatement insert = conn.prepareStatement("INSERT INTO vpp_schema_version (version) VALUES (?)")) {
                        insert.setInt(1, 1);
                        insert.executeUpdate();
                    }
                }
            }
            runSchemaMigrations(conn);
        }
    }

    private static final int CURRENT_SCHEMA_VERSION = 3;

    private void runSchemaMigrations(Connection conn) throws SQLException {
        int version;
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM vpp_schema_version");
             ResultSet rs = ps.executeQuery()) {
            version = rs.next() ? rs.getInt("version") : 0;
        }
        if (version >= CURRENT_SCHEMA_VERSION) return;

        // Schema migration chain: run all migrations from current version upwards
        if (version < 2) {
            try (Statement st = conn.createStatement()) {
                addColumnIfNotExists(st, "vpp_vanished", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                addColumnIfNotExists(st, "vpp_rules", "updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                addColumnIfNotExists(st, "vpp_levels", "updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            }
        }

        if (version < 3) {
            try (Statement st = conn.createStatement()) {
                // v3: rule presets, preferences, history, stats tables
                st.execute("CREATE TABLE IF NOT EXISTS vpp_rule_presets ("
                        + "uuid VARCHAR(36), preset_name VARCHAR(64), rule_key VARCHAR(64), rule_value TEXT,"
                        + "PRIMARY KEY(uuid, preset_name, rule_key))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_preferences ("
                        + "uuid VARCHAR(36), pref_key VARCHAR(64), pref_value TEXT,"
                        + "PRIMARY KEY(uuid, pref_key))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_history ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "uuid VARCHAR(36) NOT NULL,"
                        + "player_name VARCHAR(64),"
                        + "action VARCHAR(16) NOT NULL,"
                        + "timestamp BIGINT NOT NULL,"
                        + "server VARCHAR(64),"
                        + "reason TEXT,"
                        + "duration_ms BIGINT DEFAULT 0,"
                        + "INDEX idx_hist_uuid (uuid),"
                        + "INDEX idx_hist_ts (timestamp))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_stats ("
                        + "uuid VARCHAR(36) PRIMARY KEY,"
                        + "total_ms BIGINT DEFAULT 0,"
                        + "vanish_count INT DEFAULT 0,"
                        + "longest_ms BIGINT DEFAULT 0)");
            }
            // PostgreSQL uses SERIAL / SEQUENCE instead of AUTO_INCREMENT
            if (type.equals("postgresql")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS vpp_rule_presets ("
                            + "uuid VARCHAR(36), preset_name VARCHAR(64), rule_key VARCHAR(64), rule_value TEXT,"
                            + "PRIMARY KEY(uuid, preset_name, rule_key))");
                    st.execute("CREATE TABLE IF NOT EXISTS vpp_preferences ("
                            + "uuid VARCHAR(36), pref_key VARCHAR(64), pref_value TEXT,"
                            + "PRIMARY KEY(uuid, pref_key))");
                    st.execute("CREATE SEQUENCE IF NOT EXISTS vpp_history_id_seq");
                    st.execute("CREATE TABLE IF NOT EXISTS vpp_history ("
                            + "id BIGINT DEFAULT nextval('vpp_history_id_seq') PRIMARY KEY,"
                            + "uuid VARCHAR(36) NOT NULL,"
                            + "player_name VARCHAR(64),"
                            + "action VARCHAR(16) NOT NULL,"
                            + "timestamp BIGINT NOT NULL,"
                            + "server VARCHAR(64),"
                            + "reason TEXT,"
                            + "duration_ms BIGINT DEFAULT 0)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_hist_uuid ON vpp_history(uuid)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_hist_ts  ON vpp_history(timestamp)");
                    st.execute("CREATE TABLE IF NOT EXISTS vpp_stats ("
                            + "uuid VARCHAR(36) PRIMARY KEY,"
                            + "total_ms BIGINT DEFAULT 0,"
                            + "vanish_count INT DEFAULT 0,"
                            + "longest_ms BIGINT DEFAULT 0)");
                }
            }
        }

        // Update schema version
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE vpp_schema_version SET version = ?")) {
            ps.setInt(1, CURRENT_SCHEMA_VERSION);
            ps.executeUpdate();
        }
        plugin.getLogger().info("SQL schema migrated to v" + CURRENT_SCHEMA_VERSION + ".");
    }

    private void addColumnIfNotExists(Statement st, String table, String column, String type) {
        try {
            if (this.type.equals("postgresql")) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type);
            } else {
                // H2/MySQL: try to add, catch if it already exists
                try {
                    st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                } catch (SQLException e) {
                    if (e.getMessage().contains("Duplicate column") || e.getMessage().contains("already exists")) {
                        plugin.getLogger().fine("Column " + table + "." + column + " already exists, skipping.");
                    } else {
                        throw e;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error adding column " + table + "." + column + ": " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        } else if (dataSource instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    /** Helper: logs severe errors and notifies staff if connection issues persist */
    private void handleDatabaseError(SQLException e) {
        plugin.getLogger().severe("Database connection error: " + e.getMessage());
        long now = System.currentTimeMillis();
        if (now - lastNotificationTime > NOTIFICATION_COOLDOWN) {
            lastNotificationTime = now;
            plugin.getServer().getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("vanishpp.admin") || p.isOp())
                    .forEach(p -> p.sendMessage(net.kyori.adventure.text.Component.text()
                            .content("§c[Vanish++] Database connection failed! Check logs for details.")
                            .build()));
            plugin.getLogger().warning("[ADMIN ALERT] Database connection issue — staff have been notified.");
        }
    }

    @Override
    public boolean isVanished(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM vpp_vanished WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            handleDatabaseError(e);
            return false;
        }
    }

    @Override
    public void setVanished(UUID uuid, boolean vanished) {
        String query = vanished ? "INSERT IGNORE INTO vpp_vanished (uuid) VALUES (?)"
                : "DELETE FROM vpp_vanished WHERE uuid = ?";
        if (type.equals("postgresql") && vanished) {
            query = "INSERT INTO vpp_vanished (uuid) VALUES (?) ON CONFLICT (uuid) DO NOTHING";
        }

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            handleDatabaseError(e);
        }
    }

    @Override
    public Set<UUID> getVanishedPlayers() {
        Set<UUID> players = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM vpp_vanished");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    players.add(UUID.fromString(rs.getString("uuid")));
                } catch (IllegalArgumentException e2) {
                    plugin.getLogger().warning("Ignoring invalid UUID in database: " + rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            handleDatabaseError(e);
        }
        return players;
    }

    @Override
    public boolean getRule(UUID uuid, String rule, boolean defaultValue) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("SELECT rule_value FROM vpp_rules WHERE uuid = ? AND rule_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rule);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Boolean.parseBoolean(rs.getString("rule_value"));
                }
            }
        } catch (SQLException e) {
            handleDatabaseError(e);
        }
        return defaultValue;
    }

    @Override
    public void setRule(UUID uuid, String rule, Object value) {
        String query = "REPLACE INTO vpp_rules (uuid, rule_key, rule_value) VALUES (?, ?, ?)";
        if (type.equals("postgresql")) {
            query = "INSERT INTO vpp_rules (uuid, rule_key, rule_value) VALUES (?, ?, ?) ON CONFLICT (uuid, rule_key) DO UPDATE SET rule_value = EXCLUDED.rule_value";
        }

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rule);
            ps.setString(3, value.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getRules(UUID uuid) {
        Map<String, Object> rules = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("SELECT rule_key, rule_value FROM vpp_rules WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String val = rs.getString("rule_value");
                    // Parse boolean strings to Boolean so the return type is consistent with YamlStorage
                    Object parsed = "true".equalsIgnoreCase(val) ? Boolean.TRUE
                            : "false".equalsIgnoreCase(val) ? Boolean.FALSE
                            : val;
                    rules.put(rs.getString("rule_key"), parsed);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
        return rules;
    }

    @Override
    public boolean hasAcknowledged(UUID uuid, String notificationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM vpp_acknowledgements WHERE uuid = ? AND notification_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, notificationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void addAcknowledgement(UUID uuid, String notificationId) {
        String query = type.equals("postgresql")
                ? "INSERT INTO vpp_acknowledgements (uuid, notification_id) VALUES (?, ?) ON CONFLICT (uuid, notification_id) DO NOTHING"
                : "INSERT IGNORE INTO vpp_acknowledgements (uuid, notification_id) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, notificationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }

    @Override
    public int getVanishLevel(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT level FROM vpp_levels WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt("level");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
        return 1;
    }

    @Override
    public void setVanishLevel(UUID uuid, int level) {
        String query = "REPLACE INTO vpp_levels (uuid, level) VALUES (?, ?)";
        if (type.equals("postgresql")) {
            query = "INSERT INTO vpp_levels (uuid, level) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET level = EXCLUDED.level";
        }

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, level);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }

    // ── Rule Presets ──────────────────────────────────────────────────────────

    @Override
    public void saveRulePreset(UUID uuid, String presetName, Map<String, Boolean> rules) {
        // Delete existing rows for this preset first, then re-insert
        String del = "DELETE FROM vpp_rule_presets WHERE uuid = ? AND preset_name = ?";
        String ins = type.equals("postgresql")
                ? "INSERT INTO vpp_rule_presets (uuid, preset_name, rule_key, rule_value) VALUES (?,?,?,?)"
                  + " ON CONFLICT (uuid, preset_name, rule_key) DO UPDATE SET rule_value = EXCLUDED.rule_value"
                : "REPLACE INTO vpp_rule_presets (uuid, preset_name, rule_key, rule_value) VALUES (?,?,?,?)";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement d = conn.prepareStatement(del)) {
                d.setString(1, uuid.toString()); d.setString(2, presetName); d.executeUpdate();
            }
            try (PreparedStatement i = conn.prepareStatement(ins)) {
                for (Map.Entry<String, Boolean> e : rules.entrySet()) {
                    i.setString(1, uuid.toString()); i.setString(2, presetName);
                    i.setString(3, e.getKey()); i.setString(4, e.getValue().toString());
                    i.addBatch();
                }
                i.executeBatch();
            }
        } catch (SQLException e) { handleDatabaseError(e); }
    }

    @Override
    public Map<String, Boolean> loadRulePreset(UUID uuid, String presetName) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rule_key, rule_value FROM vpp_rule_presets WHERE uuid = ? AND preset_name = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, presetName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Boolean> result = new LinkedHashMap<>();
                while (rs.next())
                    result.put(rs.getString("rule_key"), Boolean.parseBoolean(rs.getString("rule_value")));
                return result.isEmpty() ? null : result;
            }
        } catch (SQLException e) { handleDatabaseError(e); return null; }
    }

    @Override
    public Set<String> listRulePresets(UUID uuid) {
        Set<String> names = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT preset_name FROM vpp_rule_presets WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString("preset_name"));
            }
        } catch (SQLException e) { handleDatabaseError(e); }
        return names;
    }

    @Override
    public void deleteRulePreset(UUID uuid, String presetName) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM vpp_rule_presets WHERE uuid = ? AND preset_name = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, presetName);
            ps.executeUpdate();
        } catch (SQLException e) { handleDatabaseError(e); }
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    @Override
    public boolean getAutoVanishOnJoin(UUID uuid) {
        return getPref(uuid, "auto_vanish_join", "false").equalsIgnoreCase("true");
    }

    @Override
    public void setAutoVanishOnJoin(UUID uuid, boolean value) {
        setPref(uuid, "auto_vanish_join", String.valueOf(value));
    }

    private String getPref(UUID uuid, String key, String def) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pref_value FROM vpp_preferences WHERE uuid = ? AND pref_key = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("pref_value");
            }
        } catch (SQLException e) { handleDatabaseError(e); }
        return def;
    }

    private void setPref(UUID uuid, String key, String value) {
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_preferences (uuid,pref_key,pref_value) VALUES(?,?,?) ON CONFLICT (uuid,pref_key) DO UPDATE SET pref_value=EXCLUDED.pref_value"
                : "REPLACE INTO vpp_preferences (uuid,pref_key,pref_value) VALUES(?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString()); ps.setString(2, key); ps.setString(3, value);
            ps.executeUpdate();
        } catch (SQLException e) { handleDatabaseError(e); }
    }

    // ── Vanish History ────────────────────────────────────────────────────────

    @Override
    public void addHistoryEntry(VanishHistoryEntry entry) {
        String q = "INSERT INTO vpp_history (uuid,player_name,action,timestamp,server,reason,duration_ms)"
                + " VALUES (?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, entry.getPlayerUuid().toString());
            ps.setString(2, entry.getPlayerName());
            ps.setString(3, entry.getAction().name());
            ps.setLong(4, entry.getTimestamp().toEpochMilli());
            ps.setString(5, entry.getServer());
            ps.setString(6, entry.getReason() != null ? entry.getReason() : "");
            ps.setLong(7, entry.getDurationMs());
            ps.executeUpdate();
        } catch (SQLException e) { handleDatabaseError(e); }
    }

    @Override
    public List<VanishHistoryEntry> getPlayerHistory(UUID uuid, int page, int perPage) {
        String q = "SELECT * FROM vpp_history WHERE uuid = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?";
        return queryHistory(q, uuid.toString(), perPage, (page - 1) * perPage);
    }

    @Override
    public List<VanishHistoryEntry> getAllHistory(int page, int perPage) {
        String q = "SELECT * FROM vpp_history ORDER BY timestamp DESC LIMIT ? OFFSET ?";
        return queryHistory(q, null, perPage, (page - 1) * perPage);
    }

    private List<VanishHistoryEntry> queryHistory(String query, String uuidFilter, int limit, int offset) {
        List<VanishHistoryEntry> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            int idx = 1;
            if (uuidFilter != null) ps.setString(idx++, uuidFilter);
            ps.setInt(idx++, limit);
            ps.setInt(idx, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new VanishHistoryEntry(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("player_name"),
                            VanishHistoryEntry.Action.valueOf(rs.getString("action")),
                            java.time.Instant.ofEpochMilli(rs.getLong("timestamp")),
                            rs.getString("server"),
                            rs.getString("reason"),
                            rs.getLong("duration_ms")));
                }
            }
        } catch (SQLException e) { handleDatabaseError(e); }
        return list;
    }

    @Override
    public int pruneHistory(int retentionDays) {
        if (retentionDays <= 0) return 0;
        long cutoff = java.time.Instant.now().minusSeconds((long) retentionDays * 86400).toEpochMilli();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM vpp_history WHERE timestamp < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) { handleDatabaseError(e); return 0; }
    }

    // ── Vanish Statistics ─────────────────────────────────────────────────────

    @Override
    public VanishStats getStats(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT total_ms, vanish_count, longest_ms FROM vpp_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return new VanishStats(rs.getLong("total_ms"),
                            rs.getInt("vanish_count"), rs.getLong("longest_ms"));
            }
        } catch (SQLException e) { handleDatabaseError(e); }
        return VanishStats.empty();
    }

    @Override
    public void recordVanishSession(UUID uuid, long durationMs) {
        if (durationMs <= 0) return;
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_stats (uuid,total_ms,vanish_count,longest_ms) VALUES(?,?,1,?)"
                  + " ON CONFLICT (uuid) DO UPDATE SET total_ms=vpp_stats.total_ms+EXCLUDED.total_ms,"
                  + " vanish_count=vpp_stats.vanish_count+1,"
                  + " longest_ms=GREATEST(vpp_stats.longest_ms,EXCLUDED.longest_ms)"
                : "INSERT INTO vpp_stats (uuid,total_ms,vanish_count,longest_ms) VALUES(?,?,1,?)"
                  + " ON DUPLICATE KEY UPDATE total_ms=total_ms+VALUES(total_ms),"
                  + " vanish_count=vanish_count+1,"
                  + " longest_ms=GREATEST(longest_ms,VALUES(longest_ms))";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, durationMs);
            ps.setLong(3, durationMs);
            ps.executeUpdate();
        } catch (SQLException e) { handleDatabaseError(e); }
    }

    // ── Existing methods ──────────────────────────────────────────────────────

    @Override
    public Set<UUID> getAllKnownPlayers() {
        Set<UUID> uuids = new HashSet<>();
        String[] tables = {"vpp_vanished", "vpp_rules", "vpp_levels", "vpp_acknowledgements"};
        for (String table : tables) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT uuid FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { uuids.add(UUID.fromString(rs.getString("uuid"))); }
                    catch (IllegalArgumentException ignored) {}
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Migration check failed on " + table + ": " + e.getMessage());
            }
        }
        return uuids;
    }

    @Override
    public Set<String> getAcknowledgements(UUID uuid) {
        Set<String> ids = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT notification_id FROM vpp_acknowledgements WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("notification_id"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
        return ids;
    }

    @Override
    public void removePlayerData(UUID uuid) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String table : new String[]{"vpp_rules","vpp_acknowledgements","vpp_levels",
                                                 "vpp_rule_presets","vpp_preferences","vpp_stats"}) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                        ps.setString(1, uuid.toString()); ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                plugin.getLogger().severe("Database error during removePlayerData: " + e.getMessage());
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }
}
