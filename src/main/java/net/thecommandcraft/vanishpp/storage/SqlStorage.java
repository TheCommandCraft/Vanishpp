package net.thecommandcraft.vanishpp.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.thecommandcraft.vanishpp.Vanishpp;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class SqlStorage implements StorageProvider {

    private final Vanishpp plugin;
    private DataSource dataSource;
    private final String type;

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
                config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSSL);
            } else if (type.equals("postgresql")) {
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
                // Seed schema version if the table is empty
                st.execute("INSERT " + (type.equals("postgresql") ? "" : "IGNORE ") + "INTO vpp_schema_version (version) VALUES (1)" + (type.equals("postgresql") ? " ON CONFLICT DO NOTHING" : ""));
            }
            runSchemaMigrations(conn);
        }
    }

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private void runSchemaMigrations(Connection conn) throws SQLException {
        int version;
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM vpp_schema_version");
             ResultSet rs = ps.executeQuery()) {
            version = rs.next() ? rs.getInt("version") : 0;
        }
        if (version >= CURRENT_SCHEMA_VERSION) return;
        // Future schema migrations go here as additional cases:
        // case 1: ALTER TABLE ... ; update version to 2; etc.
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE vpp_schema_version SET version = ?")) {
            ps.setInt(1, CURRENT_SCHEMA_VERSION);
            ps.executeUpdate();
        }
        plugin.getLogger().info("SQL schema migrated to v" + CURRENT_SCHEMA_VERSION + ".");
    }

    @Override
    public void shutdown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        } else if (dataSource instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
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
            plugin.getLogger().severe("Database error: " + e.getMessage());
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
            plugin.getLogger().severe("Database error: " + e.getMessage());
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
            plugin.getLogger().severe("Database error: " + e.getMessage());
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
            plugin.getLogger().severe("Database error: " + e.getMessage());
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
                ? "INSERT INTO vpp_acknowledgements (uuid, notification_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
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

    @Override
    public void removePlayerData(UUID uuid) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_rules WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_acknowledgements WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_levels WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }
}
