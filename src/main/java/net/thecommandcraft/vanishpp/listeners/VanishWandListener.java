package net.thecommandcraft.vanishpp.listeners;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.gui.RulesGUI;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Handles right-click interaction with the Vanish Wand item.
 * <ul>
 *   <li>Right-click (not sneaking) → toggle vanish</li>
 *   <li>Shift-right-click → open Rules GUI</li>
 * </ul>
 */
public class VanishWandListener implements Listener {

    private final Vanishpp plugin;

    public VanishWandListener(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!plugin.getConfigManager().wandEnabled) return;
        if (!plugin.getPermissionManager().hasPermission(player, "vanishpp.vanish")) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isWand(item)) return;

        event.setCancelled(true); // Don't interact with underlying block

        if (player.isSneaking()) {
            // Shift-right-click → open Rules GUI
            if (plugin.getPermissionManager().hasPermission(player, "vanishpp.rules")) {
                new RulesGUI(plugin).open(player, player);
            }
        } else {
            // Regular right-click → toggle vanish
            if (plugin.isVanished(player)) {
                plugin.unvanishPlayer(player, player);
            } else {
                plugin.vanishPlayer(player, player);
            }
        }
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        try {
            Material mat = Material.valueOf(plugin.getConfigManager().wandMaterial.toUpperCase());
            if (item.getType() != mat) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Check for wand identifier in item lore/meta
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String displayName = plugin.getConfigManager().wandDisplayName;
        String parsed = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().serialize(
                        plugin.getMessageManager().parse(displayName, null));
        if (meta.hasDisplayName()) {
            String name = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().serialize(meta.displayName());
            return name != null && name.equals(parsed);
        }
        return false;
    }
}
