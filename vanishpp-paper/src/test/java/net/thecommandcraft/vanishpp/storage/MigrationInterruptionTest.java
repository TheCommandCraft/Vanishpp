package net.thecommandcraft.vanishpp.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import com.zaxxer.hikari.HikariDataSource;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.utils.VanishScheduler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationInterruptionTest {
    private Vanishpp plugin;
    private Tasks tasks;
    private StorageProvider source;
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        var server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        server.getScheduler().cancelTasks(plugin);
        tasks = new Tasks();
        field("vanishScheduler", tasks);
        source = plugin.getStorageProvider();
        for (UUID id : List.of(first, second)) {
            source.setVanished(id, true);
            source.setRule(id, "can_chat", true);
            source.setTemporaryRule(id, "can_drop_items", true, "0:false:" + UUID.randomUUID());
            source.setVanishLevel(id, 4);
            source.setAutoVanishOnJoin(id, true);
            source.addAcknowledgement(id, "protocol-lib-warning");
            source.saveRulePreset(id, "quiet", Map.of("can_chat", false));
            source.setStats(id, new VanishStats(5000, 2, 3000));
            source.addHistoryEntry(new VanishHistoryEntry(id, "Staff", VanishHistoryEntry.Action.VANISH,
                    Instant.parse("2026-01-01T00:00:00Z"), "lobby", id.toString(), 0));
        }
    }

    @AfterEach
    void close() { MockBukkit.unmock(); }

    @ParameterizedTest
    @ValueSource(strings = {"state", "acknowledgement", "history"})
    void interruptedSqlCopyCannotBecomeAuthoritativeOrDuplicateHistory(String failurePoint) throws Exception {
        JdbcDataSource database = database();
        SqlStorage schema = new SqlStorage(plugin, database); schema.init();
        AtomicBoolean fail = new AtomicBoolean(true), injected = new AtomicBoolean();
        AtomicInteger stateWrites = new AtomicInteger(), historyWrites = new AtomicInteger(), successfulWrites = new AtomicInteger();
        plugin.getConfigManager().getConfig().set("storage.type", "MYSQL");
        field("storageProvider", null);
        AtomicInteger poolsCreated = new AtomicInteger();
        try (var pools = mockConstruction(HikariDataSource.class, (pool, context) -> {
            if (poolsCreated.incrementAndGet() == 1) throw new IllegalStateException("initial SQL outage");
            when(pool.getConnection()).thenAnswer(call -> {
                Connection real = database.getConnection();
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (self, method, args) -> {
                    Object value = invoke(method, real, args);
                    if (!method.getName().equals("prepareStatement")) return value;
                    String query = (String) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statement, operation, parameters) -> {
                        if (operation.getName().equals("executeUpdate")) {
                            boolean state = query.startsWith("INSERT") && query.contains("INTO vpp_vanished");
                            boolean ack = query.startsWith("INSERT") && query.contains("INTO vpp_acknowledgements");
                            boolean history = query.startsWith("INSERT") && query.contains("INTO vpp_history");
                            boolean selected = switch (failurePoint) {
                                case "state" -> state && stateWrites.incrementAndGet() == 2;
                                case "acknowledgement" -> ack;
                                default -> history && historyWrites.incrementAndGet() == 2;
                            };
                            if (fail.get() && selected) {
                                injected.set(true); throw new SQLException("interrupted " + failurePoint + " copy");
                            }
                            Object result = invoke(operation, value, parameters);
                            if (state) successfulWrites.incrementAndGet();
                            return result;
                        }
                        return invoke(operation, value, parameters);
                    });
                });
            });
        })) {
            var initialize = Vanishpp.class.getDeclaredMethod("initStorage"); initialize.setAccessible(true); initialize.invoke(plugin);
            StorageProvider active = plugin.getStorageProvider();
            assertThrows(IllegalStateException.class, () -> active.isVanished(second));
            tasks.drain();
            assertTrue(injected.get(), "destination write failure must actually occur");
            assertTrue(successfulWrites.get() > 0, "failure must follow an earlier successful row write");
            assertAll(
                    () -> assertThrows(IllegalStateException.class, () -> active.isVanished(second),
                            "interrupted copy must not publish SQL readiness"),
                    () -> {
                        fail.set(false); tasks.delayed(); tasks.drain();
                        assertComplete(active);
                        tasks.delayed(); tasks.drain();
                        assertFalse(active.getRule(first, "can_drop_items", true), "migrated first deadline must execute");
                        assertFalse(active.getRule(second, "can_drop_items", true), "migrated second deadline must execute");
                    });
        }
    }

    @Test
    void independentlyPopulatedSqlDestinationRemainsUntouched() throws Exception {
        SqlStorage destination = new SqlStorage(plugin, database()); destination.init();
        UUID independent = UUID.randomUUID();
        destination.setVanished(independent, true);
        migrate(destination, "MYSQL");
        assertEquals(Set.of(independent), destination.getVanishedPlayers(), "migration must not merge into independent pre-existing data");
        assertTrue(destination.getAllHistory(1, 20).isEmpty());
    }

    @Test
    void interruptedYamlCopyCanRetryWithoutRetainingAPartialTarget(@TempDir Path directory) throws Exception {
        YamlStorage target = spy(yaml(directory));
        AtomicInteger writes = new AtomicInteger();
        doAnswer(call -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("interrupted YAML copy");
            return call.callRealMethod();
        }).when(target).setVanished(any(), eq(true));
        assertThrows(IllegalStateException.class, () -> migrate(target, "YAML"), "fixture must interrupt after an earlier player write");
        migrate(target, "YAML");
        assertComplete(yaml(directory));
    }

    @Test
    void committedYamlCheckpointReplaysBothFilesExactlyOnce(@TempDir Path directory) throws Exception {
        YamlStorage partial = yaml(directory);
        partial.setVanished(first, true);
        partial.addHistoryEntry(source.getAllHistory(1, 20).getFirst());
        YamlConfiguration journal = new YamlConfiguration();
        journal.set("version", 1);
        journal.set("data", Files.readString(plugin.getDataFolder().toPath().resolve("data.yml")));
        journal.set("history", Files.readString(plugin.getDataFolder().toPath().resolve("history.yml")));
        journal.save(directory.resolve("migration.yml").toFile());
        assertComplete(yaml(directory));
        assertComplete(yaml(directory));
        assertFalse(Files.exists(directory.resolve("migration.yml")), "fully replayed checkpoint must be retired");
    }

    @Test
    void invalidYamlCheckpointCannotPublishPartialLegacyFiles(@TempDir Path directory) throws Exception {
        yaml(directory).setVanished(first, true);
        YamlConfiguration journal = new YamlConfiguration();
        journal.set("version", 1);
        journal.set("data", "vanished-players: [unterminated");
        journal.set("history", "{}");
        journal.save(directory.resolve("migration.yml").toFile());
        assertThrows(IllegalStateException.class, () -> yaml(directory), "invalid checkpoint must keep YAML unavailable instead of publishing partial files");
    }

    @Test
    void checkpointSurvivesFailureBetweenTheTwoYamlFileReplacements(@TempDir Path directory) throws Exception {
        YamlStorage target = yaml(directory);
        Path blocked = directory.resolve("data.yml.tmp");
        Files.createDirectory(blocked); // History can install, but the data replacement cannot be written.
        assertDoesNotThrow(() -> migrate(target, "YAML"),
                "complete checkpoint must make the copy durable despite interrupted file installation");
        assertComplete(target);
        assertTrue(Files.exists(directory.resolve("migration.yml")), "interrupted installation must retain its complete checkpoint");
        assertTrue(YamlConfiguration.loadConfiguration(directory.resolve("data.yml").toFile())
                .getStringList("vanished-players").isEmpty(), "legacy data file must still be uninstalled in this stimulus");
        Files.delete(blocked);
        YamlStorage recovered = yaml(directory);
        assertComplete(recovered);
        recovered.setVanished(first, false);
        assertFalse(yaml(directory).isVanished(first), "a later write must not be overwritten by a stale checkpoint replay");
        assertFalse(Files.exists(directory.resolve("migration.yml")));
    }

    private void assertComplete(StorageProvider target) {
        assertAll(
                () -> assertEquals(Set.of(first, second), target.getVanishedPlayers(), "retry must copy every vanished player"),
                () -> { for (UUID id : List.of(first, second)) {
                    assertTrue(target.getRule(id, "can_chat", false), "rules must copy");
                    assertEquals(4, target.getVanishLevel(id), "levels must copy");
                    assertTrue(target.getAutoVanishOnJoin(id), "preferences must copy");
                    assertTrue(target.hasAcknowledged(id, "protocol-lib-warning"), "acknowledgements must copy");
                    assertEquals(Map.of("can_chat", false), target.loadRulePreset(id, "quiet"), "presets must copy");
                    assertEquals(5000, target.getStats(id).getTotalVanishTimeMs(), "statistics must copy");
                } },
                () -> assertEquals(2, target.getAllHistory(1, 20).size(), "history must copy exactly once after retry"),
                () -> assertEquals(Set.of(first.toString(), second.toString()),
                        target.getAllHistory(1, 20).stream().map(VanishHistoryEntry::getReason).collect(java.util.stream.Collectors.toSet())));
    }

    private YamlStorage yaml(Path directory) {
        Vanishpp owner = mock(Vanishpp.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        when(owner.getLogger()).thenReturn(plugin.getLogger());
        YamlStorage storage = new YamlStorage(owner); storage.init(); return storage;
    }

    private JdbcDataSource database() {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:interrupted_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        return database;
    }

    private void migrate(StorageProvider target, String type) throws Exception {
        var method = Vanishpp.class.getDeclaredMethod("migrateIfNeeded", StorageProvider.class, String.class);
        method.setAccessible(true);
        try { method.invoke(plugin, target, type); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw e;
        }
    }

    private void field(String name, Object value) throws Exception {
        var field = Vanishpp.class.getDeclaredField(name); field.setAccessible(true); field.set(plugin, value);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static class Tasks implements VanishScheduler {
        final List<Runnable> workers = new ArrayList<>(), later = new ArrayList<>();
        public void runGlobal(Runnable task) { task.run(); }
        public void runLaterGlobal(Runnable task, long ticks) { later.add(task); }
        public void runTimerGlobal(Runnable task, long delay, long period) {}
        public void runEntity(Entity entity, Runnable task, Runnable retired) { task.run(); }
        public void runEntityNow(Entity entity, Runnable task) { task.run(); }
        public void runAsync(Runnable task) { workers.add(task); }
        public void cancelAllTasks() { workers.clear(); later.clear(); }
        void drain() { while (!workers.isEmpty()) workers.removeFirst().run(); }
        void delayed() { var due = List.copyOf(later); later.clear(); due.forEach(Runnable::run); }
    }
}
