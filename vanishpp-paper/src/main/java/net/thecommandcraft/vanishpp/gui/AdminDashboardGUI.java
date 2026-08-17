package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Admin dashboard showing all currently vanished players.
 *
 * <p>Left-click a skull → open that player's rules GUI.<br>
 * Right-click a skull → unvanish that player.
 */
public class AdminDashboardGUI implements Listener {

    private static final int SIZE = 54;

    private final Vanishpp plugin;
    private final Set<UUID> openViewers = new HashSet<>();
    /** NBT key carrying the target player's UUID on each skull — invisible, unlike a lore line. */
    private final NamespacedKey targetUuidKey;

    public AdminDashboardGUI(Vanishpp plugin) {
        this.plugin = plugin;
        this.targetUuidKey = new NamespacedKey(plugin, "vpp_target_uuid");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player viewer) {
        // Looked up fresh on every open (not cached in a static/constant) so a
        // /vconfig reload picks up an edited messages.yml without a server restart.
        String title = plugin.getLanguageManager().getMessage("gui.admin-dashboard.title");
        Inventory inv = Bukkit.createInventory(null, SIZE, plugin.getMessageManager().parse(title, viewer));
        populateInventory(inv);

        // Info panel in last row
        inv.setItem(49, buildInfoItem());
        inv.setItem(53, buildCloseItem());

        openViewers.add(viewer.getUniqueId());
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        // Identification relies solely on the viewer being tracked in openViewers — not
        // on matching the inventory title text, since that title is now a live
        // language-file lookup and could change mid-session across a /vconfig reload.
        if (!openViewers.contains(viewer.getUniqueId())) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;

        if (clicked.getType() == Material.BARRIER) {
            // Close button (slot 53) - previously did nothing beyond cancelling the click.
            viewer.closeInventory();
            return;
        }
        if (clicked.getType() != Material.PLAYER_HEAD) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        // Extract target player UUID from invisible item NBT
        String uuidStr = meta.getPersistentDataContainer().get(targetUuidKey, PersistentDataType.STRING);
        if (uuidStr == null) return;
        UUID targetUuid;
        try { targetUuid = UUID.fromString(uuidStr); }
        catch (IllegalArgumentException e) { return; }

        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            viewer.closeInventory();
            return;
        }

        boolean rightClick = event.isRightClick();
        if (rightClick) {
            // Right-click → unvanish
            if (viewer.hasPermission("vanishpp.vanish.others")) {
                viewer.closeInventory();
                plugin.unvanishPlayer(target, viewer);
            }
        } else {
            // Left-click → open rules GUI
            if (viewer.hasPermission("vanishpp.rules.others")) {
                viewer.closeInventory();
                new RulesGUI(plugin).open(viewer, target);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        openViewers.remove(event.getPlayer().getUniqueId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void populateInventory(Inventory inv) {
        int slot = 0;
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            if (slot >= 45) break; // Leave last row for controls
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            inv.setItem(slot++, buildPlayerHead(p));
        }
    }

    @SuppressWarnings("deprecation")
    private ItemStack buildPlayerHead(Player p) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta == null) return skull;
        meta.setOwningPlayer(p);

        String reason = plugin.getVanishReason(p.getUniqueId());
        long elapsedMs = System.currentTimeMillis()
                - plugin.vanishStartTimes.getOrDefault(p.getUniqueId(), System.currentTimeMillis());
        long secs = elapsedMs / 1000;
        String elapsed = (secs / 60) + "m " + (secs % 60) + "s";
        int level = plugin.getStorageProvider().getVanishLevel(p.getUniqueId());

        meta.displayName(Component.text(p.getName(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        // Target UUID stored in invisible item NBT (read back by onClick) — not a lore line.
        meta.getPersistentDataContainer().set(targetUuidKey, PersistentDataType.STRING, p.getUniqueId().toString());
        lore.add(guiLine("gui.admin-dashboard.level", "%level%", String.valueOf(level)));
        lore.add(guiLine("gui.admin-dashboard.elapsed", "%elapsed%", elapsed));
        if (reason != null && !reason.isBlank())
            lore.add(guiLine("gui.admin-dashboard.reason", "%reason%", reason));
        lore.add(Component.empty());
        lore.add(guiLine("gui.admin-dashboard.hint-rules"));
        lore.add(guiLine("gui.admin-dashboard.hint-unvanish"));
        meta.lore(lore);
        skull.setItemMeta(meta);
        return skull;
    }

    private ItemStack buildInfoItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int count = plugin.getRawVanishedPlayers().size();
            meta.displayName(guiLine("gui.admin-dashboard.vanished-count", "%count%", String.valueOf(count)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildCloseItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(guiLine("gui.admin-dashboard.close"));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Fresh language-file lookup, parsed through MessageManager, with default italics off. */
    private Component guiLine(String key) {
        return plugin.getMessageManager().parse(plugin.getLanguageManager().getMessage(key), null)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Same as {@link #guiLine(String)} but substitutes a single %token% placeholder first. */
    private Component guiLine(String key, String token, String value) {
        String raw = plugin.getLanguageManager().getMessage(key).replace(token, value);
        return plugin.getMessageManager().parse(raw, null).decoration(TextDecoration.ITALIC, false);
    }
}
