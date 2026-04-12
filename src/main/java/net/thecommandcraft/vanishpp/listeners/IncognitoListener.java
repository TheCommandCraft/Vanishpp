package net.thecommandcraft.vanishpp.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Replaces the real name with the fake incognito name in:
 * <ul>
 *   <li>Chat messages</li>
 *   <li>Death messages</li>
 * </ul>
 * Tab list and nametag are already handled in {@link Vanishpp#enableIncognito}.
 */
public class IncognitoListener implements Listener {

    private final Vanishpp plugin;

    public IncognitoListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isIncognito(player)) return;
        String fakeName = plugin.getIncognitoName(player.getUniqueId());
        if (fakeName == null) return;

        // Rewrite the renderer so the chat message uses the fake name
        event.renderer((source, sourceDisplayName, message, viewer) ->
                Component.text(fakeName + ": ").append(message));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.isIncognito(player)) return;
        String fakeName = plugin.getIncognitoName(player.getUniqueId());
        if (fakeName == null) return;

        // Replace death message with one using the fake name
        Component originalDeath = event.deathMessage();
        if (originalDeath != null) {
            // Build a simple replacement; real name → fake name via string serialisation
            String raw = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().serialize(originalDeath);
            raw = raw.replace(player.getName(), fakeName);
            event.deathMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(raw));
        }
    }
}
