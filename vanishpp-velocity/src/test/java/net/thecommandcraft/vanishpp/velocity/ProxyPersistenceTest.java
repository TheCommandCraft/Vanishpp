package net.thecommandcraft.vanishpp.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.thecommandcraft.vanishpp.velocity.config.VelocityConfigManager;
import net.thecommandcraft.vanishpp.velocity.storage.ProxySqlStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProxyPersistenceTest {
    @TempDir Path directory;

    @Test
    void defaultProxyStorageRetainsVanishAcrossRestart() {
        var logger = LoggerFactory.getLogger(getClass());
        VelocityConfigManager config = new VelocityConfigManager(directory, logger);
        config.load();
        VanishppVelocity plugin = new VanishppVelocity(null, logger, directory);
        ProxyStateManager first = new ProxyStateManager(plugin, config);
        first.init();
        UUID id = UUID.randomUUID();
        first.setVanished(id, "Hidden", "survival", true, 4);
        first.shutdown();
        ProxyStateManager restarted = new ProxyStateManager(plugin, config);
        restarted.init();
        try {
            assertTrue(restarted.isVanished(id), "default proxy storage must preserve vanish across restart");
            assertEquals(4, restarted.getVanishLevel(id));
        } finally { restarted.shutdown(); }
    }

    @Test
    void proxySqlWritesCannotFinishInReverseToggleOrder() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        Object scheduler = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class.getMethod("getScheduler").getReturnType()}, (self, method, args) -> {
                    if (!method.getName().equals("buildTask")) return null;
                    Runnable task = (Runnable) args[1];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{method.getReturnType()},
                            (builder, operation, values) -> {
                                if (operation.getName().equals("schedule")) { workers.add(task); return null; }
                                return builder;
                            });
                });
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProxyServer.class},
                (self, method, args) -> method.getName().equals("getScheduler") ? scheduler : null);
        var logger = LoggerFactory.getLogger(getClass());
        VelocityConfigManager config = new VelocityConfigManager(directory, logger);
        config.load();
        VanishppVelocity plugin = new VanishppVelocity(proxy, logger, directory);
        ProxyStateManager states = new ProxyStateManager(plugin, config);
        boolean[] persisted = {false};
        ProxySqlStorage storage = new ProxySqlStorage(config, logger) {
            @Override public void setVanished(UUID id, boolean hidden) { persisted[0] = hidden; }
            @Override public void shutdown() {}
        };
        var field = ProxyStateManager.class.getDeclaredField("sqlStorage");
        field.setAccessible(true);
        field.set(states, storage);
        UUID id = UUID.randomUUID();
        states.setVanished(id, "Staff", "survival", true, 1);
        states.setVanished(id, "Staff", "survival", false, 1);
        while (!workers.isEmpty()) workers.removeLast().run();
        states.shutdown();
        assertFalse(persisted[0], "proxy SQL must persist the newest toggle despite worker reordering");
    }
}
