package net.thecommandcraft.vanishpp.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.thecommandcraft.vanishpp.velocity.messaging.PaperChannelDispatcher;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks Modrinth for Vanish++ Velocity proxy updates and notifies all connected
 * Paper servers when a new version is available.
 */
public class ProxyUpdateChecker {

    private final VanishppVelocity plugin;
    private final PaperChannelDispatcher dispatcher;

    private static final String API_URL_TEMPLATE = "https://api.modrinth.com/v2/project/%s/version";
    private static final String LOADER = "velocity";
    private static final String DOWNLOAD_URL_TEMPLATE = "https://modrinth.com/plugin/%s/version/%s";

    private volatile String latestVersion = null;
    private volatile String downloadUrl = null;
    private volatile boolean updateAvailable = false;
    private final AtomicBoolean checking = new AtomicBoolean(false);

    /** The Velocity proxy plugin's own version, injected from velocity-plugin.json at startup. */
    private final String currentVersion;
    /** Modrinth project slug/ID for the velocity plugin. */
    private final String modrinthId;

    public ProxyUpdateChecker(VanishppVelocity plugin, PaperChannelDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
        this.currentVersion = plugin.getProxy().getPluginManager()
                .getPlugin("vanishpp-velocity")
                .flatMap(c -> c.getDescription().getVersion())
                .orElse("unknown");
        this.modrinthId = plugin.getConfigManager().getSnapshot().updateCheckerId;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Run once on proxy startup, then every 6 hours. */
    public void startChecking() {
        if (!plugin.getConfigManager().getSnapshot().updateCheckerEnabled) return;

        // Immediate check
        plugin.getProxy().getScheduler()
                .buildTask(plugin, this::fetchAndBroadcast)
                .schedule();

        // Periodic check every 6 hours
        plugin.getProxy().getScheduler()
                .buildTask(plugin, this::fetchAndBroadcast)
                .delay(Duration.ofHours(6))
                .repeat(Duration.ofHours(6))
                .schedule();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Push the current update state to a single server (called on HELLO so new servers get it immediately). */
    public void notifyServer(com.velocitypowered.api.proxy.server.RegisteredServer server) {
        if (!updateAvailable) return;
        dispatcher.sendProxyUpdateNotify(server, currentVersion, latestVersion, downloadUrl);
    }

    public boolean isUpdateAvailable() { return updateAvailable; }
    public String getLatestVersion()   { return latestVersion; }

    // ── Check logic ───────────────────────────────────────────────────────────

    private void fetchAndBroadcast() {
        if (!checking.compareAndSet(false, true)) return; // already running
        try {
            String url = String.format(API_URL_TEMPLATE, modrinthId) + "?loaders=%5B%22" + LOADER + "%22%5D";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "TheCommandCraft/VanishppVelocity/" + currentVersion);

            try {
                int code = conn.getResponseCode();
                if (code != 200) {
                    plugin.getLogger().warn("[UpdateChecker] Modrinth returned HTTP {} for proxy check.", code);
                    return;
                }
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                    JsonArray versions = JsonParser.parseReader(reader).getAsJsonArray();
                    if (versions.isEmpty()) return;

                    JsonObject latest = versions.get(0).getAsJsonObject();
                    String remote = latest.get("version_number").getAsString();
                    String cleanRemote  = remote.replaceAll("[^0-9.]", "");
                    String cleanCurrent = currentVersion.replaceAll("[^0-9.]", "");

                    if (isNewer(cleanCurrent, cleanRemote)) {
                        this.latestVersion = remote;
                        this.downloadUrl   = String.format(DOWNLOAD_URL_TEMPLATE, modrinthId, remote);
                        this.updateAvailable = true;

                        plugin.getLogger().warn("---------------------------------------------------");
                        plugin.getLogger().warn("A new version of Vanish++ Velocity is available: {}", remote);
                        plugin.getLogger().warn("Download: {}", downloadUrl);
                        plugin.getLogger().warn("---------------------------------------------------");

                        broadcastToAllServers();
                    } else {
                        this.updateAvailable = false;
                        plugin.getLogger().info("[UpdateChecker] Vanish++ Velocity is up to date (running {}).", currentVersion);
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            plugin.getLogger().warn("[UpdateChecker] Proxy update check failed: {}", e.getMessage());
        } finally {
            checking.set(false);
        }
    }

    private void broadcastToAllServers() {
        for (var server : plugin.getProxy().getAllServers()) {
            notifyServer(server);
        }
    }

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
        } catch (NumberFormatException ignored) {
            return !current.equalsIgnoreCase(remote);
        }
        return false;
    }
}
