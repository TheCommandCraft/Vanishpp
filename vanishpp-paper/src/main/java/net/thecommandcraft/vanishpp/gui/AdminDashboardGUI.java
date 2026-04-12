package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Admin dashboard showing all currently vanished players.
 *
 * <p>Left-click a skull → open that player's rules GUI.<br>
 * Right-click a skull → unvanish that player.
 */
public class AdminDashboardGUI implements Listener {

    private static final String TITLE = "§6Vanish++ Admin Dashboard";
    private static final int SIZE = 54;

    private final Vanishpp plugin;
    private final Set<UUID> openViewers = new HashSet<>();

    public AdminDashboardGUI(Vanishpp plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, SIZE, Component.text(TITLE));
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
        if (!openViewers.contains(viewer.getUniqueId())) return;
        if (!event.getView().title().toString().contains("Vanish++ Admin Dashboard")) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        // Extract player name from lore (first lore line contains UUID as hidden key)
        String uuidStr = getLoreValue(meta, "§8UUID: ");
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
        lore.add(Component.text("§8UUID: " + p.getUniqueId()).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Level: " + level, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Elapsed: " + elapsed, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        if (reason != null && !reason.isBlank())
            lore.add(Component.text("Reason: " + reason, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Left-click → Rules GUI", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right-click → Unvanish", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        skull.setItemMeta(meta);
        return skull;
    }

    private ItemStack buildInfoItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int count = plugin.getRawVanishedPlayers().size();
            meta.displayName(Component.text("Vanished: " + count, NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildCloseItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Close", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getLoreValue(ItemMeta meta, String prefix) {
        if (!meta.hasLore() || meta.lore() == null) return null;
        for (Component line : meta.lore()) {
            String s = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().serialize(line);
            if (s.startsWith(prefix)) return s.substring(prefix.length()).trim();
        }
        return null;
    }
}
