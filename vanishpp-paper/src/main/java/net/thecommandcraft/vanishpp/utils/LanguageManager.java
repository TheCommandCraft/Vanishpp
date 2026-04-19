package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LanguageManager {
    private final Vanishpp plugin;
    private FileConfiguration langConfig;

    public LanguageManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!msgFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        langConfig = YamlConfiguration.loadConfiguration(msgFile);

        // Fill missing keys from bundled default and write them back to disk
        InputStream defMsgStream = plugin.getResource("messages.yml");
        if (defMsgStream != null) {
            YamlConfiguration defConfig = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(defMsgStream, StandardCharsets.UTF_8));
            langConfig.setDefaults(defConfig);

            boolean dirty = false;
            for (String key : defConfig.getKeys(true)) {
                if (!defConfig.isConfigurationSection(key) && !langConfig.isSet(key)) {
                    langConfig.set(key, defConfig.get(key));
                    dirty = true;
                }
            }
            if (dirty) {
                try {
                    langConfig.save(msgFile);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to write new message keys to messages.yml: " + e.getMessage());
                }
            }
        }
    }

    public String getMessage(String path) {
        String msg = langConfig.getString(path);
        if (msg == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return "<red>[Missing: " + path + "]";
        }
        return msg;
    }

    public java.util.List<String> getStringList(String path) {
        return langConfig.getStringList(path);
    }
}
