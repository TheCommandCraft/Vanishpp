package net.thecommandcraft.vanishpp.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.thecommandcraft.vanishpp.common.update.UpdateSources;
import net.thecommandcraft.vanishpp.velocity.messaging.PaperChannelDispatcher;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks Modrinth for Vanish++ Velocity proxy updates and notifies all connected
 * Paper servers when a new version is available.
 */
public class ProxyUpdateChecker {

    private final VanishppVelocity plugin;
    private final PaperChannelDispatcher dispatcher;

    private static final String LOADER = "velocity";

    private volatile String latestVersion = null;
    private volatile String downloadUrl = null;
    private volatile boolean updateAvailable = false;
    private final AtomicBoolean checking = new AtomicBoolean(false);

    /** The Velocity proxy plugin's own version, injected from velocity-plugin.json at startup. */
    private final String currentVersion;

    public ProxyUpdateChecker(VanishppVelocity plugin, PaperChannelDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
        this.currentVersion = plugin.getProxy().getPluginManager()
                .getPlugin("vanishpp-velocity")
                .flatMap(c -> c.getDescription().getVersion())
                .orElse("unknown");
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
            // The project ID, not the old update-checker.modrinth-id setting: its default ("vanishpp")
            // never existed on Modrinth, so proxy update checks never worked before 1.1.9.1.
            HttpURLConnection conn = UpdateSources.open(UpdateSources.modrinthVersionsUrl(LOADER), userAgent());

            try {
                int code = conn.getResponseCode();
                if (code != 200) {
                    plugin.getLogger().warn("[UpdateChecker] Modrinth returned HTTP {} for proxy check, checking GitHub instead.", code);
                    checkGithub();
                    return;
                }
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                    JsonArray versions = JsonParser.parseReader(reader).getAsJsonArray();
                    if (versions.isEmpty()) return;

                    JsonObject latest = versions.get(0).getAsJsonObject();
                    String remote = latest.get("version_number").getAsString();
                    String id = latest.has("id") ? latest.get("id").getAsString() : null;
                    handleResult(remote, UpdateSources.downloadPage(id));
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            plugin.getLogger().warn("[UpdateChecker] Proxy update check failed on Modrinth ({}), checking GitHub instead.", e.getMessage());
            checkGithub();
        } finally {
            checking.set(false);
        }
    }

    /** Fallback when Modrinth can't be reached; the download link still goes to Modrinth. */
    private void checkGithub() {
        try {
            String remote = UpdateSources.fetchGithubLatestVersion(userAgent());
            if (remote != null)
                handleResult(remote, UpdateSources.downloadPage(null));
        } catch (Exception e) {
            plugin.getLogger().warn("[UpdateChecker] Proxy update check failed on Modrinth and GitHub: {}", e.getMessage());
        }
    }

    private void handleResult(String remote, String link) {
        if (UpdateSources.compareVersions(currentVersion, remote) < 0) {
            this.latestVersion = remote;
            this.downloadUrl   = link;
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

    private String userAgent() {
        return "TheCommandCraft/VanishppVelocity/" + currentVersion;
    }

    private void broadcastToAllServers() {
        for (var server : plugin.getProxy().getAllServers()) {
            notifyServer(server);
        }
    }
}
