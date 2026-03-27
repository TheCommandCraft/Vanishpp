package net.thecommandcraft.vanishpp.network;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Handles the vanishpp:sync plugin messaging channel for Velocity/BungeeCord networks.
 *
 * <p>Protocol (all messages are UTF-8 strings):
 * <ul>
 *   <li>Backend → Proxy: {@code VANISH:<uuid>} — player vanished, relay to all other backends</li>
 *   <li>Backend → Proxy: {@code UNVANISH:<uuid>} — player unvanished, relay to all other backends</li>
 *   <li>Proxy → Backend: {@code VANISH:<uuid>} — apply vanish for this player</li>
 *   <li>Proxy → Backend: {@code UNVANISH:<uuid>} — remove vanish for this player</li>
 *   <li>Proxy → Backend: {@code STATE:<uuid>,<uuid>,...} — full set of currently vanished players
 *       sent when a player connects to this backend</li>
 * </ul>
 *
 * <p>Race-condition handling: the companion Velocity plugin sends {@code VANISH} messages to the
 * target backend during {@code ServerPreConnectEvent} (before the player's join packet). If the
 * message arrives before {@code PlayerJoinEvent}, the UUID is already in {@code vanishedPlayers}
 * and the join is treated as vanished immediately — suppressing the join message and applying
 * all effects in a single pass. If STATE arrives after join, {@link Vanishpp#handleNetworkVanishSync}
 * applies effects to the now-online player atomically on the main thread.
 */
public class VelocityMessenger implements PluginMessageListener {

    public static final String CHANNEL = "vanishpp:sync";

    private final Vanishpp plugin;

    public VelocityMessenger(Vanishpp plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getLogger().info("Plugin messaging channel registered: " + CHANNEL);
    }

    public void shutdown() {
        try {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Broadcasts a vanish state change to the proxy so it can relay to all other backends.
     * Must be called with the affected online player as the routing channel.
     */
    public void broadcast(Player player, boolean vanished) {
        String msg = (vanished ? "VANISH:" : "UNVANISH:") + player.getUniqueId();
        player.sendPluginMessage(plugin, CHANNEL, msg.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player routingPlayer, byte[] rawMessage) {
        if (!CHANNEL.equals(channel)) return;

        String msg = new String(rawMessage, StandardCharsets.UTF_8);

        if (msg.startsWith("VANISH:")) {
            UUID uuid = parseUUID(msg, 7);
            if (uuid == null) return;
            // Run on main thread — handleNetworkVanishSync is not thread-safe.
            // handleNetworkVanishSync adds uuid to vanishedPlayers unconditionally (online or offline),
            // so isVanished() returns true before PlayerJoinEvent fires if the message arrives first.
            plugin.getVanishScheduler().runGlobal(() ->
                    plugin.handleNetworkVanishSync(uuid, true));

        } else if (msg.startsWith("UNVANISH:")) {
            UUID uuid = parseUUID(msg, 9);
            if (uuid == null) return;
            plugin.getVanishScheduler().runGlobal(() ->
                    plugin.handleNetworkVanishSync(uuid, false));

        } else if (msg.startsWith("STATE:")) {
            String payload = msg.substring(6);
            if (payload.isEmpty()) return;
            plugin.getVanishScheduler().runGlobal(() -> {
                for (String part : payload.split(",")) {
                    UUID uuid = parseUUID(part.trim());
                    if (uuid == null) continue;
                    plugin.handleNetworkVanishSync(uuid, true);
                }
            });
        }
    }

    // --- helpers ---

    private UUID parseUUID(String msg, int offset) {
        return parseUUID(msg.substring(offset));
    }

    private UUID parseUUID(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[VelocitySync] Malformed UUID in plugin message: '" + raw + "'");
            return null;
        }
    }
}
