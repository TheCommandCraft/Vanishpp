package net.thecommandcraft.vanishpp.storage;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class YamlStorage implements StorageProvider {

    private final Vanishpp plugin;
    private File file;
    private FileConfiguration config;

    // Separate file for history so data.yml stays small
    private File historyFile;
    private FileConfiguration historyConfig;

    public YamlStorage(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        this.file = new File(plugin.getDataFolder(), "data.yml");
        if (!this.file.exists()) {
            try { this.file.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Could not create data.yml!"); }
        }
        this.config = YamlConfiguration.loadConfiguration(this.file);

        this.historyFile = new File(plugin.getDataFolder(), "history.yml");
        if (!this.historyFile.exists()) {
            try { this.historyFile.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Could not create history.yml!"); }
        }
        this.historyConfig = YamlConfiguration.loadConfiguration(this.historyFile);
    }

    @Override
    public void shutdown() {
        save();
        saveHistory();
    }

    private synchronized void save() {
        try { this.config.save(this.file); }
        catch (IOException e) { plugin.getLogger().severe("Could not save data.yml!"); }
    }

    private synchronized void saveHistory() {
        try { this.historyConfig.save(this.historyFile); }
        catch (IOException e) { plugin.getLogger().severe("Could not save history.yml!"); }
    }

    // ── Vanish State ──────────────────────────────────────────────────────────

    @Override
    public boolean isVanished(UUID uuid) {
        List<String> vanished = config.getStringList("vanished-players");
        return vanished.contains(uuid.toString());
    }

    @Override
    public void setVanished(UUID uuid, boolean vanished) {
        List<String> list = new ArrayList<>(config.getStringList("vanished-players"));
        if (vanished) {
            if (!list.contains(uuid.toString())) list.add(uuid.toString());
        } else {
            list.remove(uuid.toString());
        }
        config.set("vanished-players", list);
        save();
    }

    @Override
    public Set<UUID> getVanishedPlayers() {
        return config.getStringList("vanished-players").stream()
                .filter(s -> {
                    try { UUID.fromString(s); return true; }
                    catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Ignoring invalid UUID in vanished-players: " + s);
                        return false;
                    }
                })
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    @Override
    public boolean getRule(UUID uuid, String rule, boolean defaultValue) {
        return config.getBoolean("rules." + uuid + "." + rule, defaultValue);
    }

    @Override
    public void setRule(UUID uuid, String rule, Object value) {
        config.set("rules." + uuid + "." + rule, value);
        save();
    }

    @Override
    public Map<String, Object> getRules(UUID uuid) {
        Map<String, Object> rules = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("rules." + uuid);
        if (section != null) {
            for (String key : section.getKeys(false)) rules.put(key, section.get(key));
        }
        return rules;
    }

    // ── Rule Presets ──────────────────────────────────────────────────────────

    @Override
    public void saveRulePreset(UUID uuid, String presetName, Map<String, Boolean> rules) {
        String base = "presets." + uuid + "." + presetName + ".";
        rules.forEach((rule, value) -> config.set(base + rule, value));
        save();
    }

    @Override
    public Map<String, Boolean> loadRulePreset(UUID uuid, String presetName) {
        ConfigurationSection sec = config.getConfigurationSection("presets." + uuid + "." + presetName);
        if (sec == null) return null;
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            Object v = sec.get(key);
            if (v instanceof Boolean b) result.put(key, b);
            else result.put(key, Boolean.parseBoolean(v.toString()));
        }
        return result;
    }

    @Override
    public Set<String> listRulePresets(UUID uuid) {
        ConfigurationSection sec = config.getConfigurationSection("presets." + uuid);
        if (sec == null) return Collections.emptySet();
        return sec.getKeys(false);
    }

    @Override
    public void deleteRulePreset(UUID uuid, String presetName) {
        config.set("presets." + uuid + "." + presetName, null);
        save();
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    @Override
    public boolean getAutoVanishOnJoin(UUID uuid) {
        return config.getBoolean("preferences." + uuid + ".auto-vanish-join", false);
    }

    @Override
    public void setAutoVanishOnJoin(UUID uuid, boolean value) {
        config.set("preferences." + uuid + ".auto-vanish-join", value);
        save();
    }

    // ── Metadata / Acknowledgements ───────────────────────────────────────────

    @Override
    public boolean hasAcknowledged(UUID uuid, String notificationId) {
        return config.getStringList("acknowledged-notifications." + uuid).contains(notificationId);
    }

    @Override
    public void addAcknowledgement(UUID uuid, String notificationId) {
        List<String> list = new ArrayList<>(config.getStringList("acknowledged-notifications." + uuid));
        if (!list.contains(notificationId)) {
            list.add(notificationId);
            config.set("acknowledged-notifications." + uuid, list);
            save();
        }
    }

    // ── Permission Levels ─────────────────────────────────────────────────────

    @Override
    public int getVanishLevel(UUID uuid) {
        return config.getInt("levels." + uuid, 1);
    }

    @Override
    public void setVanishLevel(UUID uuid, int level) {
        config.set("levels." + uuid, level);
        save();
    }

    // ── Vanish History ────────────────────────────────────────────────────────

    @Override
    public void addHistoryEntry(VanishHistoryEntry entry) {
        List<Map<?, ?>> entries = new ArrayList<>(historyConfig.getMapList("history"));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", entry.getPlayerUuid().toString());
        map.put("name", entry.getPlayerName());
        map.put("action", entry.getAction().name());
        map.put("timestamp", entry.getTimestamp().toEpochMilli());
        map.put("server", entry.getServer());
        map.put("reason", entry.getReason() != null ? entry.getReason() : "");
        map.put("duration", entry.getDurationMs());
        entries.add(map);
        historyConfig.set("history", entries);
        saveHistory();
    }

    @Override
    public List<VanishHistoryEntry> getPlayerHistory(UUID uuid, int page, int perPage) {
        String uuidStr = uuid.toString();
        List<VanishHistoryEntry> all = parseHistory().stream()
                .filter(e -> e.getPlayerUuid().equals(uuid))
                .collect(Collectors.toList());
        return paginate(all, page, perPage);
    }

    @Override
    public List<VanishHistoryEntry> getAllHistory(int page, int perPage) {
        return paginate(parseHistory(), page, perPage);
    }

    private List<VanishHistoryEntry> parseHistory() {
        List<Map<?, ?>> raw = historyConfig.getMapList("history");
        List<VanishHistoryEntry> result = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            try {
                UUID uuid = UUID.fromString((String) m.get("uuid"));
                String name = (String) m.get("name");
                VanishHistoryEntry.Action action = VanishHistoryEntry.Action.valueOf((String) m.get("action"));
                Instant ts = Instant.ofEpochMilli(((Number) m.get("timestamp")).longValue());
                String server = (String) m.get("server");
                Object rawReason = m.get("reason");
                String reason = rawReason instanceof String s ? s : "";
                Object rawDur = m.get("duration");
                long dur = rawDur instanceof Number n ? n.longValue() : 0L;
                result.add(new VanishHistoryEntry(uuid, name, action, ts, server,
                        reason.isEmpty() ? null : reason, dur));
            } catch (Exception ignored) {}
        }
        // Most-recent first
        result.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
        return result;
    }

    private static <T> List<T> paginate(List<T> list, int page, int perPage) {
        if (page < 1) page = 1;
        int from = (page - 1) * perPage;
        if (from >= list.size()) return Collections.emptyList();
        int to = Math.min(from + perPage, list.size());
        return list.subList(from, to);
    }

    @Override
    public int pruneHistory(int retentionDays) {
        if (retentionDays <= 0) return 0;
        Instant cutoff = Instant.now().minusSeconds((long) retentionDays * 86400);
        List<Map<?, ?>> raw = new ArrayList<>(historyConfig.getMapList("history"));
        int before = raw.size();
        raw.removeIf(m -> {
            try {
                long ts = ((Number) m.get("timestamp")).longValue();
                return Instant.ofEpochMilli(ts).isBefore(cutoff);
            } catch (Exception e) { return true; }
        });
        int removed = before - raw.size();
        if (removed > 0) {
            historyConfig.set("history", raw);
            saveHistory();
        }
        return removed;
    }

    // ── Vanish Statistics ─────────────────────────────────────────────────────

    @Override
    public VanishStats getStats(UUID uuid) {
        String base = "stats." + uuid + ".";
        long total  = config.getLong(base + "total-ms", 0);
        int count   = config.getInt(base + "count", 0);
        long longest = config.getLong(base + "longest-ms", 0);
        return new VanishStats(total, count, longest);
    }

    @Override
    public void recordVanishSession(UUID uuid, long durationMs) {
        if (durationMs <= 0) return;
        String base = "stats." + uuid + ".";
        long total   = config.getLong(base + "total-ms", 0) + durationMs;
        int count    = config.getInt(base + "count", 0) + 1;
        long longest = Math.max(config.getLong(base + "longest-ms", 0), durationMs);
        config.set(base + "total-ms", total);
        config.set(base + "count", count);
        config.set(base + "longest-ms", longest);
        save();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Override
    public void removePlayerData(UUID uuid) {
        config.set("rules." + uuid, null);
        config.set("acknowledged-notifications." + uuid, null);
        config.set("levels." + uuid, null);
        config.set("presets." + uuid, null);
        config.set("preferences." + uuid, null);
        config.set("stats." + uuid, null);
        save();
    }

    // ── Migration support ─────────────────────────────────────────────────────

    @Override
    public Set<UUID> getAllKnownPlayers() {
        Set<UUID> uuids = new HashSet<>();
        for (String s : config.getStringList("vanished-players")) {
            try { uuids.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        for (String section : List.of("rules", "levels", "acknowledged-notifications", "presets", "preferences", "stats")) {
            ConfigurationSection cs = config.getConfigurationSection(section);
            if (cs != null) {
                for (String key : cs.getKeys(false)) {
                    try { uuids.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return uuids;
    }

    @Override
    public Set<String> getAcknowledgements(UUID uuid) {
        return new HashSet<>(config.getStringList("acknowledged-notifications." + uuid));
    }
}
