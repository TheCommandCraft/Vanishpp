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

        // Fallback to internal english resources if keys are missing
        InputStream defMsgStream = plugin.getResource("messages.yml");
        if (defMsgStream != null) {
            YamlConfiguration defConfig = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(defMsgStream, StandardCharsets.UTF_8));
            langConfig.setDefaults(defConfig);
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
}
