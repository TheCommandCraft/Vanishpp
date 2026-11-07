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
    private volatile DataSource dataSource;
    private volatile boolean initialized;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean recoveryScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean recovering;
    private volatile Thread recoveryOwner;
    private Runnable cancelRecoveryRetry = () -> {};
    private long recoveryDelay = 100L;
    private final String type;
    private final org.bukkit.configuration.file.FileConfiguration settings;
    private volatile long lastNotificationTime = 0;
    private static final long NOTIFICATION_COOLDOWN = 300000; // 5 minutes between notifications

    public volatile boolean writeSuspended = false;
    public String dbVersion = null;

    public SqlStorage(Vanishpp plugin, String type) { this(plugin, type, plugin.getConfig()); }

    public SqlStorage(Vanishpp plugin, String type, org.bukkit.configuration.file.FileConfiguration settings) {
        this.plugin = plugin;
        this.type = type.toLowerCase();
        this.settings = settings;
    }

    /** Package-private constructor for testing with a pre-configured DataSource (e.g. H2 in-memory). */
    SqlStorage(Vanishpp plugin, DataSource dataSource) {
        this.plugin = plugin;
        this.type = "mysql";
        this.dataSource = dataSource;
        this.settings = plugin.getConfig();
    }

    @Override
    public synchronized void init() throws Exception {
        if (closed) throw new IllegalStateException("Storage is closed");
        if (this.dataSource == null) {
            HikariConfig config = new HikariConfig();
            String host = settings.getString("storage.mysql.host", "localhost");
            int port = settings.getInt("storage.mysql.port", 3306);
            String database = settings.getString("storage.mysql.database", "vanishpp");
            String username = settings.getString("storage.mysql.username", "root");
            String password = settings.getString("storage.mysql.password", "");
            boolean useSSL = settings.getBoolean("storage.mysql.use-ssl", false);

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
            config.setMaximumPoolSize(settings.getInt("storage.mysql.pool-size", 10));
            config.setConnectionTimeout(5000);

            this.dataSource = new HikariDataSource(config);
        }

        try (SqlConnection conn = borrowConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS vpp_vanished (uuid VARCHAR(36) PRIMARY KEY)");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_rules (uuid VARCHAR(36), rule_key VARCHAR(64), rule_value TEXT, PRIMARY KEY(uuid, rule_key))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_levels (uuid VARCHAR(36) PRIMARY KEY, level INT)");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_acknowledgements (uuid VARCHAR(36), notification_id VARCHAR(128), PRIMARY KEY(uuid, notification_id))");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_schema_version (version INT PRIMARY KEY)");
                st.execute("CREATE TABLE IF NOT EXISTS vpp_plugin_version (id INT PRIMARY KEY, version VARCHAR(32) NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
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
            runSchemaMigrations(conn.connection);
            checkPluginVersionStamp(conn.connection);
        }
        initialized = true;
    }

    private Connection connection() throws SQLException {
        DataSource source = dataSource;
        if (closed || source == null) throw new SQLException("SQL storage is unavailable");
        return source.getConnection();
    }

    private static final class Migration {
        final Connection connection;
        boolean rollbackOnly;
        Migration(Connection connection) { this.connection = connection; }
    }

    /** A borrowed migration connection cannot be closed or committed by an individual setter. */
    private static final class SqlConnection implements AutoCloseable {
        final Connection connection;
        final Migration migration;
        SqlConnection(Connection connection, Migration migration) { this.connection = connection; this.migration = migration; }
        PreparedStatement prepareStatement(String sql) throws SQLException { return connection.prepareStatement(sql); }
        PreparedStatement prepareStatement(String sql, int type, int concurrency) throws SQLException {
            return connection.prepareStatement(sql, type, concurrency);
        }
        Statement createStatement() throws SQLException { return connection.createStatement(); }
        DatabaseMetaData getMetaData() throws SQLException { return connection.getMetaData(); }
        void setAutoCommit(boolean enabled) throws SQLException { if (migration == null) connection.setAutoCommit(enabled); }
        void commit() throws SQLException { if (migration == null) connection.commit(); }
        void rollback() throws SQLException {
            if (migration == null) connection.rollback();
            else migration.rollbackOnly = true;
        }
        @Override public void close() throws SQLException { if (migration == null) connection.close(); }
    }

    private final ThreadLocal<Migration> migration = new ThreadLocal<>();

    private SqlConnection borrowConnection() throws SQLException {
        Migration current = migration.get();
        return new SqlConnection(current == null ? connection() : current.connection, current);
    }

    private void failMigration(SQLException failure) {
        if (migration.get() != null) throw new IllegalStateException("Storage migration failed", failure);
    }

    public void migrateAtomically(Runnable copy) {
        ensureInitialized();
        if (writeSuspended) throw new IllegalStateException("Cannot migrate while SQL writes are suspended");
        migrationTransaction(copy);
    }

    /** Gives all source reads one consistent snapshot and propagates unavailable categories. */
    public void readForMigration(Runnable copy) {
        ensureInitialized();
        migrationTransaction(copy);
    }

    private void migrationTransaction(Runnable copy) {
        if (migration.get() != null) { copy.run(); return; }
        try (Connection connection = connection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            Migration transaction = new Migration(connection);
            migration.set(transaction);
            try {
                copy.run();
                if (transaction.rollbackOnly) throw new IllegalStateException("Migration was rolled back");
                connection.commit();
            } catch (RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            } finally { migration.remove(); }
        } catch (SQLException failure) { throw new IllegalStateException("Could not commit storage migration", failure); }
    }

    private void ensureInitialized() {
        if (initialized && !closed && (!recovering || recoveryOwner == Thread.currentThread())) return;
        if (!closed && recoveryScheduled.compareAndSet(false, true)) {
            recovering = true;
            plugin.getVanishScheduler().runAsync(() -> {
                try {
                    init();
                    if (plugin.getStorageProvider() == this) plugin.storageRecovered(this);
                    else finishRecovery(() -> {});
                } catch (Exception e) { recoveryFailed(e); }
            });
        }
        throw new IllegalStateException("SQL storage is unavailable; initialization and recovery are asynchronous");
    }

    public boolean isRecovering() { return recovering; }

    /** The ordered worker alone may access an initialized provider before readiness is published. */
    public void finishRecovery(Runnable restore) {
        recoveryOwner = Thread.currentThread();
        try {
            restore.run();
            recovering = false;
            recoveryDelay = 100L;
            cancelRecoveryRetry.run();
            recoveryScheduled.set(false);
        } catch (RuntimeException e) { recoveryFailed(e); }
        finally { recoveryOwner = null; }
    }

    private synchronized void recoveryFailed(Exception failure) {
        initialized = false;
        recovering = false;
        recoveryScheduled.set(false);
        plugin.getLogger().warning("SQL recovery will retry: " + failure.getMessage());
        cancelRecoveryRetry.run();
        if (!closed) {
            cancelRecoveryRetry = plugin.getVanishScheduler().startLater(() -> {
                try { ensureInitialized(); } catch (IllegalStateException unavailable) { /* retry was queued */ }
            }, recoveryDelay);
            recoveryDelay = Math.min(600L, recoveryDelay * 2L);
        }
    }

    private void checkPluginVersionStamp(Connection conn) throws SQLException {
        String current = plugin.getDescription().getVersion();
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM vpp_plugin_version WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                // Fresh install or first run after upgrade — write the stamp
                String ins = type.equals("postgresql")
                        ? "INSERT INTO vpp_plugin_version (id, version) VALUES (1, ?) ON CONFLICT (id) DO NOTHING"
                        : "INSERT IGNORE INTO vpp_plugin_version (id, version) VALUES (1, ?)";
                try (PreparedStatement ip = conn.prepareStatement(ins)) {
                    ip.setString(1, current);
                    ip.executeUpdate();
                }
                return;
            }
            String stored = rs.getString("version");
            if (compareVersions(stored, current) > 0) {
                // DB was written by a newer version — suspend writes
                writeSuspended = true;
                dbVersion = stored;
            } else {
                // Same or older — update stamp to current (keep highest ever seen)
                try (PreparedStatement up = conn.prepareStatement(
                        "UPDATE vpp_plugin_version SET version = ? WHERE id = 1")) {
                    up.setString(1, current);
                    up.executeUpdate();
                }
            }
        }
    }

    /** Compares two semantic version strings (e.g. "1.1.8-beta" vs "1.2.0"). Returns positive if a > b. */
    static int compareVersions(String a, String b) {
        int[] pa = parseSemver(a), pb = parseSemver(b);
        for (int i = 0; i < 3; i++) {
            int diff = pa[i] - pb[i];
            if (diff != 0) return diff;
        }
        return 0;
    }

    private static int[] parseSemver(String v) {
        String clean = v.contains("-") ? v.substring(0, v.indexOf('-')) : v;
        String[] parts = clean.split("\\.", -1);
        int[] result = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    /** Returns row counts for each vpp_* table — used by the downgrade guard command. */
    public Map<String, Long> getDataSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        String[] tables = {"vpp_vanished", "vpp_rules", "vpp_levels",
                           "vpp_rule_presets", "vpp_history", "vpp_stats"};
        try (SqlConnection conn = borrowConnection()) {
            for (String table : tables) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet rs = ps.executeQuery()) {
                    summary.put(table, rs.next() ? rs.getLong(1) : 0L);
                } catch (SQLException e) {
                    failMigration(e);
                    summary.put(table, -1L);
                }
            }
        } catch (SQLException e) {
            failMigration(e);
            handleDatabaseError(e);
        }
        return summary;
    }

    /** Wipes all player data and resets the version stamp. Lifts write-suspend. */
    public void resetDatabase() {
        String[] tables = {"vpp_vanished", "vpp_rules", "vpp_levels", "vpp_acknowledgements",
                           "vpp_rule_presets", "vpp_preferences", "vpp_history", "vpp_stats"};
        try (SqlConnection conn = borrowConnection();
             Statement st = conn.createStatement()) {
            for (String table : tables) {
                st.execute("DELETE FROM " + table);
            }
            try (PreparedStatement up = conn.prepareStatement(
                    "UPDATE vpp_plugin_version SET version = ? WHERE id = 1")) {
                up.setString(1, plugin.getDescription().getVersion());
                up.executeUpdate();
            }
        } catch (SQLException e) {
            failMigration(e);
            handleDatabaseError(e);
            return;
        }
        writeSuspended = false;
        dbVersion = null;
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
            } else {
                try (Statement st = conn.createStatement()) {
                    // v3: rule presets, preferences, history, stats tables (MySQL / H2)
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
                    failMigration(e);
                    if (e.getMessage().contains("Duplicate column") || e.getMessage().contains("already exists")) {
                        plugin.getLogger().fine("Column " + table + "." + column + " already exists, skipping.");
                    } else {
                        throw e;
                    }
                }
            }
        } catch (SQLException e) {
            failMigration(e);
            plugin.getLogger().warning("Error adding column " + table + "." + column + ": " + e.getMessage());
        }
    }

    @Override
    public synchronized void shutdown() {
        closed = true;
        cancelRecoveryRetry.run();
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
        ensureInitialized();
        try (SqlConnection conn = borrowConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM vpp_vanished WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            failMigration(e);
            handleDatabaseError(e);
            throw new IllegalStateException("Could not read vanish state", e);
        }
    }

    @Override
    public void setVanished(UUID uuid, boolean vanished) {
        ensureInitialized();
        if (writeSuspended) return;
        String query = vanished ? "INSERT IGNORE INTO vpp_vanished (uuid) VALUES (?)"
                : "DELETE FROM vpp_vanished WHERE uuid = ?";
        if (type.equals("postgresql") && vanished) {
            query = "INSERT INTO vpp_vanished (uuid) VALUES (?) ON CONFLICT (uuid) DO NOTHING";
        }

        try (SqlConnection conn = borrowConnection();
                PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e);
            handleDatabaseError(e);
            throw new IllegalStateException("Could not persist vanish state", e);
        }
    }

    @Override
    public Set<UUID> getVanishedPlayers() {
        ensureInitialized();
        Set<UUID> players = new HashSet<>();
        try (SqlConnection conn = borrowConnection();
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
            failMigration(e);
            handleDatabaseError(e);
            throw new IllegalStateException("Could not read vanished players", e);
        }
        return players;
    }

    @Override
    public boolean getRule(UUID uuid, String rule, boolean defaultValue) {
        ensureInitialized();
        try (SqlConnection conn = borrowConnection();
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
            failMigration(e);
            handleDatabaseError(e);
        }
        return defaultValue;
    }

    @Override
    public void setRule(UUID uuid, String rule, Object value) {
        ensureInitialized();
        if (writeSuspended) return;
        try (SqlConnection conn = borrowConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!rule.startsWith(EXPIRY_PREFIX)) writeRule(conn.connection, uuid, EXPIRY_PREFIX + rule, null);
                writeRule(conn.connection, uuid, rule, value);
                conn.commit();
            } catch (SQLException e) {
                failMigration(e);
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            failMigration(e);
            throw new IllegalStateException("Could not save rule", e);
        }
    }

    @Override
    public void setTemporaryRule(UUID uuid, String rule, boolean value, String token) {
        ensureInitialized();
        if (writeSuspended) return;
        try (SqlConnection conn = borrowConnection()) {
            conn.setAutoCommit(false);
            try {
                writeRule(conn.connection, uuid, EXPIRY_PREFIX + rule, token);
                writeRule(conn.connection, uuid, rule, value);
                conn.commit();
            } catch (SQLException e) {
                failMigration(e); conn.rollback(); throw e; }
        } catch (SQLException e) {
            failMigration(e); throw new IllegalStateException("Could not save temporary rule", e); }
    }

    private void writeRule(Connection conn, UUID uuid, String rule, Object value) throws SQLException {
        String query = value == null ? "DELETE FROM vpp_rules WHERE uuid = ? AND rule_key = ?"
                : type.equals("postgresql")
                ? "INSERT INTO vpp_rules (uuid, rule_key, rule_value) VALUES (?, ?, ?) ON CONFLICT (uuid, rule_key) DO UPDATE SET rule_value = EXCLUDED.rule_value"
                : "REPLACE INTO vpp_rules (uuid, rule_key, rule_value) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rule);
            if (value != null) ps.setString(3, value.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean expireRule(UUID uuid, String rule, String token) {
        ensureInitialized();
        if (writeSuspended) return false;
        try (SqlConnection conn = borrowConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT rule_value FROM vpp_rules WHERE uuid = ? AND rule_key = ? FOR UPDATE")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, EXPIRY_PREFIX + rule);
                boolean matches;
                try (ResultSet rs = ps.executeQuery()) { matches = rs.next() && token.equals(rs.getString(1)); }
                if (matches) {
                    writeRule(conn.connection, uuid, rule, Boolean.parseBoolean(token.split(":", 3)[1]));
                    writeRule(conn.connection, uuid, EXPIRY_PREFIX + rule, null);
                }
                conn.commit();
                return matches;
            } catch (SQLException e) {
                failMigration(e);
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            failMigration(e);
            throw new IllegalStateException("Could not expire rule", e);
        }
    }

    @Override
    public Map<String, Object> getRules(UUID uuid) {
        try { return getRecoveryRules(uuid); }
        catch (IllegalStateException e) { plugin.getLogger().warning(e.getMessage()); return new HashMap<>(); }
    }

    @Override
    public Map<String, Object> getRecoveryRules(UUID uuid) {
        ensureInitialized();
        Map<String, Object> rules = new HashMap<>();
        try (SqlConnection conn = borrowConnection();
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
            failMigration(e);
            throw new IllegalStateException("Could not read recovery rules", e);
        }
        return rules;
    }

    @Override
    public boolean hasAcknowledged(UUID uuid, String notificationId) {
        ensureInitialized();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM vpp_acknowledgements WHERE uuid = ? AND notification_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, notificationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            failMigration(e);
            plugin.getLogger().severe("Database error: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void addAcknowledgement(UUID uuid, String notificationId) {
        ensureInitialized();
        if (writeSuspended) return;
        String query = type.equals("postgresql")
                ? "INSERT INTO vpp_acknowledgements (uuid, notification_id) VALUES (?, ?) ON CONFLICT (uuid, notification_id) DO NOTHING"
                : "INSERT IGNORE INTO vpp_acknowledgements (uuid, notification_id) VALUES (?, ?)";
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, notificationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e);
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }

    @Override
    public int getVanishLevel(UUID uuid) {
        try { return getRecoveryVanishLevel(uuid); }
        catch (IllegalStateException e) { plugin.getLogger().warning(e.getMessage()); return 1; }
    }

    @Override
    public int getRecoveryVanishLevel(UUID uuid) {
        ensureInitialized();
        try (SqlConnection conn = borrowConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT level FROM vpp_levels WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt("level");
            }
        } catch (SQLException e) {
            failMigration(e);
            throw new IllegalStateException("Could not read vanish level", e);
        }
        return 1;
    }

    @Override
    public void setVanishLevel(UUID uuid, int level) {
        ensureInitialized();
        if (writeSuspended) return;
        String query = "REPLACE INTO vpp_levels (uuid, level) VALUES (?, ?)";
        if (type.equals("postgresql")) {
            query = "INSERT INTO vpp_levels (uuid, level) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET level = EXCLUDED.level";
        }

        try (SqlConnection conn = borrowConnection();
                PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, level);
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e);
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }

    // ── Rule Presets ──────────────────────────────────────────────────────────

    @Override
    public void saveRulePreset(UUID uuid, String presetName, Map<String, Boolean> rules) {
        ensureInitialized();
        if (writeSuspended) return;
        // Delete existing rows for this preset first, then re-insert
        String del = "DELETE FROM vpp_rule_presets WHERE uuid = ? AND preset_name = ?";
        String ins = type.equals("postgresql")
                ? "INSERT INTO vpp_rule_presets (uuid, preset_name, rule_key, rule_value) VALUES (?,?,?,?)"
                  + " ON CONFLICT (uuid, preset_name, rule_key) DO UPDATE SET rule_value = EXCLUDED.rule_value"
                : "REPLACE INTO vpp_rule_presets (uuid, preset_name, rule_key, rule_value) VALUES (?,?,?,?)";
        try (SqlConnection conn = borrowConnection()) {
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
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
    }

    @Override
    public Map<String, Boolean> loadRulePreset(UUID uuid, String presetName) {
        ensureInitialized();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rule_key, rule_value FROM vpp_rule_presets WHERE uuid = ? AND preset_name = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, presetName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Boolean> result = new LinkedHashMap<>();
                while (rs.next())
                    result.put(rs.getString("rule_key"), Boolean.parseBoolean(rs.getString("rule_value")));
                return result.isEmpty() ? null : result;
            }
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); return null; }
    }

    @Override
    public Set<String> listRulePresets(UUID uuid) {
        ensureInitialized();
        Set<String> names = new LinkedHashSet<>();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT preset_name FROM vpp_rule_presets WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString("preset_name"));
            }
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
        return names;
    }

    @Override
    public void deleteRulePreset(UUID uuid, String presetName) {
        ensureInitialized();
        if (writeSuspended) return;
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM vpp_rule_presets WHERE uuid = ? AND preset_name = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, presetName);
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    @Override
    public boolean getAutoVanishOnJoin(UUID uuid) {
        ensureInitialized();
        return getPref(uuid, "auto_vanish_join", "false").equalsIgnoreCase("true");
    }

    @Override
    public void setAutoVanishOnJoin(UUID uuid, boolean value) {
        ensureInitialized();
        setPref(uuid, "auto_vanish_join", String.valueOf(value));
    }

    private String getPref(UUID uuid, String key, String def) {
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pref_value FROM vpp_preferences WHERE uuid = ? AND pref_key = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("pref_value");
            }
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
        return def;
    }

    private void setPref(UUID uuid, String key, String value) {
        if (writeSuspended) return;
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_preferences (uuid,pref_key,pref_value) VALUES(?,?,?) ON CONFLICT (uuid,pref_key) DO UPDATE SET pref_value=EXCLUDED.pref_value"
                : "REPLACE INTO vpp_preferences (uuid,pref_key,pref_value) VALUES(?,?,?)";
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString()); ps.setString(2, key); ps.setString(3, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
    }

    // ── Vanish History ────────────────────────────────────────────────────────

    @Override
    public void addHistoryEntry(VanishHistoryEntry entry) {
        ensureInitialized();
        if (writeSuspended) return;
        String q = "INSERT INTO vpp_history (uuid,player_name,action,timestamp,server,reason,duration_ms)"
                + " VALUES (?,?,?,?,?,?,?)";
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, entry.getPlayerUuid().toString());
            ps.setString(2, entry.getPlayerName());
            ps.setString(3, entry.getAction().name());
            ps.setLong(4, entry.getTimestamp().toEpochMilli());
            ps.setString(5, entry.getServer());
            ps.setString(6, entry.getReason() != null ? entry.getReason() : "");
            ps.setLong(7, entry.getDurationMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
    }

    @Override
    public List<VanishHistoryEntry> getPlayerHistory(UUID uuid, int page, int perPage) {
        ensureInitialized();
        String q = "SELECT * FROM vpp_history WHERE uuid = ? ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?";
        return queryHistory(q, uuid.toString(), perPage, (page - 1) * perPage);
    }

    @Override
    public List<VanishHistoryEntry> getAllHistory(int page, int perPage) {
        ensureInitialized();
        String q = "SELECT * FROM vpp_history ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?";
        return queryHistory(q, null, perPage, (page - 1) * perPage);
    }

    @Override
    public UUID resolveHistoryPlayer(String name) {
        ensureInitialized();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT uuid FROM vpp_history WHERE LOWER(player_name) = ? ORDER BY timestamp DESC, id DESC LIMIT 1")) {
            ps.setString(1, name.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? UUID.fromString(rs.getString(1)) : null; }
        } catch (SQLException e) { throw new IllegalStateException("Could not resolve history player", e); }
    }

    @Override
    public void copyHistoryTo(StorageProvider target) {
        ensureInitialized();
        try (SqlConnection conn = borrowConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM vpp_history ORDER BY timestamp DESC, id DESC",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                // PostgreSQL uses a cursor with autocommit disabled. MySQL uses streaming rows.
                boolean mysql = conn.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
                ps.setFetchSize(mysql ? Integer.MIN_VALUE : 500);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) target.addHistoryEntry(readHistoryEntry(rs));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            failMigration(e); throw new IllegalStateException("Could not migrate history snapshot", e); }
    }

    private VanishHistoryEntry readHistoryEntry(ResultSet rs) throws SQLException {
        return new VanishHistoryEntry(UUID.fromString(rs.getString("uuid")), rs.getString("player_name"),
                VanishHistoryEntry.Action.valueOf(rs.getString("action")),
                java.time.Instant.ofEpochMilli(rs.getLong("timestamp")), rs.getString("server"),
                rs.getString("reason"), rs.getLong("duration_ms"));
    }

    private List<VanishHistoryEntry> queryHistory(String query, String uuidFilter, int limit, int offset) {
        List<VanishHistoryEntry> list = new ArrayList<>();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            int idx = 1;
            if (uuidFilter != null) ps.setString(idx++, uuidFilter);
            ps.setInt(idx++, limit);
            ps.setInt(idx, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readHistoryEntry(rs));
                }
            }
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
        return list;
    }

    @Override
    public int pruneHistory(int retentionDays) {
        ensureInitialized();
        if (retentionDays <= 0) return 0;
        long cutoff = java.time.Instant.now().minusSeconds((long) retentionDays * 86400).toEpochMilli();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM vpp_history WHERE timestamp < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); return 0; }
    }

    // ── Vanish Statistics ─────────────────────────────────────────────────────

    @Override
    public VanishStats getStats(UUID uuid) {
        ensureInitialized();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT total_ms, vanish_count, longest_ms FROM vpp_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return new VanishStats(rs.getLong("total_ms"),
                            rs.getInt("vanish_count"), rs.getLong("longest_ms"));
            }
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
        return VanishStats.empty();
    }

    @Override
    public void recordVanishSession(UUID uuid, long durationMs) {
        ensureInitialized();
        if (durationMs <= 0) return;
        if (writeSuspended) throw new IllegalStateException("Statistics writes are suspended");
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_stats (uuid,total_ms,vanish_count,longest_ms) VALUES(?,?,1,?)"
                  + " ON CONFLICT (uuid) DO UPDATE SET total_ms=vpp_stats.total_ms+EXCLUDED.total_ms,"
                  + " vanish_count=vpp_stats.vanish_count+1,"
                  + " longest_ms=GREATEST(vpp_stats.longest_ms,EXCLUDED.longest_ms)"
                : "INSERT INTO vpp_stats (uuid,total_ms,vanish_count,longest_ms) VALUES(?,?,1,?)"
                  + " ON DUPLICATE KEY UPDATE total_ms=total_ms+VALUES(total_ms),"
                  + " vanish_count=vanish_count+1,"
                  + " longest_ms=GREATEST(longest_ms,VALUES(longest_ms))";
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, durationMs);
            ps.setLong(3, durationMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e); throw new IllegalStateException("Could not record final session for " + uuid, e); }
    }

    @Override
    public void setStats(UUID uuid, VanishStats stats) {
        ensureInitialized();
        if (writeSuspended) return;
        String q = type.equals("postgresql")
                ? "INSERT INTO vpp_stats (uuid,total_ms,vanish_count,longest_ms) VALUES(?,?,?,?)"
                  + " ON CONFLICT (uuid) DO UPDATE SET total_ms=EXCLUDED.total_ms,"
                  + " vanish_count=EXCLUDED.vanish_count, longest_ms=EXCLUDED.longest_ms"
                : "INSERT INTO vpp_stats (uuid,total_ms,vanish_count,longest_ms) VALUES(?,?,?,?)"
                  + " ON DUPLICATE KEY UPDATE total_ms=VALUES(total_ms),"
                  + " vanish_count=VALUES(vanish_count), longest_ms=VALUES(longest_ms)";
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, stats.getTotalVanishTimeMs());
            ps.setInt(3, stats.getVanishCount());
            ps.setLong(4, stats.getLongestSessionMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e); handleDatabaseError(e); }
    }

    // ── Existing methods ──────────────────────────────────────────────────────

    @Override
    public Set<UUID> getAllKnownPlayers() {
        ensureInitialized();
        Set<UUID> uuids = new HashSet<>();
        String[] tables = {"vpp_vanished", "vpp_rules", "vpp_levels", "vpp_acknowledgements",
                "vpp_rule_presets", "vpp_preferences", "vpp_history", "vpp_stats"};
        for (String table : tables) {
            try (SqlConnection conn = borrowConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT uuid FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { uuids.add(UUID.fromString(rs.getString("uuid"))); }
                    catch (IllegalArgumentException ignored) {}
                }
            } catch (SQLException e) {
                failMigration(e);
                throw new IllegalStateException("Could not enumerate stored players in " + table, e);
            }
        }
        return uuids;
    }

    @Override
    public Set<String> getAcknowledgements(UUID uuid) {
        try { return getRecoveryAcknowledgements(uuid); }
        catch (IllegalStateException e) { plugin.getLogger().warning(e.getMessage()); return new HashSet<>(); }
    }

    @Override
    public Set<String> getRecoveryAcknowledgements(UUID uuid) {
        ensureInitialized();
        Set<String> ids = new HashSet<>();
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT notification_id FROM vpp_acknowledgements WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("notification_id"));
            }
        } catch (SQLException e) {
            failMigration(e);
            throw new IllegalStateException("Could not restore acknowledgements", e);
        }
        return ids;
    }

    @Override
    public void removeAcknowledgement(UUID uuid, String notificationId) {
        ensureInitialized();
        if (writeSuspended) return;
        try (SqlConnection conn = borrowConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM vpp_acknowledgements WHERE uuid = ? AND notification_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, notificationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            failMigration(e); throw new IllegalStateException("Could not remove acknowledgement", e); }
    }

    @Override
    public void removePlayerData(UUID uuid) {
        ensureInitialized();
        if (writeSuspended) return;
        try (SqlConnection conn = borrowConnection()) {
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
                failMigration(e);
                conn.rollback();
                plugin.getLogger().severe("Database error during removePlayerData: " + e.getMessage());
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            failMigration(e);
            plugin.getLogger().severe("Database error: " + e.getMessage());
        }
    }
}
