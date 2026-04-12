package net.thecommandcraft.vanishpp.zone;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Persists no-vanish zones to zones.yml in the plugin data folder.
 * Thread-safe reads; writes are synchronous (called from main thread only).
 */
public class VanishZoneManager {

    private final Vanishpp plugin;
    private final File dataFile;
    private final Map<String, VanishZone> zones = Collections.synchronizedMap(new LinkedHashMap<>());

    public VanishZoneManager(Vanishpp plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "zones.yml");
        load();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        zones.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = cfg.getConfigurationSection("zones");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection z = sec.getConfigurationSection(key);
            if (z == null) continue;
            VanishZone zone = new VanishZone(
                    key,
                    z.getString("world", "world"),
                    z.getDouble("x"),
                    z.getDouble("y"),
                    z.getDouble("z"),
                    z.getDouble("radius", 32),
                    z.getBoolean("force-unvanish", true)
            );
            zones.put(key.toLowerCase(), zone);
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        synchronized (zones) {
            for (Map.Entry<String, VanishZone> entry : zones.entrySet()) {
                VanishZone z = entry.getValue();
                String path = "zones." + entry.getKey();
                cfg.set(path + ".world", z.getWorldName());
                cfg.set(path + ".x", z.getX());
                cfg.set(path + ".y", z.getY());
                cfg.set(path + ".z", z.getZ());
                cfg.set(path + ".radius", z.getRadius());
                cfg.set(path + ".force-unvanish", z.isForceUnvanish());
            }
        }
        try {
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save zones.yml", e);
        }
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /** @return true if the zone was created (false if name already taken). */
    public boolean createZone(String name, Location center, double radius, boolean forceUnvanish) {
        String key = name.toLowerCase();
        if (zones.containsKey(key)) return false;
        zones.put(key, new VanishZone(name, center, radius, forceUnvanish));
        save();
        return true;
    }

    /** @return true if a zone with this name existed and was removed. */
    public boolean deleteZone(String name) {
        boolean removed = zones.remove(name.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }

    public Collection<VanishZone> getAllZones() {
        synchronized (zones) {
            return Collections.unmodifiableCollection(zones.values());
        }
    }

    public VanishZone getZone(String name) {
        return zones.get(name.toLowerCase());
    }

    /** Returns the first zone that contains the given location, or {@code null}. */
    public VanishZone getZoneAt(Location location) {
        synchronized (zones) {
            for (VanishZone z : zones.values()) {
                if (z.contains(location)) return z;
            }
        }
        return null;
    }

    public int size() {
        return zones.size();
    }
}
