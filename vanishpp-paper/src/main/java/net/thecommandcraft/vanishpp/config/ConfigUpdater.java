package net.thecommandcraft.vanishpp.config;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Enhanced Config Updater that properly handles indentation and empty collections.
 */
public class ConfigUpdater {

    private final Vanishpp plugin;

    public ConfigUpdater(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void update(File configFile, String resourceName) {
        try {
            List<String> templateLines = loadResourceLines(resourceName);
            List<String> currentLines = loadFileLines(configFile);
            YamlConfiguration currentYaml = YamlConfiguration.loadConfiguration(configFile);
            YamlConfiguration templateYaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(plugin.getResource(resourceName), StandardCharsets.UTF_8));

            List<String> outputLines = new ArrayList<>(currentLines);
            boolean modified = false;

            // Use a list of all keys from template
            Set<String> templateKeys = templateYaml.getKeys(true);
            
            for (String key : templateKeys) {
                // Double check if key really exists to avoid duplicates
                if (!currentYaml.contains(key)) {
                    modified = appendMissingKey(key, templateYaml, templateLines, outputLines) || modified;
                    // Important: refresh YamlConfiguration view so we don't double-add subkeys 
                    // if their parent was just added
                    currentYaml = loadFromLines(outputLines);
                }
            }

            int targetVersion = templateYaml.getInt("config-version");
            if (currentYaml.getInt("config-version") != targetVersion) {
                modified = updateKeyLine("config-version", String.valueOf(targetVersion), outputLines) || modified;
            }

            if (modified) {
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8))) {
                    for (String line : outputLines) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
                plugin.getLogger().info("Successfully auto-completed missing keys in " + configFile.getName());
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to auto-update config file: " + e.getMessage());
        }
    }

    private YamlConfiguration loadFromLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(sb.toString());
        } catch (Exception ignored) {}
        return config;
    }

    private boolean updateKeyLine(String key, String newValue, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                int colonIdx = line.indexOf(":");
                String prefix = line.substring(0, colonIdx + 1);
                lines.set(i, prefix + " " + newValue);
                return true;
            }
        }
        return false;
    }

    private boolean appendMissingKey(String fullPath, YamlConfiguration templateYaml, List<String> templateLines, List<String> outputLines) {
        String[] parts = fullPath.split("\\.");
        
        int templateLineIdx = findLineForKey(fullPath, templateLines);
        if (templateLineIdx == -1) return false;

        String templateLine = templateLines.get(templateLineIdx);
        int indentLevel = getIndent(templateLine);
        String indentPrefix = " ".repeat(indentLevel);
        
        List<String> linesToAdd = new ArrayList<>();
        // Collect comments above the key
        for (int i = templateLineIdx - 1; i >= 0; i--) {
            String line = templateLines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                linesToAdd.add(0, line);
            } else {
                break;
            }
        }
        
        linesToAdd.add(indentPrefix + "# [Auto-Generated Default]");
        linesToAdd.add(templateLine);

        // Find the best place to insert
        int insertPos = findInsertionPoint(fullPath, outputLines);
        if (insertPos != -1) {
            outputLines.addAll(insertPos, linesToAdd);
            return true;
        } else {
            // Fallback for root keys
            if (parts.length == 1) {
                outputLines.add("");
                outputLines.addAll(linesToAdd);
                return true;
            }
            return false;
        }
    }

    private int findLineForKey(String fullPath, List<String> lines) {
        String[] parts = fullPath.split("\\.");
        int currentLevel = 0;
        int lastFoundLine = -1;
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue;
            
            int indent = getIndent(line);
            // Dynamic indentation check: only look for the current level's key
            // We expect the indent to be increasing or at least consistent with nesting
            if (trimmed.startsWith(parts[currentLevel] + ":")) {
                // Verify this is the correct level by checking its parentage if needed
                // Simple check: first root match, then next levels must have more indent
                if (currentLevel == 0 || indent > getIndent(lines.get(lastFoundLine))) {
                    lastFoundLine = i;
                    if (currentLevel == parts.length - 1) {
                        return i;
                    }
                    currentLevel++;
                }
            }
        }
        return -1;
    }

    private int findInsertionPoint(String fullPath, List<String> outputLines) {
        String[] parts = fullPath.split("\\.");
        if (parts.length == 1) return outputLines.size();

        // Find parent line
        String parentPath = String.join(".", Arrays.copyOfRange(parts, 0, parts.length - 1));
        int parentIdx = findLineForKey(parentPath, outputLines);
        
        if (parentIdx != -1) {
            int parentIndent = getIndent(outputLines.get(parentIdx));
            // Find the end of parent's section
            int lastContentLine = parentIdx;
            for (int i = parentIdx + 1; i < outputLines.size(); i++) {
                String line = outputLines.get(i);
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                if (getIndent(line) <= parentIndent) {
                    break;
                }
                lastContentLine = i;
            }
            return lastContentLine + 1;
        }
        return -1;
    }

    private int getIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private List<String> loadResourceLines(String name) throws IOException {
        try (InputStream in = plugin.getResource(name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        }
    }

    private List<String> loadFileLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
