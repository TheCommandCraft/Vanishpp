package net.thecommandcraft.vanishpp.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import com.zaxxer.hikari.HikariDataSource;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlRecoveryTest {
    @Test
    void failedPoolInitializationCanRecoverOnTheNextRead() throws Exception {
        MockBukkit.mock();
        try {
            Vanishpp plugin = MockBukkit.load(Vanishpp.class);
            java.util.List<Runnable> workers = new java.util.ArrayList<>();
            var scheduler = mock(net.thecommandcraft.vanishpp.utils.VanishScheduler.class);
            doAnswer(call -> { workers.add(call.getArgument(0)); return null; }).when(scheduler).runAsync(any());
            var schedulerField = Vanishpp.class.getDeclaredField("vanishScheduler");
            schedulerField.setAccessible(true);
            schedulerField.set(plugin, scheduler);
            JdbcDataSource database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:recovery_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
            SqlStorage seed = new SqlStorage(plugin, database);
            seed.init();
            UUID id = UUID.randomUUID();
            seed.setVanished(id, true);
            AtomicInteger attempts = new AtomicInteger();
            try (var pools = mockConstruction(HikariDataSource.class, (pool, context) -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("connection unavailable");
                when(pool.getConnection()).thenAnswer(call -> database.getConnection());
            })) {
                SqlStorage storage = new SqlStorage(plugin, "MYSQL");
                assertThrows(Exception.class, storage::init, "first pool initialization must fail in the fixture");
                assertThrows(IllegalStateException.class, () -> storage.isVanished(id), "unavailable read must queue recovery");
                while (!workers.isEmpty()) workers.removeFirst().run();
                assertDoesNotThrow(() -> {
                    assertTrue(storage.isVanished(id), "recovered SQL must restore the persisted hidden player");
                }, "failed pool initialization must retry rather than leave a permanently unusable provider");
                storage.shutdown();
                assertEquals(2, attempts.get());
            }
        } finally { MockBukkit.unmock(); }
    }
}
