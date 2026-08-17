package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LanguageManager {
    private final Vanishpp plugin;
    private final Map<String, String> messages = new HashMap<>();
    private YamlConfiguration messagesConfig;
    private String currentLang;

    public LanguageManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        String lang = plugin.getConfigManager().getLanguage();
        loadLanguage(lang);
    }

    private void loadLanguage(String lang) {
        messages.clear();
        currentLang = lang;

        YamlConfiguration config = loadRaw(lang);
        if (config == null && !"en-us".equals(lang)) {
            // Fall back to the bundled default language
            config = loadRaw("en-us");
        }
        if (config == null) {
            plugin.getLogger().warning("Could not load any language file (tried " + lang + " and en-us).");
            return;
        }

        messagesConfig = config;
        for (String key : config.getKeys(true)) {
            if (config.isString(key)) {
                messages.put(key, config.getString(key));
            }
        }
        plugin.getLogger().info("Loaded " + messages.size() + " messages for language: " + currentLang);
    }

    /** Loads a language file from the plugin data folder first, then falls back to the jar. */
    private YamlConfiguration loadRaw(String lang) {
        String fileName = "messages_" + lang + ".yml";
        for (String candidate : fileNameVariants(fileName)) {
            File langFile = new File(plugin.getDataFolder(), "languages/" + candidate);
            if (langFile.exists()) {
                return YamlConfiguration.loadConfiguration(langFile);
            }
        }
        for (String candidate : fileNameVariants(fileName)) {
            InputStream in = plugin.getResource("languages/" + candidate);
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    /** Generates both "-" and "_" separated candidates for a file name, covering historical naming differences. */
    private String[] fileNameVariants(String fileName) {
        if (fileName.contains("_")) {
            return new String[]{fileName, fileName.replace("_", "-")};
        }
        return new String[]{fileName};
    }

    public String getMessage(String path) {
        String msg = messages.get(path);
        if (msg == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return "<red>[Missing: " + path + "]";
        }
        return msg;
    }

    public List<String> getStringList(String path) {
        if (messagesConfig != null && messagesConfig.isList(path)) {
            return messagesConfig.getStringList(path);
        }
        plugin.getLogger().warning("Missing string list key: " + path);
        return java.util.Collections.emptyList();
    }

    public String getCurrentLanguage() {
        return currentLang;
    }
}
