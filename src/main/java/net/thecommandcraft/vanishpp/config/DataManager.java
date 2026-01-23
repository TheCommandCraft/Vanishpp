package net.thecommandcraft.vanishpp.config;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class DataManager {

    private final Vanishpp plugin;
    private File file;
    private FileConfiguration config;

    public DataManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
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

    public void save() {
        try {
            this.config.save(this.file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml!");
        }
    }

    public FileConfiguration getConfig() {
        return this.config;
    }
}