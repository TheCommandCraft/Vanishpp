package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Clickable inventory GUI for managing per-player vanish rules.
 *
 * <p>Each rule is shown as a dyed wool block:
 * <ul>
 *   <li>Green = rule enabled</li>
 *   <li>Red   = rule disabled</li>
 * </ul>
 * Click to toggle. The GUI auto-refreshes to reflect the new state.
 *
 * <p>This class is both a factory (call {@link #open}) and a Listener — register it once per
 * plugin lifecycle via {@link Bukkit#getPluginManager()#registerEvents}.
 */
public class RulesGUI implements Listener {

    private static final String GUI_TITLE_PREFIX = "§6Rules: ";

    private final Vanishpp plugin;
    /** viewer UUID → target player UUID */
    private final Map<UUID, UUID> openGuis = new HashMap<>();

    public RulesGUI(Vanishpp plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Opens the rules GUI for {@code viewer}, displaying (and allowing editing of)
     * {@code target}'s rules.
     */
    public void open(Player viewer, Player target) {
        List<String> rules = sortedRules();
        int size = ((rules.size() / 9) + 1) * 9;
        Inventory inv = Bukkit.createInventory(null, Math.max(size, 9),
                Component.text(GUI_TITLE_PREFIX + target.getName()));

        for (int i = 0; i < rules.size(); i++) {
            inv.setItem(i, buildItem(target, rules.get(i)));
        }

        openGuis.put(viewer.getUniqueId(), target.getUniqueId());
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        UUID viewerUuid = viewer.getUniqueId();
        if (!openGuis.containsKey(viewerUuid)) return;

        // Ensure this is the GUI we opened (title check)
        if (!event.getView().title().toString().contains(GUI_TITLE_PREFIX)) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        UUID targetUuid = openGuis.get(viewerUuid);
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) { viewer.closeInventory(); return; }

        // Extract rule name from item name
        String displayName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(meta.displayName());
        // Display name format: "§acan_break_blocks" or "§ccan_break_blocks"
        String ruleName = displayName.replaceAll("§[0-9a-fk-or]", "").trim();

        if (!plugin.getRuleManager().getAvailableRules().contains(ruleName)) return;
        if (!viewer.hasPermission("vanishpp.rules")
                && (!viewer.equals(target) || !viewer.hasPermission("vanishpp.rules.others"))) return;

        boolean current = plugin.getRuleManager().getRule(target, ruleName);
        plugin.getRuleManager().setRule(target, ruleName, !current);

        // Refresh the clicked slot
        event.getClickedInventory().setItem(event.getSlot(), buildItem(target, ruleName));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        openGuis.remove(event.getPlayer().getUniqueId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ItemStack buildItem(Player target, String rule) {
        boolean enabled = plugin.getRuleManager().getRule(target, rule);
        Material mat = enabled ? Material.LIME_WOOL : Material.RED_WOOL;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(rule, enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(enabled ? "✔ ENABLED" : "✘ DISABLED",
                            enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Click to toggle", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> sortedRules() {
        List<String> list = new ArrayList<>(plugin.getRuleManager().getAvailableRules());
        Collections.sort(list);
        return list;
    }
}
