package net.thecommandcraft.vanishpp.storage;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.utils.VanishScheduler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisReconnectTest {
    @Test
    void subscriberReceivesMessagesAfterConnectionFailureAndStopsOnShutdown() throws Exception {
        Vanishpp plugin = mock(Vanishpp.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getStorageProvider()).thenReturn(mock(StorageProvider.class));
        VanishScheduler scheduler = mock(VanishScheduler.class);
        when(plugin.getVanishScheduler()).thenReturn(scheduler);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(scheduler).runGlobal(any());
        UUID id = UUID.randomUUID();
        CountDownLatch received = new CountDownLatch(1), closed = new CountDownLatch(1);
        doAnswer(call -> { received.countDown(); return null; }).when(plugin).handleNetworkVanishSync(id, true);
        Jedis broken = mock(Jedis.class), recovered = mock(Jedis.class);
        doThrow(new IllegalStateException("connection lost")).when(broken).subscribe(any(JedisPubSub.class), eq("vanishpp:sync"));
        doAnswer(call -> {
            JedisPubSub subscription = call.getArgument(0);
            subscription.onSubscribe("vanishpp:sync", 1);
            subscription.onMessage("vanishpp:sync", "VANISH:" + id);
            closed.await(10, TimeUnit.SECONDS);
            return null;
        }).when(recovered).subscribe(any(JedisPubSub.class), eq("vanishpp:sync"));
        doAnswer(call -> { closed.countDown(); return null; }).when(recovered).close();
        AtomicInteger attempts = new AtomicInteger();
        try (var construction = mockConstruction(JedisPool.class, (pool, context) ->
                when(pool.getResource()).thenAnswer(call -> attempts.getAndIncrement() == 0 ? broken : recovered))) {
            RedisStorage redis = new RedisStorage(plugin);
            redis.init();
            boolean delivered;
            try { delivered = received.await(5, TimeUnit.SECONDS); }
            finally { redis.shutdown(); closed.countDown(); }
            assertTrue(delivered, "subscriber must reconnect and receive messages after Redis recovers");
            var field = RedisStorage.class.getDeclaredField("pubSubThread");
            field.setAccessible(true);
            assertFalse(((Thread) field.get(redis)).isAlive(), "shutdown must stop the recovered subscriber");
            assertTrue(attempts.get() >= 2);
        }
    }
}
