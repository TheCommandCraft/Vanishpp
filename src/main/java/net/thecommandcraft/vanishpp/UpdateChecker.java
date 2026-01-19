package net.thecommandcraft.vanishpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private final Vanishpp plugin;
    private final String modrinthId;
    private String latestVersion;
    private boolean updateAvailable;

    public UpdateChecker(Vanishpp plugin) {
        this.plugin = plugin;
        this.modrinthId = plugin.getConfigManager().updateCheckerId;
        this.updateAvailable = false;
    }

    public void check() {
        if (!plugin.getConfigManager().updateCheckerEnabled) {
            return;
        }

        plugin.getLogger().info("Checking for updates on Modrinth (ID: " + modrinthId + ")...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Modrinth API v2
                URL url = new URL("https://api.modrinth.com/v2/project/" + modrinthId + "/version");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "TheCommandCraft/Vanishpp/" + plugin.getDescription().getVersion());

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                    JsonElement element = JsonParser.parseReader(reader);

                    if (element.isJsonArray()) {
                        JsonArray versions = element.getAsJsonArray();
                        if (versions.size() > 0) {
                            JsonObject latest = versions.get(0).getAsJsonObject();
                            String remoteVersion = latest.get("version_number").getAsString();
                            String currentVersion = plugin.getDescription().getVersion();

                            // Normalize versions (remove 'v' prefix if present)
                            String cleanRemote = remoteVersion.replaceAll("[^0-9.]", "");
                            String cleanCurrent = currentVersion.replaceAll("[^0-9.]", "");

                            plugin.getLogger().info("Version Check: Local [" + cleanCurrent + "] vs Remote [" + cleanRemote + "]");

                            if (isVersionNewer(cleanCurrent, cleanRemote)) {
                                this.latestVersion = remoteVersion;
                                this.updateAvailable = true;
                                plugin.getLogger().warning("--------------------------------------------------");
                                plugin.getLogger().warning("A new version of Vanish++ is available: " + remoteVersion);
                                plugin.getLogger().warning("Download at: https://modrinth.com/project/" + modrinthId);
                                plugin.getLogger().warning("--------------------------------------------------");
                            } else {
                                plugin.getLogger().info("You are running the latest version.");
                            }
                        }
                    } else {
                        plugin.getLogger().warning("Modrinth API returned unexpected JSON format.");
                    }
                    reader.close();
                } else {
                    plugin.getLogger().warning("Failed to connect to Modrinth. Response Code: " + responseCode);
                    if (responseCode == 404) {
                        plugin.getLogger().warning("Project ID '" + modrinthId + "' not found on Modrinth. Check config.yml.");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Update check failed: " + e.getMessage());
            }
        });
    }

    /**
     * Compares semantic versions (e.g. 1.1.0 vs 1.2.0)
     * Returns true if remote is strictly NEWER than current.
     */
    private boolean isVersionNewer(String current, String remote) {
        try {
            String[] currentParts = current.split("\\.");
            String[] remoteParts = remote.split("\\.");

            int length = Math.max(currentParts.length, remoteParts.length);

            for (int i = 0; i < length; i++) {
                int v1 = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int v2 = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;

                if (v2 > v1) return true;  // Remote is newer
                if (v2 < v1) return false; // Local is newer (dev build)
            }
        } catch (NumberFormatException e) {
            // Fallback for complex version strings
            return !current.equalsIgnoreCase(remote);
        }
        return false; // Versions are identical
    }

    public void notifyPlayer(Player player) {
        if (!updateAvailable) return;

        ConfigManager cm = plugin.getConfigManager();
        boolean shouldNotify = false;

        // Mode Logic
        if (cm.updateCheckerMode.equalsIgnoreCase("LIST")) {
            if (cm.updateCheckerList.contains(player.getName())) {
                shouldNotify = true;
            }
        } else {
            // Default to PERMISSION mode
            if (player.hasPermission("vanishpp.update") || player.isOp()) {
                shouldNotify = true;
            }
        }

        if (shouldNotify) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("Vanish++ Update Available!", NamedTextColor.GREEN, TextDecoration.BOLD));
            player.sendMessage(Component.text("Current: ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.getDescription().getVersion(), NamedTextColor.RED)));
            player.sendMessage(Component.text("Latest: ", NamedTextColor.GRAY)
                    .append(Component.text(latestVersion, NamedTextColor.GREEN)));

            String link = "https://modrinth.com/project/" + modrinthId;
            player.sendMessage(Component.text("[CLICK TO DOWNLOAD]", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.openUrl(link))
                    .hoverEvent(HoverEvent.showText(Component.text("Go to Modrinth", NamedTextColor.YELLOW))));
            player.sendMessage(Component.empty());
        }
    }
}