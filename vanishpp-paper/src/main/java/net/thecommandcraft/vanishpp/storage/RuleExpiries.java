package net.thecommandcraft.vanishpp.storage;

import net.thecommandcraft.vanishpp.Vanishpp;
import java.util.*;

/** Owns deadline tasks. A failed replacement scan leaves the previous tasks intact. */
public final class RuleExpiries implements AutoCloseable {
    private record Key(UUID uuid, String rule) {}
    private static final class Deadline {
        final StorageProvider provider;
        final String token;
        Runnable cancel = () -> {};
        Deadline(StorageProvider provider, String token) { this.provider = provider; this.token = token; }
    }

    private final Vanishpp plugin;
    private final Map<Key, Deadline> deadlines = new HashMap<>();
    private Runnable cancelRetry = () -> {};
    private boolean closed;

    public RuleExpiries(Vanishpp plugin) { this.plugin = plugin; }

    public synchronized void replace(UUID uuid, String rule, String token, StorageProvider provider) {
        if (closed) return;
        Key key = new Key(uuid, rule);
        Deadline old = deadlines.remove(key);
        if (old != null) old.cancel.run();
        Deadline deadline = new Deadline(provider, token);
        deadlines.put(key, deadline);
        schedule(key, deadline, 1L);
    }

    public synchronized void cancel(UUID uuid, String rule) {
        Deadline old = deadlines.remove(new Key(uuid, rule));
        if (old != null) old.cancel.run();
    }

    public void reload() {
        plugin.submitStorageWork(() -> {
            try {
                restoreNow(plugin.getStorageProvider());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Rule deadline recovery will retry: " + e.getMessage());
                synchronized (this) {
                    if (closed) return;
                    cancelRetry.run();
                    cancelRetry = plugin.getVanishScheduler().startLater(this::reload, 100L);
                }
            }
        });
    }

    /** Called only by the ordered worker; publication follows a complete successful scan. */
    public void restoreNow(StorageProvider provider) {
        Map<Key, String> snapshot = new HashMap<>();
        for (UUID uuid : provider.getAllKnownPlayers()) {
            provider.getRecoveryRules(uuid).forEach((rule, value) -> {
                if (rule.startsWith(StorageProvider.EXPIRY_PREFIX))
                    snapshot.put(new Key(uuid, rule.substring(StorageProvider.EXPIRY_PREFIX.length())), value.toString());
            });
        }
        synchronized (this) {
            if (closed || provider != plugin.getStorageProvider()) return;
            cancelRetry.run();
            deadlines.values().forEach(deadline -> deadline.cancel.run());
            deadlines.clear();
            snapshot.forEach((key, token) -> replace(key.uuid(), key.rule(), token, provider));
        }
    }

    private void schedule(Key key, Deadline deadline, long minimumTicks) {
        long due;
        try { due = Long.parseLong(deadline.token.split(":", 3)[0]); }
        catch (IllegalArgumentException e) {
            deadlines.remove(key, deadline);
            plugin.getLogger().warning("Invalid saved rule expiry for " + key.uuid());
            return;
        }
        deadline.cancel = plugin.getVanishScheduler().startLater(() -> plugin.submitStorageWork(() -> expire(key, deadline)),
                Math.max(minimumTicks, (due - System.currentTimeMillis() + 49L) / 50L));
    }

    private void expire(Key key, Deadline deadline) {
        synchronized (this) {
            if (closed || deadlines.get(key) != deadline) return;
        }
        if (deadline.provider != plugin.getStorageProvider()) { reload(); return; }
        if (Long.parseLong(deadline.token.split(":", 3)[0]) > System.currentTimeMillis()) {
            synchronized (this) { if (!closed && deadlines.get(key) == deadline) schedule(key, deadline, 1L); }
            return;
        }
        try {
            boolean changed = deadline.provider.expireRule(key.uuid(), key.rule(), deadline.token);
            synchronized (this) {
                if (closed || !deadlines.remove(key, deadline)) return;
            }
            if (changed) plugin.ruleExpired(key.uuid(), key.rule(), deadline.provider,
                    Boolean.parseBoolean(deadline.token.split(":", 3)[1]));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Rule expiry will retry: " + e.getMessage());
            synchronized (this) { if (!closed && deadlines.get(key) == deadline) schedule(key, deadline, 100L); }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        cancelRetry.run();
        deadlines.values().forEach(deadline -> deadline.cancel.run());
        deadlines.clear();
    }
}
