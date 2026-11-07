package net.thecommandcraft.vanishpp.config;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.storage.SqlStorage;
import net.thecommandcraft.vanishpp.storage.StorageProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Gameplay reads never acquire a SQL connection. Failed refreshes retain the last complete snapshot. */
public final class PlayerDataCache {
    private record Snapshot(StorageProvider provider, Map<String, Object> rules, int level, Set<String> acknowledgements) {}
    private final Vanishpp plugin;
    private static final class Entry {
        volatile Snapshot snapshot;
        long revision;
        boolean loading;
    }
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public PlayerDataCache(Vanishpp plugin) { this.plugin = plugin; }

    /** Called by pre-login or the ordered storage worker, never by a gameplay callback. */
    public void load(UUID uuid) {
        StorageProvider provider = plugin.getStorageProvider();
        if (!(provider instanceof SqlStorage)) return;
        Entry entry = entries.computeIfAbsent(uuid, key -> new Entry());
        long revision;
        synchronized (entry) { revision = entry.revision; }
        Map<String, Object> rules = Collections.unmodifiableMap(new HashMap<>(provider.getRecoveryRules(uuid)));
        int level = provider.getRecoveryVanishLevel(uuid);
        Set<String> acknowledgements = Set.copyOf(provider.getRecoveryAcknowledgements(uuid));
        synchronized (entry) {
            if (provider == plugin.getStorageProvider() && entries.get(uuid) == entry && revision == entry.revision)
                entry.snapshot = new Snapshot(provider, rules, level, acknowledgements);
        }
    }

    public void refresh(UUID uuid) {
        if (!(plugin.getStorageProvider() instanceof SqlStorage)) return;
        Entry entry = entries.computeIfAbsent(uuid, key -> new Entry());
        synchronized (entry) { if (entry.loading) return; entry.loading = true; }
        plugin.submitStorageWork(() -> {
            try { if (entries.get(uuid) == entry) load(uuid); }
            catch (RuntimeException e) { plugin.getLogger().warning("Player settings refresh will retry: " + e.getMessage()); }
            finally { synchronized (entry) { entry.loading = false; } }
        });
    }

    public void refreshKnown() {
        if (!(plugin.getStorageProvider() instanceof SqlStorage)) return;
        plugin.submitStorageWork(() -> {
            try {
                Set<UUID> players = new HashSet<>(plugin.getStorageProvider().getVanishedPlayers());
                plugin.getServer().getOnlinePlayers().forEach(player -> players.add(player.getUniqueId()));
                for (UUID uuid : players) load(uuid);
            }
            catch (RuntimeException e) { plugin.getLogger().warning("Player settings refresh remains pending: " + e.getMessage()); }
        });
    }

    private Snapshot snapshot(UUID uuid) {
        Entry entry = entries.get(uuid);
        Snapshot snapshot = entry == null ? null : entry.snapshot;
        if (snapshot == null || snapshot.provider() != plugin.getStorageProvider()) refresh(uuid);
        return snapshot;
    }

    public Map<String, Object> rules(UUID uuid) {
        if (!(plugin.getStorageProvider() instanceof SqlStorage)) return plugin.getStorageProvider().getRules(uuid);
        Snapshot snapshot = snapshot(uuid);
        return snapshot == null ? Map.of() : snapshot.rules();
    }

    public int level(UUID uuid) {
        if (!(plugin.getStorageProvider() instanceof SqlStorage)) return plugin.getStorageProvider().getVanishLevel(uuid);
        Snapshot snapshot = snapshot(uuid);
        return snapshot == null ? 1 : snapshot.level();
    }

    public boolean acknowledged(UUID uuid, String notification) {
        if (!(plugin.getStorageProvider() instanceof SqlStorage)) return plugin.getStorageProvider().hasAcknowledged(uuid, notification);
        Snapshot snapshot = snapshot(uuid);
        return snapshot != null && snapshot.acknowledgements().contains(notification);
    }

    /** A completed write invalidates older reads before the pending decision is released. */
    public void ruleWritten(UUID uuid, String rule, Object value) {
        Entry entry = entries.computeIfAbsent(uuid, key -> new Entry());
        synchronized (entry) {
            entry.revision++;
            Snapshot previous = entry.snapshot;
            Map<String, Object> rules = new HashMap<>(previous == null ? Map.of() : previous.rules());
            if (value == null) rules.remove(rule); else rules.put(rule, value);
            entry.snapshot = new Snapshot(plugin.getStorageProvider(), Collections.unmodifiableMap(rules),
                    previous == null ? 1 : previous.level(), previous == null ? Set.of() : previous.acknowledgements());
        }
    }

    public void forget(UUID uuid) { entries.remove(uuid); }
}
