package net.thecommandcraft.vanishpp.velocity.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * SQL storage backend for the Velocity proxy plugin.
 * Uses the same vpp_* schema as the Paper plugin's SqlStorage.
 * No Bukkit/Paper dependency — uses slf4j Logger and VelocityConfigManager directly.
 */
public class ProxySqlStorage {

    private final VelocityConfigManager config;
    private final Logger logger;
    private DataSource dataSource;
    private String type;

    private volatile long lastNotificationTime = 0;
    private static final long NOTIFICATION_COOLDOWN = 300_000;
    private static final int CURRENT_SCHEMA_VERSION = 2;

    public ProxySqlStorage(VelocityConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void init() throws Exception {
        this.type = config.getStorageType().toLowerCase();
        if (!type.equals("mysql") && !type.equals("postgresql")) {
            throw new IllegalArgumentException("ProxySqlStorage requires MYSQL or POSTGRESQL, got: " + type);
        }

        HikariConfig hc = new HikariConfig();
        String host     = config.getDbHost();
        int    port     = config.getDbPort();
        String database = config.getDbName();
        String username = config.getDbUser();
        String password = config.getDbPass();
        boolean useSSL  = config.getDbSsl();

        if (type.equals("mysql")) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSSL);
        } else {
            Class.forName("org.postgresql.Driver");
            hc.setDriverClassName("org.postgresql.Driver");
            hc.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database + "?ssl=" + useSSL);
        }
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(config.getPoolSize());
        hc.setConnectionTimeout(5000);
        hc.setPoolName("VanishPP-Velocity");

        this.dataSource = new HikariDataSource(hc);

        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS vpp_vanished (uuid VARCHAR(36) PRIMARY KEY)");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_rules (uuid VARCHAR(36), rule_key VARCHAR(64), rule_value TEXT, PRIMARY KEY(uuid, rule_key))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_levels (uuid VARCHAR(36) PRIMARY KEY, level INT)");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_acknowledgements (uuid VARCHAR(36), notification_id VARCHAR(128), PRIMARY KEY(uuid, notification_id))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_schema_version (version INT PRIMARY KEY)");
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM vpp_schema_version");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (PreparedStatement ins = conn.prepareStatement("INSERT INTO vpp_schema_version (version) VALUES (?)")) {
                        ins.setInt(1, 1);
                        ins.executeUpdate();
                    }
                }
            }
            runSchemaMigrations(conn);
        }
        logger.info("ProxySqlStorage connected to {} database.", type);
    }

    private void runSchemaMigrations(Connection conn) throws SQLException {
        int version;
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM vpp_schema_version");
             ResultSet rs = ps.executeQuery()) {
            version = rs.next() ? rs.getInt("version") : 0;
        }
        if (version >= CURRENT_SCHEMA_VERSION) return;

        if (version < 2) {
            try (Statement st = conn.createStatement()) {
                addColumnIfNotExists(st, "vpp_vanished", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                addColumnIfNotExists(st, "vpp_rules", "updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                addColumnIfNotExists(st, "vpp_levels", "updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE vpp_schema_version SET version = ?")) {
            ps.setInt(1, CURRENT_SCHEMA_VERSION);
            ps.executeUpdate();
        }
        logger.info("SQL schema migrated to v{}.", CURRENT_SCHEMA_VERSION);
    }

    private void addColumnIfNotExists(Statement st, String table, String column, String colType) {
        try {
            if (type.equals("postgresql")) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + colType);
            } else {
                try {
                    st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + colType);
                } catch (SQLException e) {
                    if (e.getMessage().contains("Duplicate column") || e.getMessage().contains("already exists")) {
                        logger.debug("Column {}.{} already exists, skipping.", table, column);
                    } else {
                        throw e;
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Error adding column {}.{}: {}", table, column, e.getMessage());
        }
    }

    public void shutdown() {
        if (dataSource instanceof HikariDataSource hds) hds.close();
    }

    // ── Vanish state ─────────────────────────────────────────────────────────

    public boolean isVanished(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM vpp_vanished WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { handleDbError(e); return false; }
    }

    public void setVanished(UUID uuid, boolean vanished) {
        String q = vanished
                ? (type.equals("postgresql")
                   ? "INSERT INTO vpp_vanished (uuid) VALUES (?) ON CONFLICT (uuid) DO NOTHING"
                   : "INSERT IGNORE INTO vpp_vanished (uuid) VALUES (?)")
                : "DELETE FROM vpp_vanished WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { handleDbError(e); }
    }

    public Set<UUID> getVanishedPlayers() {
        Set<UUID> players = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM vpp_vanished");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try { players.add(UUID.fromString(rs.getString("uuid"))); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) { handleDbError(e); }
        return players;
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    public boolean getRule(UUID uuid, String rule, boolean defaultValue) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rule_value FROM vpp_rules WHERE uuid = ? AND rule_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rule);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Boolean.parseBoolean(rs.getString("rule_value"));
            }
        } catch (SQLException e) { handleDbError(e); }
        return defaultValue;
    }

    public void setRule(UUID uuid, String rule, Object value) {
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_rules (uuid, rule_key, rule_value) VALUES (?, ?, ?) ON CONFLICT (uuid, rule_key) DO UPDATE SET rule_value = EXCLUDED.rule_value"
                : "REPLACE INTO vpp_rules (uuid, rule_key, rule_value) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rule);
            ps.setString(3, value.toString());
            ps.executeUpdate();
        } catch (SQLException e) { logger.error("DB error: {}", e.getMessage()); }
    }

    public Map<String, Object> getRules(UUID uuid) {
        Map<String, Object> rules = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rule_key, rule_value FROM vpp_rules WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String val = rs.getString("rule_value");
                    Object parsed = "true".equalsIgnoreCase(val) ? Boolean.TRUE
                                  : "false".equalsIgnoreCase(val) ? Boolean.FALSE
                                  : val;
                    rules.put(rs.getString("rule_key"), parsed);
                }
            }
        } catch (SQLException e) { logger.error("DB error: {}", e.getMessage()); }
        return rules;
    }

    // ── Vanish levels ─────────────────────────────────────────────────────────

    public int getVanishLevel(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT level FROM vpp_levels WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("level");
            }
        } catch (SQLException e) { logger.error("DB error: {}", e.getMessage()); }
        return 1;
    }

    public void setVanishLevel(UUID uuid, int level) {
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_levels (uuid, level) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET level = EXCLUDED.level"
                : "REPLACE INTO vpp_levels (uuid, level) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, level);
            ps.executeUpdate();
        } catch (SQLException e) { logger.error("DB error: {}", e.getMessage()); }
    }

    // ── Acknowledgements ──────────────────────────────────────────────────────

    public boolean hasAcknowledged(UUID uuid, String notificationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM vpp_acknowledgements WHERE uuid = ? AND notification_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, notificationId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { logger.error("DB error: {}", e.getMessage()); return false; }
    }

    public void addAcknowledgement(UUID uuid, String notificationId) {
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_acknowledgements (uuid, notification_id) VALUES (?, ?) ON CONFLICT (uuid, notification_id) DO NOTHING"
                : "INSERT IGNORE INTO vpp_acknowledgements (uuid, notification_id) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, notificationId);
            ps.executeUpdate();
        } catch (SQLException e) { logger.error("DB error: {}", e.getMessage()); }
    }

    public Set<String> getAcknowledgements(UUID uuid) {
        Set<String> ids = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT notification_id FROM vpp_acknowledgements WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("notification_id"));
            }
        } catch (SQLException e) { logger.error("DB error: {}", e.getMessage()); }
        return ids;
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private void handleDbError(SQLException e) {
        logger.error("Database error: {}", e.getMessage());
        long now = System.currentTimeMillis();
        if (now - lastNotificationTime > NOTIFICATION_COOLDOWN) {
            lastNotificationTime = now;
            logger.warn("[ADMIN ALERT] Repeated database connection failures — check your storage configuration.");
        }
    }
}
