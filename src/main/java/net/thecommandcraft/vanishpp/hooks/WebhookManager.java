package net.thecommandcraft.vanishpp.hooks;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Sends vanish/unvanish events to one or more HTTP webhook endpoints.
 *
 * <p>Config keys:
 * <ul>
 *   <li>{@code webhook.enabled}</li>
 *   <li>{@code webhook.urls} (list of strings)</li>
 *   <li>{@code webhook.payload-template} — placeholders: {player}, {action}, {reason}, {server}, {timestamp}</li>
 *   <li>{@code webhook.authorization} — sent as the {@code Authorization} header (optional)</li>
 * </ul>
 */
public class WebhookManager {

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1_000, 5_000, 15_000};

    private final Vanishpp plugin;

    public WebhookManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends the event asynchronously. Retries up to 3 times on failure with
     * exponential back-off.
     *
     * @param player the subject player
     * @param action "vanish" or "unvanish"
     * @param reason optional vanish reason (may be null)
     */
    public void send(Player player, String action, String reason) {
        if (!plugin.getConfigManager().webhookEnabled) return;
        List<String> urls = plugin.getConfigManager().webhookUrls;
        if (urls == null || urls.isEmpty()) return;

        String payload = buildPayload(player, action, reason);
        String auth    = plugin.getConfigManager().webhookAuthHeader;

        plugin.getVanishScheduler().runAsync(() -> {
            for (String rawUrl : urls) {
                if (rawUrl == null || rawUrl.isBlank()) continue;
                postWithRetry(rawUrl.trim(), payload, auth);
            }
        });
    }

    private String buildPayload(Player player, String action, String reason) {
        String template = plugin.getConfigManager().webhookPayloadTemplate;
        String server   = plugin.getConfigManager().getConfig()
                .getString("proxy.server-name", "server");
        return template
                .replace("{player}",    player.getName())
                .replace("{uuid}",      player.getUniqueId().toString())
                .replace("{action}",    action)
                .replace("{reason}",    reason != null ? reason : "")
                .replace("{server}",    server)
                .replace("{timestamp}", Instant.now().toString());
    }

    private void postWithRetry(String rawUrl, String payload, String auth) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(RETRY_DELAYS_MS[attempt - 1]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            try {
                URL url = new URL(rawUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Content-Type", "application/json");
                if (auth != null && !auth.isBlank())
                    conn.setRequestProperty("Authorization", auth);

                byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) return; // success

                plugin.getLogger().warning("Webhook returned HTTP " + status + " (attempt " + (attempt + 1) + ")");
            } catch (Exception e) {
                plugin.getLogger().warning("Webhook error (attempt " + (attempt + 1) + "): " + e.getMessage());
            }
        }
        plugin.getLogger().severe("Webhook failed after " + MAX_RETRIES + " attempts for URL: " + rawUrl);
    }
}
