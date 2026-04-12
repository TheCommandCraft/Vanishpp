package net.thecommandcraft.vanishpp.listeners;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.hooks.WorldGuardHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Enforces WorldGuard vanish-related flags on player movement:
 * <ul>
 *   <li>{@code vanishpp-force-vanish}  — vanishes the player when they enter the region</li>
 *   <li>{@code vanishpp-deny-vanish}   — checked at vanish-time (in VanishCommand)</li>
 *   <li>{@code vanishpp-deny-unvanish} — checked at unvanish-time (in VanishCommand)</li>
 * </ul>
 */
public class WorldGuardVanishListener implements Listener {

    private final Vanishpp plugin;

    public WorldGuardVanishListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only fire once per block boundary to reduce overhead
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        WorldGuardHook hook = plugin.getWorldGuardHook();
        if (hook == null) return;

        // Force-vanish: player entered a force-vanish region and is not yet vanished
        if (!plugin.isVanished(player)
                && plugin.getPermissionManager().hasPermission(player, "vanishpp.vanish")
                && hook.isForceVanish(player)) {
            plugin.vanishPlayer(player, player, "force-vanish region");
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("worldguard.force-vanished"));
        }
    }
}
