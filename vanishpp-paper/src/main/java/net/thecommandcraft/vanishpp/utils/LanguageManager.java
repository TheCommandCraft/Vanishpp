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
    /** Raw config of each language file type, used by getStringList for cross-file list lookups. */
    private final Map<String, YamlConfiguration> typeConfigs = new HashMap<>();
    private String currentLang;

    // GUI text lives in the messages files; only messages and the separate scoreboards
    // files are loaded. (The old gui_*.yml files were deleted when their keys were
    // integrated into messages_*.yml.)
    private static final String[] FILE_TYPES = {"messages", "scoreboards"};

    public LanguageManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        String lang = plugin.getConfigManager().getLanguage();
        loadLanguage(lang);
    }

    private void loadLanguage(String lang) {
        messages.clear();
        typeConfigs.clear();
        currentLang = lang;
        int loaded = 0;

        for (String type : FILE_TYPES) {
            YamlConfiguration config = loadRaw(type, lang);
            if (config == null) {
                continue;
            }
            typeConfigs.put(type, config);

            // Normalize keys into the "type.flatKey" namespace and strip any redundant
            // top-level wrapper the file itself carries (e.g. scoreboards files have
            // their own "scoreboards:" root). Callers can then hit keys either as flat
            // keys ("config.reloaded") or with a type prefix ("messages.config.reloaded",
            // "scoreboards.title", "gui.admin.title"). List keys are handled by
            // getStringList and never enter the string table.
            for (String key : config.getKeys(true)) {
                if (config.isString(key)) {
                    String flatKey = stripTypeWrapper(key, type);
                    messages.put(type + "." + flatKey, config.getString(key));
                }
            }
            loaded += config.getKeys(true).size();
        }
        plugin.getLogger().info("Loaded " + loaded + " messages for language: " + lang);
    }

    /**
     * Loads one language file for a type (tries the requested language first, falls back
     * to en-us). Returns null if the type cannot be loaded. Tolerates both "-" and "_"
     * separators in the language code so the file name resolves whether it lives in the
     * plugin data folder or inside the jar.
     */
    private YamlConfiguration loadRaw(String type, String lang) {
        String fileName = type + "_" + lang + ".yml";
        // 1. Plugin data folder (user customizations take priority)
        for (String candidate : fileNameVariants(fileName)) {
            File langFile = new File(plugin.getDataFolder(), "languages/" + candidate);
            if (langFile.exists()) {
                return YamlConfiguration.loadConfiguration(langFile);
            }
        }
        // 2. Resources inside the jar
        for (String candidate : fileNameVariants(fileName)) {
            InputStream in = plugin.getResource("languages/" + candidate);
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    /** Generates both "-" and "_" separated candidates for a file name, covering the historical gui_en-us / gui_en_us naming difference. */
    private String[] fileNameVariants(String fileName) {
        if (fileName.contains("_")) {
            return new String[]{fileName, fileName.replace("_", "-")};
        }
        return new String[]{fileName};
    }

    /** Normalizes a raw key from a file: if it starts with "type." (the file wraps its keys under a same-named top level), strip that wrapper. */
    private String stripTypeWrapper(String key, String type) {
        String prefix = type + ".";
        if (key.startsWith(prefix)) {
            return key.substring(prefix.length());
        }
        return key;
    }

    public String getMessage(String key) {
        String msg = messages.get(key);
        // Resolve into the messages namespace:
        //  - flat keys ("config.reloaded", "console-specify") → "messages.config.reloaded"
        //  - GUI keys ("gui.admin-dashboard.title") → "messages.gui.admin-dashboard.title"
        //  - already-prefixed keys ("messages.x", "scoreboards.x") hit directly
        if (msg == null && !key.startsWith("messages.") && !key.startsWith("scoreboards.")) {
            msg = messages.get("messages." + key);
        }
        if (msg == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            return "<red>[Missing: " + key + "]";
        }
        return msg;
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        String msg = getMessage(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                msg = msg.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return msg;
    }

    public String getMessage(String key, Object... placeholders) {
        String msg = getMessage(key);
        for (int i = 0; i < placeholders.length - 1; i += 2) {
            if (placeholders[i] instanceof String) {
                msg = msg.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
            }
        }
        return msg;
    }

    /**
     * Gets a string list. List keys may live in different type files: flat list keys in the
     * messages file (e.g. "changelog.entries") and list keys in the scoreboards file
     * (e.g. "scoreboards.lines"). All loaded type files are searched in turn.
     */
    public List<String> getStringList(String key) {
        for (YamlConfiguration config : typeConfigs.values()) {
            if (config.isList(key)) {
                return config.getStringList(key);
            }
        }
        // Handle scoreboards files wrapped under a "scoreboards:" top level
        if (!key.startsWith("scoreboards.")) {
            YamlConfiguration sb = typeConfigs.get("scoreboards");
            if (sb != null && sb.isList("scoreboards." + key)) {
                return sb.getStringList("scoreboards." + key);
            }
        }
        plugin.getLogger().warning("Missing string list key: " + key);
        return java.util.Collections.emptyList();
    }

    public String getCurrentLanguage() {
        return currentLang;
    }
}