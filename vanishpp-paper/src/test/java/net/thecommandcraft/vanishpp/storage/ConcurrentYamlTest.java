package net.thecommandcraft.vanishpp.storage;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConcurrentYamlTest {
    @TempDir Path directory;

    @Test
    void concurrentVanishWritesRetainBothPlayersOnDisk() throws Exception {
        Vanishpp plugin = mock(Vanishpp.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        YamlStorage storage = new YamlStorage(plugin);
        storage.init();
        CountDownLatch firstRead = new CountDownLatch(1), release = new CountDownLatch(1), secondRead = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        YamlConfiguration config = new YamlConfiguration() {
            @Override public List<String> getStringList(String path) {
                List<String> snapshot = super.getStringList(path);
                if (path.equals("vanished-players")) {
                    if (Thread.currentThread() == firstThread.get()) {
                        firstRead.countDown();
                        try { assertTrue(release.await(10, TimeUnit.SECONDS), "release first writer"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    } else secondRead.countDown();
                }
                return snapshot;
            }
        };
        var field = YamlStorage.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(storage, config);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread a = new Thread(() -> { try { storage.setVanished(first, true); } catch (Throwable t) { failure.set(t); } });
        Thread b = new Thread(() -> { try { storage.setVanished(second, true); } catch (Throwable t) { failure.set(t); } });
        firstThread.set(a);
        try {
            a.start();
            assertTrue(firstRead.await(5, TimeUnit.SECONDS), "first writer must reach the read");
            b.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (secondRead.getCount() > 0 && b.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                Thread.onSpinWait();
            if (secondRead.getCount() == 0) b.join(5000);
        } finally {
            release.countDown();
            a.join(5000);
            b.join(5000);
        }
        assertNull(failure.get(), "workers must finish without errors");
        assertFalse(a.isAlive() || b.isAlive(), "workers must not deadlock");
        YamlStorage reopened = new YamlStorage(plugin);
        reopened.init();
        assertEquals(Set.of(first, second), reopened.getVanishedPlayers(),
                "concurrent YAML writes must retain both players after reopening storage");
    }
}
