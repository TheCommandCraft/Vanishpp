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
    private final String defaultLang = "en";

    public LanguageManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load(String langCode) {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // Always save default language to the folder if it doesn't exist
        File defFile = new File(langFolder, defaultLang + ".yml");
        if (!defFile.exists()) {
            plugin.saveResource("lang/" + defaultLang + ".yml", false);
        }

        File langFile = new File(langFolder, langCode + ".yml");
        if (!langFile.exists()) {
            // If requested language doesn't exist, we'll use whatever we have or fallback
            // to internal
            plugin.getLogger()
                    .warning("Language file " + langCode + ".yml not found. Falling back to internal defaults.");
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // Fallback to internal English resources if keys are missing
        InputStream defLangStream = plugin.getResource("lang/" + defaultLang + ".yml");
        if (defLangStream != null) {
            YamlConfiguration defConfig = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(defLangStream, StandardCharsets.UTF_8));
            langConfig.setDefaults(defConfig);
        }
    }

    public String getMessage(String path) {
        return langConfig.getString(path, path);
    }
}
