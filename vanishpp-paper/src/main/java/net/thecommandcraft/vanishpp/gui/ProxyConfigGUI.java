package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
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

import java.util.*;

/**
 * In-game visual editor for proxy/shared config settings.
 * Boolean keys are shown as colored glass panes — click to toggle.
 * Changes are saved locally and synced to the Velocity proxy if connected.
 */
public class ProxyConfigGUI implements Listener {

    static final String TITLE = "§3Proxy Config — Vanish++";
    private static final int SIZE = 54;

    /** {configKey, displayLabel} pairs — each occupies one slot in order. */
    private static final String[][] ENTRIES = {
        {"messages.staff-notify.enabled",               "Staff Notifications"},
        {"vanish-appearance.staff-glow",                "Staff Glow"},
        {"vanish-appearance.action-bar.enabled",        "Action Bar"},
        {"invisibility-features.god-mode",              "God Mode"},
        {"invisibility-features.night-vision",          "Night Vision"},
        {"invisibility-features.disable-mob-targeting", "No Mob Targeting"},
        {"invisibility-features.disable-hunger",        "No Hunger"},
        {"invisibility-features.silent-chests",         "Silent Chests"},
        {"invisibility-features.ignore-projectiles",    "Ignore Projectiles"},
        {"vanish-effects.hide-real-quit-messages",      "Hide Real Quit"},
        {"vanish-effects.hide-real-join-messages",      "Hide Real Join"},
        {"vanish-effects.broadcast-fake-quit",          "Broadcast Fake Quit"},
        {"vanish-effects.broadcast-fake-join",          "Broadcast Fake Join"},
        {"vanish-effects.disable-block-triggering",     "No Block Triggering"},
        {"hide-announcements.death-messages",           "Hide Deaths"},
        {"hide-announcements.advancements",             "Hide Advancements"},
        {"hide-announcements.hide-from-plugin-list",    "Hide From /plugins"},
        {"flight-control.vanish-enable-fly",            "Auto Fly on Vanish"},
        {"flight-control.unvanish-disable-fly",         "Remove Fly on Unvanish"},
        {"scoreboard.enabled",                          "Scoreboard"},
        {"scoreboard.auto-show-on-vanish",              "Auto-Show Scoreboard"},
        {"proxy.cross-server-list",                     "Cross-Server /vlist"},
        {"proxy.broadcast-vanish-events",               "Cross-Server Broadcast"},
        {"hooks.simple-voice-chat.enabled",             "SimpleVoiceChat Hook"},
    };

    private final Vanishpp plugin;
    private final Set<UUID> openViewers = new HashSet<>();

    public ProxyConfigGUI(Vanishpp plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player viewer) {
        boolean proxyConnected = plugin.getProxyBridge() != null && plugin.getProxyBridge().isProxyDetected();
        Inventory inv = Bukkit.createInventory(null, SIZE, Component.text(TITLE));
        populate(inv, proxyConnected);
        openViewers.add(viewer.getUniqueId());
        viewer.openInventory(inv);
    }

    private void populate(Inventory inv, boolean proxyConnected) {
        var cfg = plugin.getConfigManager();
        for (int i = 0; i < ENTRIES.length && i < 45; i++) {
            boolean value = cfg.getConfig().getBoolean(ENTRIES[i][0], false);
            inv.setItem(i, buildPane(ENTRIES[i][1], ENTRIES[i][0], value, proxyConnected));
        }

        // Slot 49: proxy connection status
        ItemStack statusItem = new ItemStack(proxyConnected ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = statusItem.getItemMeta();
        statusMeta.displayName(Component.text(
                proxyConnected ? "Proxy: Connected" : "Proxy: Not Connected",
                proxyConnected ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (!proxyConnected) {
            statusMeta.lore(List.of(
                    Component.text("Changes are saved locally only.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Connect a Velocity proxy to sync.", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        statusItem.setItemMeta(statusMeta);
        inv.setItem(49, statusItem);

        // Slot 53: close
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(closeMeta);
        inv.setItem(53, close);
    }

    private ItemStack buildPane(String label, String key, boolean value, boolean proxyConnected) {
        ItemStack item = new ItemStack(value ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, value ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                Component.text("Key: " + key, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Value: " + value, value ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(proxyConnected ? "Click to toggle + sync to proxy" : "Click to toggle (local only)",
                        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (!openViewers.contains(viewer.getUniqueId())) return;
        if (!event.getView().title().toString().contains("Proxy Config — Vanish++")) return;
        event.setCancelled(true);

        int slot = event.getSlot();

        // Close button
        if (slot == 53) { viewer.closeInventory(); return; }

        if (slot >= ENTRIES.length || slot >= 45) return;

        String key = ENTRIES[slot][0];
        boolean current = plugin.getConfigManager().getConfig().getBoolean(key, false);
        boolean newVal = !current;

        plugin.getConfigManager().setAndSave(key, newVal);

        // Sync to proxy if connected
        if (plugin.getProxyBridge() != null && plugin.getProxyBridge().isProxyDetected()) {
            plugin.getProxyBridge().sendConfigSync(Map.of(key, String.valueOf(newVal)));
        }

        boolean proxyConnected = plugin.getProxyBridge() != null && plugin.getProxyBridge().isProxyDetected();
        event.getInventory().setItem(slot, buildPane(ENTRIES[slot][1], key, newVal, proxyConnected));
        viewer.sendMessage(Component.text("§aSet §e" + key + " §ato §f" + newVal));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        openViewers.remove(event.getPlayer().getUniqueId());
    }
}
