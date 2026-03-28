package net.thecommandcraft.vanishpp.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.ConfigManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private final Vanishpp plugin;
    private static final String PROJECT_SLUG = "vanish%2B%2B";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/vanish++";
    private static final String API_URL = "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";

    private static final String TEST_VERSION = null;

    private String latestVersion;
    private boolean updateAvailable;

    public UpdateChecker(Vanishpp plugin) {
        this.plugin = plugin;
        this.updateAvailable = false;
    }

    public void check() {
        if (!plugin.getConfigManager().updateCheckerEnabled) return;
        plugin.getVanishScheduler().runAsync(this::fetchAndCompare);
    }

    public void startPeriodicCheck() {
        if (!plugin.getConfigManager().updateCheckerEnabled) return;
        // Re-check every 6 hours (6 * 60 * 60 * 20 = 432000 ticks)
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::fetchAndCompare, 432000L, 432000L);
    }

    private void fetchAndCompare() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent",
                    "TheCommandCraft/Vanishpp/" + plugin.getDescription().getVersion());

            try {
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    plugin.getLogger().warning("[UpdateChecker] Modrinth returned HTTP " + responseCode);
                    return;
                }
                try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (!element.isJsonArray()) return;
                    JsonArray versions = element.getAsJsonArray();
                    if (versions.size() == 0) return;

                    JsonObject latest = versions.get(0).getAsJsonObject();
                    String remoteVersion = latest.get("version_number").getAsString();
                    String currentVersion = TEST_VERSION != null ? TEST_VERSION : plugin.getDescription().getVersion();

                    String cleanRemote = remoteVersion.replaceAll("[^0-9.]", "");
                    String cleanCurrent = currentVersion.replaceAll("[^0-9.]", "");

                    if (isNewer(cleanCurrent, cleanRemote)) {
                        this.latestVersion = remoteVersion;
                        this.updateAvailable = true;
                        plugin.getLogger().warning("---------------------------------------------------");
                        plugin.getLogger().warning("A new version of Vanish++ is available: " + remoteVersion);
                        plugin.getLogger().warning("Download: " + MODRINTH_URL);
                        plugin.getLogger().warning("---------------------------------------------------");
                    } else {
                        this.updateAvailable = false;
                        plugin.getLogger().info("[UpdateChecker] Vanish++ is up to date (running " + currentVersion + ").");
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[UpdateChecker] Check failed: " + e.getMessage());
        }
    }

    /** Returns true if remoteVersion is newer than currentVersion. */
    private boolean isNewer(String current, String remote) {
        try {
            String[] c = current.split("\\.");
            String[] r = remote.split("\\.");
            int len = Math.max(c.length, r.length);
            for (int i = 0; i < len; i++) {
                int cv = i < c.length ? Integer.parseInt(c[i]) : 0;
                int rv = i < r.length ? Integer.parseInt(r[i]) : 0;
                if (rv > cv) return true;
                if (rv < cv) return false;
            }
        } catch (NumberFormatException e) {
            return !current.equalsIgnoreCase(remote);
        }
        return false;
    }

    public void notifyPlayer(Player player) {
        if (!updateAvailable) return;

        ConfigManager cm = plugin.getConfigManager();
        boolean shouldNotify = false;

        if (cm.updateCheckerMode.equalsIgnoreCase("LIST")) {
            shouldNotify = cm.updateCheckerList.contains(player.getName());
        } else {
            shouldNotify = player.hasPermission("vanishpp.update") || player.isOp();
        }

        if (!shouldNotify) return;

        LanguageManager lm = plugin.getConfigManager().getLanguageManager();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
        player.sendMessage(Component.empty());
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.available"));
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.current")
                .replace("%version%", TEST_VERSION != null ? TEST_VERSION : plugin.getDescription().getVersion()));
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.latest")
                .replace("%version%", latestVersion));

        String downloadBtn = lm.getMessage("update.download").replace("%version%", latestVersion);
        String hoverText  = lm.getMessage("update.hover").replace("%version%", latestVersion);
        String link = MODRINTH_URL + "/version/" + latestVersion;

        Component button = plugin.getMessageManager().parse(downloadBtn, player)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(plugin.getMessageManager().parse(hoverText, player)));
        player.sendMessage(button);
        player.sendMessage(Component.empty());
    }
}
