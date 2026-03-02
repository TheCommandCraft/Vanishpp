package net.thecommandcraft.vanishpp.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.thecommandcraft.vanishpp.Vanishpp;

import java.sql.*;
import java.util.*;

public class SqlStorage implements StorageProvider {

    private final Vanishpp plugin;
    private HikariDataSource dataSource;
    private final String type;

    public SqlStorage(Vanishpp plugin, String type) {
        this.plugin = plugin;
        this.type = type.toLowerCase();
    }

    @Override
    public void init() throws Exception {
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

        try (Connection conn = dataSource.getConnection()) {
            String createVanishTable = "CREATE TABLE IF NOT EXISTS vpp_vanished (uuid VARCHAR(36) PRIMARY KEY)";
            String createRulesTable = "CREATE TABLE IF NOT EXISTS vpp_rules (uuid VARCHAR(36), rule_key VARCHAR(64), rule_value TEXT, PRIMARY KEY(uuid, rule_key))";
            String createLevelsTable = "CREATE TABLE IF NOT EXISTS vpp_levels (uuid VARCHAR(36) PRIMARY KEY, level INT)";

            try (Statement st = conn.createStatement()) {
                st.execute(createVanishTable);
                st.execute(createRulesTable);
                st.execute(createLevelsTable);
            }
        }
    }

    @Override
    public void shutdown() {
        if (dataSource != null) {
            dataSource.close();
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
                players.add(UUID.fromString(rs.getString("uuid")));
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
                    rules.put(rs.getString("rule_key"), rs.getString("rule_value"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
        return rules;
    }

    @Override
    public boolean hasAcknowledged(UUID uuid, String notificationId) {
        // Acknowledgements are intentionally transient or server-local for now in the
        // SQL implementation
        // to avoid table bloat, unless specified otherwise.
        return false;
    }

    @Override
    public void addAcknowledgement(UUID uuid, String notificationId) {
        // Local only for now.
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
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_levels WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }
}
