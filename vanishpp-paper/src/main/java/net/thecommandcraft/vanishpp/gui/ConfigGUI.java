package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Professional inventory-based configuration GUI.
 * Manages categories, numerical controls, and value persistence.
 */
public class ConfigGUI implements Listener {

    private final Vanishpp plugin;
    private final ConfigRenderer renderer;
    private final Map<UUID, String> playerCategory;
    private final Map<UUID, Integer> playerPage;
    private final Map<UUID, Map<Integer, String>> slotToKeyMapping;  // Track slot->key for each player
    private final Set<UUID> openViewers;
    private final Map<UUID, Long> lastBoundarySound;  // Cooldown for boundary sound spam

    public ConfigGUI(Vanishpp plugin) {
        this.plugin = plugin;
        this.renderer = new ConfigRenderer();
        this.playerCategory = new ConcurrentHashMap<>();
        this.playerPage = new ConcurrentHashMap<>();
        this.slotToKeyMapping = new ConcurrentHashMap<>();
        this.openViewers = ConcurrentHashMap.newKeySet();
        this.lastBoundarySound = new ConcurrentHashMap<>();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Open the ConfigGUI for a player.
     */
    public void open(Player player) {
        if (!player.hasPermission("vanishpp.config")) {
            player.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }

        playerCategory.put(player.getUniqueId(), "GENERAL");
        playerPage.put(player.getUniqueId(), 0);
        openViewers.add(player.getUniqueId());

        render(player);
    }

    /**
     * Render the current inventory for a player.
     */
    private void render(Player player) {
        UUID uuid = player.getUniqueId();
        String category = playerCategory.getOrDefault(uuid, "GENERAL");
        int page = playerPage.getOrDefault(uuid, 0);

        Object[] result = renderer.buildCategoryInventory(category, page);
        Inventory inv = (Inventory) result[0];
        @SuppressWarnings("unchecked")
        Map<Integer, String> slotToKey = (Map<Integer, String>) result[1];

        slotToKeyMapping.put(uuid, slotToKey);
        player.openInventory(inv);
    }

    /**
     * Handle inventory click events.
     */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        if (!openViewers.contains(uuid)) return;
        String title = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(event.getView().title()).toLowerCase();
        if (!title.contains("config")) return;  // Case-insensitive check

        // Re-check permission in case player lost it while GUI was open
        if (!player.hasPermission("vanishpp.config")) {
            player.closeInventory();
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        // Determine what was clicked
        if (renderer.isCategoryTab(slot)) {
            handleCategoryClick(player, slot);
        } else if (renderer.isNavigation(slot)) {
            handleNavigationClick(player, slot);
        } else if (isSetting(slot)) {
            handleSettingClick(player, slot, event.isRightClick(), event.isShiftClick());
        }
    }

    /**
     * Handle category tab clicks.
     */
    private void handleCategoryClick(Player player, int slot) {
        String category = renderer.getCategoryFromSlot(slot);
        if (category == null) return;

        UUID uuid = player.getUniqueId();
        playerCategory.put(uuid, category);
        playerPage.put(uuid, 0);  // Reset to first page
        render(player);
    }

    /**
     * Handle navigation button clicks.
     */
    private void handleNavigationClick(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        String category = playerCategory.getOrDefault(uuid, "GENERAL");
        int currentPage = playerPage.getOrDefault(uuid, 0);
        int totalPages = renderer.getTotalPages(category);

        if (slot == 45 && currentPage > 0) {
            // Previous button
            playerPage.put(uuid, currentPage - 1);
            render(player);
        } else if (slot == 53 && currentPage < totalPages - 1) {
            // Next button
            playerPage.put(uuid, currentPage + 1);
            render(player);
        }
    }

    /**
     * Handle setting item clicks.
     */
    private void handleSettingClick(Player player, int slot, boolean isRightClick, boolean isShiftClick) {
        UUID uuid = player.getUniqueId();

        Map<Integer, String> slotToKey = slotToKeyMapping.getOrDefault(uuid, new HashMap<>());
        String key = slotToKey.get(slot);
        if (key == null) return;

        String category = playerCategory.getOrDefault(uuid, "GENERAL");
        ConfigCategory cat = ConfigCategory.valueOf(category);
        ConfigCategory.ConfigValue value = cat.getSettings().get(key);
        if (value == null) return;

        if (value.type.isBoolean()) {
            handleBooleanClick(player, value);
        } else if (value.type.isNumeric()) {
            int delta = calculateNumericDelta(isRightClick, isShiftClick);
            handleNumericClick(player, value, delta);
        }

        render(player);
    }

    /**
     * Handle boolean setting toggle.
     */
    private void handleBooleanClick(Player player, ConfigCategory.ConfigValue value) {
        Object current = plugin.getConfigManager().getConfig().get(value.key);
        if (current == null) current = value.defaultValue;
        boolean newValue = !(current instanceof Boolean && (Boolean) current);

        if (saveConfig(player, value.key, newValue)) {
            playSound(player, ConfigSound.SUCCESS);
            sendSaveMessage(player, value.key, String.valueOf(newValue));
        }
    }

    /**
     * Handle numeric setting adjustment.
     */
    private void handleNumericClick(Player player, ConfigCategory.ConfigValue value, int delta) {
        Object current = plugin.getConfigManager().getConfig().get(value.key);
        int currentValue = (current instanceof Integer) ? (Integer) current : (Integer) value.defaultValue;
        int newValue = currentValue + delta;

        // Enforce min/max bounds
        if (newValue < value.minBound || newValue > value.maxBound) {
            long now = System.currentTimeMillis();
            long lastTime = lastBoundarySound.getOrDefault(player.getUniqueId(), 0L);
            if (now - lastTime > 100) {  // Max 1 sound per 100ms
                playSound(player, ConfigSound.BOUNDARY);
                lastBoundarySound.put(player.getUniqueId(), now);
            }
            return;
        }

        if (saveConfig(player, value.key, newValue)) {
            playSound(player, ConfigSound.SUCCESS);
            sendSaveMessage(player, value.key, String.valueOf(newValue));
        }
    }

    /**
     * Save config change to file.
     * Returns true if save was successful, false if failed.
     */
    private boolean saveConfig(Player player, String key, Object value) {
        try {
            plugin.getConfigManager().setAndSave(key, value);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save config " + key + ": " + e.getMessage());
            playSound(player, ConfigSound.ERROR);
            return false;
        }

        // Proxy sync if available (separate error handling)
        var bridge = plugin.getProxyBridge();
        if (bridge != null && bridge.isProxyDetected()) {
            try {
                bridge.sendConfigSync(Map.of(key, String.valueOf(value)));
            } catch (Exception e) {
                plugin.getLogger().warning("Proxy sync failed for " + key + ": " + e.getMessage());
            }
        }
        return true;
    }

    /**
     * Play sound feedback for user action.
     */
    private void playSound(Player player, ConfigSound soundType) {
        try {
            Sound sound = soundType.getSound();
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (Exception ignored) {
            // Sounds are optional
        }
    }

    /**
     * Send action bar confirmation message for saved value.
     */
    private void sendSaveMessage(Player player, String key, String value) {
        Component message = Component.text("✓ ", NamedTextColor.GREEN)
                .append(Component.text(key, NamedTextColor.AQUA))
                .append(Component.text(" → ", NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.GREEN))
                .decoration(TextDecoration.ITALIC, false);
        player.sendActionBar(message);
    }

    /**
     * Determine the numeric delta for adjustment.
     * Left-click: -1, Right-click: +1, Shift+Left: -10, Shift+Right: +10
     */
    private int calculateNumericDelta(boolean isRightClick, boolean isShiftClick) {
        if (isShiftClick) {
            return isRightClick ? 10 : -10;
        } else {
            return isRightClick ? 1 : -1;
        }
    }

    /**
     * Check if a slot is a setting item (rows 2-4).
     */
    private boolean isSetting(int slot) {
        int row = slot / 9;
        return row >= 2 && row <= 4;
    }

    /**
     * Handle inventory close event.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openViewers.remove(uuid);
        playerCategory.remove(uuid);
        playerPage.remove(uuid);
        slotToKeyMapping.remove(uuid);
        lastBoundarySound.remove(uuid);
    }

    /**
     * Sound types for ConfigGUI feedback.
     */
    public enum ConfigSound {
        SUCCESS(Sound.ENTITY_EXPERIENCE_ORB_PICKUP),
        BOUNDARY(Sound.UI_BUTTON_CLICK),
        ERROR(Sound.ENTITY_VILLAGER_NO);

        private final Sound sound;

        ConfigSound(Sound sound) {
            this.sound = sound;
        }

        public Sound getSound() {
            return sound;
        }
    }
}
