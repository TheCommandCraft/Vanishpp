package net.thecommandcraft.vanishpp.proxy;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Holds the in-memory config snapshot pushed by the Velocity proxy.
 * Never writes to disk. The proxy is always authoritative when connected.
 */
public class ProxyConfigCache {

    private final Vanishpp plugin;
    private volatile ProxyConfigSnapshot snapshot;

    public ProxyConfigCache(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /**
     * Called when the proxy sends a CONFIG_PUSH.
     * Updates the in-memory snapshot and triggers a re-sync of all online vanished players.
     *
     * @param json       the raw JSON from the CONFIG_PUSH payload's "config" field
     * @param serverName this server's name (used to apply per-server overrides)
     */
    public void update(String json, String serverName) {
        ProxyConfigSnapshot global = ProxyConfigSnapshot.fromJson(json);
        this.snapshot = global.applyOverride(serverName);

        plugin.getLogger().info("[Proxy] Config cache updated from proxy (server: " + serverName + ").");

        // Apply the new config to the plugin's ConfigManager fields
        plugin.getConfigManager().loadFromProxySnapshot(this.snapshot);

        // Re-sync effects for all online vanished players
        plugin.getVanishScheduler().runGlobal(() -> {
            for (UUID uuid : plugin.getRawVanishedPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    plugin.resyncVanishEffects(p);
                }
            }
        });
    }

    /** @return the current proxy-pushed config snapshot, or null if not yet received. */
    public ProxyConfigSnapshot get() {
        return snapshot;
    }

    public boolean isReady() {
        return snapshot != null;
    }
}
