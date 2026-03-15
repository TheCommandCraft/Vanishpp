package net.thecommandcraft.vanishpp.storage;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class YamlStorage implements StorageProvider {

    private final Vanishpp plugin;
    private File file;
    private FileConfiguration config;

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
            try {
                this.file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create data.yml!");
            }
        }

        this.config = YamlConfiguration.loadConfiguration(this.file);
    }

    @Override
    public void shutdown() {
        save();
    }

    private synchronized void save() {
        try {
            this.config.save(this.file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml!");
        }
    }

    @Override
    public boolean isVanished(UUID uuid) {
        List<String> vanished = config.getStringList("vanished-players");
        return vanished.contains(uuid.toString());
    }

    @Override
    public void setVanished(UUID uuid, boolean vanished) {
        List<String> list = new ArrayList<>(config.getStringList("vanished-players"));
        if (vanished) {
            if (!list.contains(uuid.toString()))
                list.add(uuid.toString());
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

    @Override
    public boolean getRule(UUID uuid, String rule, boolean defaultValue) {
        String path = "rules." + uuid.toString() + "." + rule;
        return config.getBoolean(path, defaultValue);
    }

    @Override
    public void setRule(UUID uuid, String rule, Object value) {
        config.set("rules." + uuid.toString() + "." + rule, value);
        save();
    }

    @Override
    public Map<String, Object> getRules(UUID uuid) {
        Map<String, Object> rules = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("rules." + uuid.toString());
        if (section != null) {
            for (String key : section.getKeys(false)) {
                rules.put(key, section.get(key));
            }
        }
        return rules;
    }

    @Override
    public boolean hasAcknowledged(UUID uuid, String notificationId) {
        List<String> acknowledged = config.getStringList("acknowledged-notifications." + uuid.toString());
        return acknowledged.contains(notificationId);
    }

    @Override
    public void addAcknowledgement(UUID uuid, String notificationId) {
        List<String> list = new ArrayList<>(config.getStringList("acknowledged-notifications." + uuid.toString()));
        if (!list.contains(notificationId)) {
            list.add(notificationId);
            config.set("acknowledged-notifications." + uuid.toString(), list);
            save();
        }
    }

    @Override
    public int getVanishLevel(UUID uuid) {
        return config.getInt("levels." + uuid.toString(), 1);
    }

    @Override
    public void setVanishLevel(UUID uuid, int level) {
        config.set("levels." + uuid.toString(), level);
        save();
    }

    @Override
    public void removePlayerData(UUID uuid) {
        config.set("rules." + uuid.toString(), null);
        config.set("acknowledged-notifications." + uuid.toString(), null);
        config.set("levels." + uuid.toString(), null);
        save();
    }
}
