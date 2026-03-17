package net.thecommandcraft.vanishpp.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.ConfigManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private final Vanishpp plugin;
    // Hardcoded Project ID (Slug) for Modrinth
    private static final String PROJECT_ID = "vanish++";

    private String latestVersion;
    private boolean updateAvailable;

    public UpdateChecker(Vanishpp plugin) {
        this.plugin = plugin;
        this.updateAvailable = false;
    }

    public void check() {
        // Only check if enabled in config
        if (!plugin.getConfigManager().updateCheckerEnabled) {
            return;
        }

        plugin.getLogger().info("Checking for updates on Modrinth...");

        plugin.getVanishScheduler().runAsync(() -> {
            try {
                // Modrinth API v2
                URL url = new URL("https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent",
                        "TheCommandCraft/Vanishpp/" + plugin.getDescription().getVersion());

                try {
                    int responseCode = connection.getResponseCode();
                    if (responseCode == 200) {
                        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                            JsonElement element = JsonParser.parseReader(reader);

                            if (element.isJsonArray()) {
                                JsonArray versions = element.getAsJsonArray();
                                if (versions.size() > 0) {
                                    JsonObject latest = versions.get(0).getAsJsonObject();
                                    String remoteVersion = latest.get("version_number").getAsString();
                                    String currentVersion = plugin.getDescription().getVersion();

                                    // Normalize versions
                                    String cleanRemote = remoteVersion.replaceAll("[^0-9.]", "");
                                    String cleanCurrent = currentVersion.replaceAll("[^0-9.]", "");

                                    if (isVersionNewer(cleanCurrent, cleanRemote)) {
                                        this.latestVersion = remoteVersion;
                                        this.updateAvailable = true;
                                        plugin.getLogger().warning("--------------------------------------------------");
                                        plugin.getLogger().warning("A new version of Vanish++ is available: " + remoteVersion);
                                        plugin.getLogger().warning("Download at: https://modrinth.com/plugin/" + PROJECT_ID
                                                + "/version/" + remoteVersion);
                                        plugin.getLogger().warning("--------------------------------------------------");
                                    } else {
                                        plugin.getLogger().info("You are running the latest version.");
                                    }
                                }
                            }
                        }
                    } else {
                        plugin.getLogger().warning("Failed to connect to Modrinth. Response Code: " + responseCode);
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Update check failed: " + e.getMessage());
            }
        });
    }

    private boolean isVersionNewer(String current, String remote) {
        try {
            String[] currentParts = current.split("\\.");
            String[] remoteParts = remote.split("\\.");

            int length = Math.max(currentParts.length, remoteParts.length);

            for (int i = 0; i < length; i++) {
                int v1 = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int v2 = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;

                if (v2 > v1)
                    return true;
                if (v2 < v1)
                    return false;
            }
        } catch (NumberFormatException e) {
            return !current.equalsIgnoreCase(remote);
        }
        return false;
    }

    public void notifyPlayer(Player player) {
        if (!updateAvailable)
            return;

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
            LanguageManager lm = plugin.getConfigManager().getLanguageManager();
            // Play Notification Sound
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);

            player.sendMessage(Component.empty());
            plugin.getMessageManager().sendMessage(player, lm.getMessage("update.available"));
            plugin.getMessageManager().sendMessage(player, lm.getMessage("update.current")
                    .replace("%version%", plugin.getDescription().getVersion()));
            plugin.getMessageManager().sendMessage(player, lm.getMessage("update.latest")
                    .replace("%version%", latestVersion));

            // New Link Structure: /plugin/ID/version/VERSION
            String link = "https://modrinth.com/plugin/" + PROJECT_ID + "/version/" + latestVersion;

            String downloadBtn = lm.getMessage("update.download")
                    .replace("%version%", latestVersion);
            String hoverText = lm.getMessage("update.hover")
                    .replace("%version%", latestVersion);

            player.sendMessage(plugin.getMessageManager().parse("<click:open_url:" + link + ">" +
                    "<hover:show_text:'" + hoverText + "'>" + downloadBtn + "</hover></click>", player));
            player.sendMessage(Component.empty());
        }
    }
}