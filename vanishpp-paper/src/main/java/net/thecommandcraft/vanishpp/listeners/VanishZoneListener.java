package net.thecommandcraft.vanishpp.listeners;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.zone.VanishZone;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Enforces no-vanish zones: any vanished player who enters a zone
 * with force-unvanish=true is automatically unvanished.
 */
public class VanishZoneListener implements Listener {

    private final Vanishpp plugin;

    public VanishZoneListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only fire when the block position changes (optimization)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        var player = event.getPlayer();
        if (!plugin.isVanished(player)) return;
        if (player.hasPermission("vanishpp.zone.bypass")) return;

        VanishZone zone = plugin.getVanishZoneManager().getZoneAt(event.getTo());
        if (zone == null || !zone.isForceUnvanish()) return;

        plugin.unvanishPlayer(player, player);
        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("zone.forced-unvanish")
                        .replace("%zone%", zone.getName()));
    }
}
