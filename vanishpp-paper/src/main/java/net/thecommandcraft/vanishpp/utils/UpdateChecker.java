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
    // Project ID instead of the slug: the slug was renamed and the API only redirects (308) old ones
    private static final String PROJECT_ID = "kbKpK1bc";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/vanish-plus-plus";
    private static final String API_BASE_URL = "https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version";

    private static final String TEST_VERSION = null;

    private String latestVersion;
    private String latestVersionId;
    private String latestVersionType;
    private boolean updateAvailable;
    private volatile Boolean lastCheckSucceeded; // null until the first check finishes

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
        // Re-check every 6 hours (6 * 60 * 60 * 20 = 432000 ticks).
        // The timer callback runs on the main/global thread and must never block it:
        // dispatch the synchronous HTTP request (fetchAndCompare) onto an async thread,
        // so a slow or unreachable Modrinth API cannot stall the server thread.
        plugin.getVanishScheduler().runTimerGlobal(
                () -> plugin.getVanishScheduler().runAsync(this::fetchAndCompare),
                432000L, 432000L);
    }

    private void fetchAndCompare() {
        try {
            HttpURLConnection connection = open(buildApiUrl());

            try {
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    lastCheckSucceeded = false;
                    plugin.getLogger().warning("[UpdateChecker] Modrinth returned HTTP " + responseCode);
                    return;
                }
                try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (!element.isJsonArray()) return;
                    JsonArray versions = element.getAsJsonArray();
                    if (versions.size() == 0) return;

                    boolean showBeta  = plugin.getConfigManager().updateCheckerShowBeta;
                    boolean showAlpha = plugin.getConfigManager().updateCheckerShowAlpha;

                    // Find the best candidate: highest semver, tiebreak by release > beta > alpha
                    JsonObject best = null;
                    for (JsonElement el : versions) {
                        JsonObject v = el.getAsJsonObject();
                        String type = v.has("version_type")
                                ? v.get("version_type").getAsString() : "release";
                        if (type.equals("beta")  && !showBeta)  continue;
                        if (type.equals("alpha") && !showAlpha) continue;
                        if (best == null || compareVersionObjects(v, best) > 0) best = v;
                    }
                    if (best == null) return;

                    String remoteVersion  = best.get("version_number").getAsString();
                    String remoteType     = best.has("version_type")
                            ? best.get("version_type").getAsString() : "release";
                    String currentVersion = TEST_VERSION != null
                            ? TEST_VERSION : plugin.getDescription().getVersion();

                    lastCheckSucceeded = true;
                    String cleanRemote  = remoteVersion.replaceAll("[^0-9.]", "");
                    String cleanCurrent = currentVersion.replaceAll("[^0-9.]", "");

                    int semverCmp = compareSemver(cleanCurrent, cleanRemote);
                    // Newer if: higher semver, OR same semver but higher release tier
                    // (e.g. running 1.1.8-beta and a release build of 1.1.8 is out)
                    boolean isNewer = semverCmp < 0
                            || (semverCmp == 0
                                && releaseWeight(remoteType) > releaseWeight(currentReleaseType()));

                    if (isNewer) {
                        this.latestVersion = remoteVersion;
                        this.latestVersionId = best.has("id") ? best.get("id").getAsString() : null;
                        this.latestVersionType = remoteType;
                        this.updateAvailable = true;
                        plugin.getLogger().warning("---------------------------------------------------");
                        plugin.getLogger().warning("A new version of Vanish++ is available: "
                                + remoteVersion + " [" + remoteType + "]");
                        plugin.getLogger().warning("Download: " + getDownloadUrl());
                        plugin.getLogger().warning("---------------------------------------------------");
                    } else {
                        this.updateAvailable = false;
                        plugin.getLogger().info("[UpdateChecker] Vanish++ is up to date (running "
                                + currentVersion + ").");
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            lastCheckSucceeded = false;
            plugin.getLogger().warning("[UpdateChecker] Check failed: " + e.getMessage());
        }
    }

    /**
     * Opens a GET connection, following up to 5 redirects. HttpURLConnection doesn't follow
     * 307/308 on its own, and Modrinth answers renamed slugs with a 308 - which silently broke
     * update checks for every version before 1.1.9.
     */
    private HttpURLConnection open(String url) throws java.io.IOException {
        URL current = new URL(url);
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent",
                    "TheCommandCraft/Vanishpp/" + plugin.getDescription().getVersion());
            int code = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (code < 300 || code > 399 || code == 304 || location == null)
                return connection;
            connection.disconnect();
            URL next = new URL(current, location);
            if (redirects >= 5 || !"https".equals(next.getProtocol()))
                throw new java.io.IOException("Unsafe or too many redirects (HTTP " + code + " to " + next + ")");
            current = next;
        }
    }

    /** null if no check has finished yet, otherwise whether the last check reached Modrinth. */
    public Boolean getLastCheckSucceeded() {
        return lastCheckSucceeded;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    /** The newest version found by the last check, or null if none was found yet. */
    public String getLatestVersion() {
        return latestVersion;
    }

    /** Link to the exact Modrinth version of the update, or the project page if unknown. */
    public String getDownloadUrl() {
        if (latestVersionId != null) return MODRINTH_URL + "/version/" + latestVersionId;
        if (latestVersion != null) return MODRINTH_URL + "/version/" + latestVersion;
        return MODRINTH_URL;
    }

    /**
     * Compares two version JSON objects. Returns positive if {@code a} is "better" than {@code b}:
     * higher semver wins; on equal semver, higher release tier wins (release > beta > alpha).
     */
    private int compareVersionObjects(JsonObject a, JsonObject b) {
        String numA = a.get("version_number").getAsString().replaceAll("[^0-9.]", "");
        String numB = b.get("version_number").getAsString().replaceAll("[^0-9.]", "");
        int semver = compareSemver(numA, numB);
        if (semver != 0) return semver;
        String typeA = a.has("version_type") ? a.get("version_type").getAsString() : "release";
        String typeB = b.has("version_type") ? b.get("version_type").getAsString() : "release";
        return Integer.compare(releaseWeight(typeA), releaseWeight(typeB));
    }

    /** release=3, beta=2, alpha=1, unknown=0 */
    private int releaseWeight(String type) {
        return switch (type.toLowerCase()) {
            case "release" -> 3;
            case "beta"    -> 2;
            case "alpha"   -> 1;
            default        -> 0;
        };
    }

    /** Infers the release type of the currently running build from its version string. */
    private String currentReleaseType() {
        String v = plugin.getDescription().getVersion().toLowerCase();
        if (v.contains("alpha")) return "alpha";
        if (v.contains("beta"))  return "beta";
        return "release";
    }

    /** Compares two semver strings. Returns negative if a < b, 0 if equal, positive if a > b. */
    private int compareSemver(String a, String b) {
        try {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int len = Math.max(pa.length, pb.length);
            for (int i = 0; i < len; i++) {
                int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
                if (va != vb) return Integer.compare(va, vb);
            }
        } catch (NumberFormatException ignored) {}
        return a.compareToIgnoreCase(b);
    }

    private String buildApiUrl() {
        return API_BASE_URL + "?loaders=%5B%22" + detectLoader() + "%22%5D";
    }

    /** Maps the running server's name to its Modrinth loader slug. */
    private String detectLoader() {
        String name = org.bukkit.Bukkit.getName().toLowerCase();
        if (name.contains("folia"))  return "folia";
        if (name.contains("purpur")) return "purpur";
        if (name.contains("paper"))  return "paper";
        if (name.contains("spigot")) return "spigot";
        return "bukkit";
    }

    /**
     * Notifies a player that the Vanish++ Velocity proxy plugin has an update.
     * Only shown when proxy mode is active and a proxy update is known.
     * Uses the same permission/list rules as the Paper plugin update notification.
     */
    public void notifyPlayerProxyUpdate(Player player) {
        if (plugin.getProxyBridge() == null) return;
        if (!plugin.getProxyBridge().isProxyUpdateAvailable()) return;
        if (!shouldNotify(player)) return;

        String currentV  = plugin.getProxyBridge().getProxyCurrentVersion();
        String latestV   = plugin.getProxyBridge().getProxyLatestVersion();
        String downloadU = plugin.getProxyBridge().getProxyDownloadUrl();

        LanguageManager lm = plugin.getConfigManager().getLanguageManager();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
        player.sendMessage(Component.empty());
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.proxy-available"));
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.proxy-note"));
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.proxy-current")
                .replace("%version%", currentV));
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.proxy-latest")
                .replace("%version%", latestV));

        String downloadBtn = lm.getMessage("update.proxy-download").replace("%version%", latestV);
        String hoverText   = lm.getMessage("update.proxy-hover").replace("%version%", latestV);

        Component button = plugin.getMessageManager().parse(downloadBtn, player)
                .clickEvent(ClickEvent.openUrl(downloadU))
                .hoverEvent(HoverEvent.showText(plugin.getMessageManager().parse(hoverText, player)));
        player.sendMessage(button);
        player.sendMessage(Component.empty());
    }

    private boolean shouldNotify(Player player) {
        ConfigManager cm = plugin.getConfigManager();
        if (cm.updateCheckerMode.equalsIgnoreCase("LIST")) {
            return cm.updateCheckerList.contains(player.getName());
        }
        return player.hasPermission("vanishpp.update") || player.isOp();
    }

    public void notifyPlayer(Player player) {
        if (!updateAvailable) return;
        if (!shouldNotify(player)) return;

        LanguageManager lm = plugin.getConfigManager().getLanguageManager();
        String type = latestVersionType != null ? latestVersionType : "release";
        String currentVersion = TEST_VERSION != null ? TEST_VERSION : plugin.getDescription().getVersion();

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
        player.sendMessage(Component.empty());
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.available"));
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.current")
                .replace("%version%", currentVersion));
        plugin.getMessageManager().sendMessage(player, lm.getMessage("update.latest")
                .replace("%version%", latestVersion)
                .replace("%type%", type));

        String link = getDownloadUrl();
        String downloadBtn = lm.getMessage("update.download").replace("%version%", latestVersion);
        String hoverText   = lm.getMessage("update.hover").replace("%version%", latestVersion);
        Component button = plugin.getMessageManager().parse(downloadBtn, player)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(plugin.getMessageManager().parse(hoverText, player)));
        player.sendMessage(button);

        // Show hide-buttons for beta/alpha if that's what the update is
        if (type.equals("beta") && plugin.getConfigManager().updateCheckerShowBeta) {
            Component hideBtn = plugin.getMessageManager()
                    .parse(lm.getMessage("update.hide-beta"), player)
                    .clickEvent(ClickEvent.runCommand("/vconfig update-checker.show-beta false"))
                    .hoverEvent(HoverEvent.showText(plugin.getMessageManager()
                            .parse(lm.getMessage("update.hide-beta-hover"), player)));
            player.sendMessage(hideBtn);
        } else if (type.equals("alpha") && plugin.getConfigManager().updateCheckerShowAlpha) {
            Component hideBtn = plugin.getMessageManager()
                    .parse(lm.getMessage("update.hide-alpha"), player)
                    .clickEvent(ClickEvent.runCommand("/vconfig update-checker.show-alpha false"))
                    .hoverEvent(HoverEvent.showText(plugin.getMessageManager()
                            .parse(lm.getMessage("update.hide-alpha-hover"), player)));
            player.sendMessage(hideBtn);
        }
        player.sendMessage(Component.empty());
    }
}
