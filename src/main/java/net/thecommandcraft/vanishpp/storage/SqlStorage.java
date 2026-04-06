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

    private static final int CURRENT_SCHEMA_VERSION = 2;

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
                // Schema v2: Add created_at and updated_at columns for audit trails
                // Use a helper method to safely add columns that might already exist
                addColumnIfNotExists(st, "vpp_vanished", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                addColumnIfNotExists(st, "vpp_rules", "updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                addColumnIfNotExists(st, "vpp_levels", "updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
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
        // Clears rule customizations, acknowledgements, and permission levels.
        // Vanish state is NOT cleared here — it's managed separately via vanish/unvanish commands.
        // This separation allows admins to remove a player's rule cache without affecting vanish status.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Delete rules
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_rules WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
                // Delete acknowledgements
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_acknowledgements WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
                // Delete permission levels
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_levels WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
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
