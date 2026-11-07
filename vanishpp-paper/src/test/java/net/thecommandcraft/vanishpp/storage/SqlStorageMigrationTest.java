package net.thecommandcraft.vanishpp.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Tests production SQL initialization and upgrades against an isolated H2 database. */
class SqlStorageMigrationTest {

    private Connection conn;
    private SqlStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        Vanishpp plugin = MockBukkit.load(Vanishpp.class);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=MySQL");
        conn = ds.getConnection();
        storage = new SqlStorage(plugin, ds);
        storage.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (conn != null) conn.close();
            if (storage != null) storage.shutdown();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void testSchemaVersionTableExists() throws Exception {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "VPP_SCHEMA_VERSION", null)) {
            assertTrue(rs.next(), "Production init must create the schema version table");
        }
    }

    @Test
    void testFreshSchemaMigratesToCurrentVersion() throws Exception {
        assertEquals(3, schemaVersion(), "Production init must migrate a fresh schema to version 3");
    }

    @Test
    void testAllRequiredTablesExist() throws Exception {
        for (String table : List.of("VPP_VANISHED", "VPP_RULES", "VPP_LEVELS",
                "VPP_ACKNOWLEDGEMENTS", "VPP_SCHEMA_VERSION", "VPP_PLUGIN_VERSION",
                "VPP_RULE_PRESETS", "VPP_PREFERENCES", "VPP_HISTORY", "VPP_STATS")) {
            try (ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
                assertTrue(rs.next(), "Production init must create " + table);
            }
        }
    }

    @Test
    void testVersionOneUpgradePreservesPlayers() throws Exception {
        // Recreate the old layout as input; only production init performs the upgrade.
        try (Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE vpp_vanished (uuid VARCHAR(36) PRIMARY KEY)");
            st.execute("CREATE TABLE vpp_rules (uuid VARCHAR(36), rule_key VARCHAR(64), rule_value TEXT, PRIMARY KEY(uuid, rule_key))");
            st.execute("CREATE TABLE vpp_levels (uuid VARCHAR(36) PRIMARY KEY, level INT)");
            st.execute("CREATE TABLE vpp_schema_version (version INT PRIMARY KEY)");
            st.execute("INSERT INTO vpp_schema_version VALUES (1)");
        }
        UUID uuid = UUID.randomUUID();
        storage.setVanished(uuid, true);
        storage.setRule(uuid, "can_chat", true);
        storage.setVanishLevel(uuid, 5);

        storage.init();

        assertEquals(3, schemaVersion(), "Production init must upgrade version 1 to version 3");
        assertTrue(storage.isVanished(uuid), "Upgrade must preserve vanished players");
        assertEquals(true, storage.getRules(uuid).get("can_chat"));
        assertEquals(5, storage.getVanishLevel(uuid));
        for (String table : List.of("VPP_VANISHED", "VPP_RULES", "VPP_LEVELS")) {
            String column = table.equals("VPP_VANISHED") ? "CREATED_AT" : "UPDATED_AT";
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
                assertTrue(rs.next(), "Upgrade must add " + table + "." + column);
            }
        }
        storage.setAutoVanishOnJoin(uuid, true);
        assertTrue(storage.getAutoVanishOnJoin(uuid), "Upgraded preferences table must be usable");
    }

    @Test
    void testVanishStateInsertAndSelect() {
        UUID uuid = UUID.randomUUID();
        assertFalse(storage.isVanished(uuid));
        storage.setVanished(uuid, true);
        assertTrue(storage.isVanished(uuid), "Production storage must persist vanish");
    }

    @Test
    void testVanishStateDelete() {
        UUID uuid = UUID.randomUUID();
        storage.setVanished(uuid, true);
        assertTrue(storage.isVanished(uuid));
        storage.setVanished(uuid, false);
        assertFalse(storage.isVanished(uuid));
    }

    @Test
    void testRulesInsertAndSelect() {
        UUID uuid = UUID.randomUUID();
        storage.setRule(uuid, "can_chat", true);
        assertEquals(true, storage.getRules(uuid).get("can_chat"));
    }

    @Test
    void testRulesUpsertOverwrites() {
        UUID uuid = UUID.randomUUID();
        storage.setRule(uuid, "can_chat", true);
        assertEquals(true, storage.getRules(uuid).get("can_chat"));
        storage.setRule(uuid, "can_chat", false);
        assertEquals(false, storage.getRules(uuid).get("can_chat"));
    }

    @Test
    void testAcknowledgementInsertAndSelect() {
        UUID uuid = UUID.randomUUID();
        assertFalse(storage.hasAcknowledged(uuid, "migration_v7"));
        storage.addAcknowledgement(uuid, "migration_v7");
        assertTrue(storage.hasAcknowledged(uuid, "migration_v7"));
    }

    @Test
    void testAcknowledgementIsPerPlayer() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        storage.addAcknowledgement(a, "migration_v7");
        assertTrue(storage.hasAcknowledged(a, "migration_v7"));
        assertFalse(storage.hasAcknowledged(b, "migration_v7"));
    }

    @Test
    void testAcknowledgementIdempotent() throws Exception {
        UUID uuid = UUID.randomUUID();
        storage.addAcknowledgement(uuid, "migration_v7");
        storage.addAcknowledgement(uuid, "migration_v7");
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM vpp_acknowledgements WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Repeated acknowledgement must not duplicate rows");
            }
        }
    }

    @Test
    void testLevelUpsert() {
        UUID uuid = UUID.randomUUID();
        storage.setVanishLevel(uuid, 2);
        assertEquals(2, storage.getVanishLevel(uuid));
        storage.setVanishLevel(uuid, 5);
        assertEquals(5, storage.getVanishLevel(uuid));
    }

    @Test
    void testSchemaMigrationIsNoOpWhenUpToDate() throws Exception {
        UUID uuid = UUID.randomUUID();
        storage.setVanished(uuid, true);
        storage.setRule(uuid, "can_chat", true);
        storage.init();
        assertEquals(3, schemaVersion());
        assertTrue(storage.isVanished(uuid), "Repeated init must preserve player state");
        assertEquals(true, storage.getRules(uuid).get("can_chat"));
    }

    private int schemaVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM vpp_schema_version")) {
            assertTrue(rs.next(), "Production init must seed the schema version");
            int version = rs.getInt(1);
            assertFalse(rs.next(), "Schema version must have exactly one row");
            return version;
        }
    }
}
