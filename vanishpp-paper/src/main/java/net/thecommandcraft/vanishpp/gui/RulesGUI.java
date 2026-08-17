package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

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

    private final Vanishpp plugin;
    /** viewer UUID → target player UUID */
    private final Map<UUID, UUID> openGuis = new HashMap<>();
    /** NBT key carrying the rule id on each wool item — invisible, unlike a lore line. */
    private final NamespacedKey ruleKey;

    public RulesGUI(Vanishpp plugin) {
        this.plugin = plugin;
        this.ruleKey = new NamespacedKey(plugin, "vpp_rule");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Opens the rules GUI for {@code viewer}, displaying (and allowing editing of)
     * {@code target}'s rules.
     */
    public void open(Player viewer, Player target) {
        List<String> rules = sortedRules();
        int size = ((rules.size() / 9) + 1) * 9;
        // Looked up fresh on every open (not cached in a static/constant) so a
        // /vconfig reload picks up an edited messages.yml without a server restart.
        String titlePrefix = plugin.getLanguageManager().getMessage("gui.rules.title-prefix");
        Component title = plugin.getMessageManager().parse(titlePrefix, viewer)
                .append(Component.text(target.getName()));
        Inventory inv = Bukkit.createInventory(null, Math.max(size, 9), title);

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
        // Identification relies solely on the viewer being tracked in openGuis — not on
        // matching the inventory title text, since that title is now a live language-file
        // lookup and could change mid-session across a /vconfig reload.
        if (!openGuis.containsKey(viewerUuid)) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        UUID targetUuid = openGuis.get(viewerUuid);
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) { viewer.closeInventory(); return; }

// Rule id read from invisible item NBT — the display name is now a translated
        // string and can no longer be parsed back into a rule key.
        String ruleName = meta.getPersistentDataContainer().get(ruleKey, PersistentDataType.STRING);
        if (ruleName == null || !plugin.getRuleManager().getAvailableRules().contains(ruleName)) return;
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
            // Rule id stored in invisible item NBT — the display name is translated, so it
            // can no longer be parsed back into a key, and a visible lore line would leak.
            meta.getPersistentDataContainer().set(ruleKey, PersistentDataType.STRING, rule);
            String statusKey = enabled ? "gui.rules.status-enabled" : "gui.rules.status-disabled";
            meta.lore(List.of(
                    plugin.getMessageManager().parse(plugin.getLanguageManager().getMessage(statusKey), null)
                            .decoration(TextDecoration.ITALIC, false),
                    plugin.getMessageManager().parse(plugin.getLanguageManager().getMessage("gui.rules.toggle-hint"), null)
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
