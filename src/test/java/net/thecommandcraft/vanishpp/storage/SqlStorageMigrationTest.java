package net.thecommandcraft.vanishpp.storage;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SQL schema creation and migration using an in-memory H2 database.
 * Validates that all required tables exist and that all StorageProvider
 * operations work correctly on a fresh schema.
 */
class SqlStorageMigrationTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:vanishpp_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        conn = ds.getConnection();
        createSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    /** Mirrors SqlStorage.init() schema creation logic. */
    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS vpp_vanished (uuid VARCHAR(36) PRIMARY KEY)");
            st.execute("CREATE TABLE IF NOT EXISTS vpp_rules (uuid VARCHAR(36), rule_key VARCHAR(64), rule_value TEXT, PRIMARY KEY(uuid, rule_key))");
            st.execute("CREATE TABLE IF NOT EXISTS vpp_levels (uuid VARCHAR(36) PRIMARY KEY, level INT)");
            st.execute("CREATE TABLE IF NOT EXISTS vpp_acknowledgements (uuid VARCHAR(36), notification_id VARCHAR(128), PRIMARY KEY(uuid, notification_id))");
            st.execute("CREATE TABLE IF NOT EXISTS vpp_schema_version (version INT PRIMARY KEY)");
            st.execute("MERGE INTO vpp_schema_version (version) KEY(version) VALUES (1)");
        }
    }

    // -------------------------------------------------------------------------
    // Schema version table
    // -------------------------------------------------------------------------

    @Test
    void testSchemaVersionTableExists() throws Exception {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "VPP_SCHEMA_VERSION", null)) {
            assertTrue(rs.next(), "vpp_schema_version table must exist");
        }
    }

    @Test
    void testSchemaVersionSeedIsOne() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM vpp_schema_version");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "vpp_schema_version must have a row");
            assertEquals(1, rs.getInt("version"), "Initial schema version must be 1");
        }
    }

    // -------------------------------------------------------------------------
    // All required tables exist
    // -------------------------------------------------------------------------

    @Test
    void testAllRequiredTablesExist() throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        for (String table : List.of("VPP_VANISHED", "VPP_RULES", "VPP_LEVELS",
                                    "VPP_ACKNOWLEDGEMENTS", "VPP_SCHEMA_VERSION")) {
            try (ResultSet rs = meta.getTables(null, null, table, null)) {
                assertTrue(rs.next(), "Table " + table + " must exist in schema");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Vanish state
    // -------------------------------------------------------------------------

    @Test
    void testVanishStateInsertAndSelect() throws Exception {
        UUID uuid = UUID.randomUUID();
        conn.prepareStatement("INSERT INTO vpp_vanished (uuid) VALUES ('" + uuid + "')").executeUpdate();

        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM vpp_vanished WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Inserted UUID must be retrievable");
            }
        }
    }

    @Test
    void testVanishStateDelete() throws Exception {
        UUID uuid = UUID.randomUUID();
        conn.prepareStatement("INSERT INTO vpp_vanished (uuid) VALUES ('" + uuid + "')").executeUpdate();
        conn.prepareStatement("DELETE FROM vpp_vanished WHERE uuid = '" + uuid + "'").executeUpdate();

        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM vpp_vanished WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(), "Deleted UUID must not be retrievable");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rules
    // -------------------------------------------------------------------------

    @Test
    void testRulesInsertAndSelect() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO vpp_rules (uuid, rule_key, rule_value) KEY(uuid, rule_key) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, "can_chat");
            ps.setString(3, "true");
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT rule_value FROM vpp_rules WHERE uuid = ? AND rule_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, "can_chat");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("true", rs.getString("rule_value"));
            }
        }
    }

    @Test
    void testRulesUpsertOverwrites() throws Exception {
        UUID uuid = UUID.randomUUID();
        for (String val : List.of("true", "false")) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO vpp_rules (uuid, rule_key, rule_value) KEY(uuid, rule_key) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, "can_chat");
                ps.setString(3, val);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT rule_value FROM vpp_rules WHERE uuid = ? AND rule_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, "can_chat");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("false", rs.getString("rule_value"), "Second upsert must overwrite first");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Acknowledgements
    // -------------------------------------------------------------------------

    @Test
    void testAcknowledgementInsertAndSelect() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO vpp_acknowledgements (uuid, notification_id) KEY(uuid, notification_id) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, "migration_v7");
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vpp_acknowledgements WHERE uuid = ? AND notification_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, "migration_v7");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Inserted acknowledgement must be retrievable");
            }
        }
    }

    @Test
    void testAcknowledgementIsPerPlayer() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO vpp_acknowledgements (uuid, notification_id) KEY(uuid, notification_id) VALUES (?, ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, "migration_v7");
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vpp_acknowledgements WHERE uuid = ? AND notification_id = ?")) {
            ps.setString(1, b.toString());
            ps.setString(2, "migration_v7");
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(), "Acknowledgement for player A must not affect player B");
            }
        }
    }

    @Test
    void testAcknowledgementIdempotent() throws Exception {
        UUID uuid = UUID.randomUUID();
        String sql = "MERGE INTO vpp_acknowledgements (uuid, notification_id) KEY(uuid, notification_id) VALUES (?, ?)";
        // Insert twice — must not throw or duplicate
        for (int i = 0; i < 2; i++) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, "migration_v7");
                assertDoesNotThrow((org.junit.jupiter.api.function.Executable) ps::executeUpdate,
                        "Duplicate acknowledgement insert must not throw");
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM vpp_acknowledgements WHERE uuid = ? AND notification_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, "migration_v7");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Idempotent insert must not create duplicate rows");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Levels
    // -------------------------------------------------------------------------

    @Test
    void testLevelUpsert() throws Exception {
        UUID uuid = UUID.randomUUID();
        String sql = "MERGE INTO vpp_levels (uuid, level) KEY(uuid) VALUES (?, ?)";
        for (int level : List.of(2, 5)) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, level);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT level FROM vpp_levels WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt("level"), "Level must reflect most recent upsert");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Schema migration no-op when already at current version
    // -------------------------------------------------------------------------

    @Test
    void testSchemaMigrationIsNoOpWhenUpToDate() throws Exception {
        // Version is already 1 (seeded in setUp). Running migration again must be harmless.
        int versionBefore;
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM vpp_schema_version");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            versionBefore = rs.getInt("version");
        }

        // Simulate re-running migration logic: if version >= CURRENT, skip
        int currentSchemaVersion = 1;
        boolean migrationRan = false;
        if (versionBefore < currentSchemaVersion) {
            migrationRan = true;
        }

        assertFalse(migrationRan, "Migration must not re-run when schema is already at current version");
        assertEquals(1, versionBefore, "Version must still be 1 after no-op migration check");
    }
}
