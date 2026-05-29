package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Renders the ConfigGUI inventory with categories, settings, and pagination.
 * Handles layout calculations, item creation, and responsive design.
 */
public class ConfigRenderer {

    private static final int INVENTORY_SIZE = 54;
    private static final int ITEMS_PER_ROW = 7;
    private static final int INDENT_WRAPPING = 2;
    private static final int CATEGORY_ROW = 0;
    private static final int SPACER_ROW = 1;
    private static final int SETTINGS_START_ROW = 2;
    private static final int NAVIGATION_ROW = 5;
    private static final int SETTINGS_CONTENT_ROWS = 3;  // Rows 2, 3, 4
    private static final int ITEMS_PER_PAGE = (SETTINGS_CONTENT_ROWS * 9) - INDENT_WRAPPING;

    private static final Material CATEGORY_ACTIVE = Material.YELLOW_STAINED_GLASS;
    private static final Material CATEGORY_INACTIVE = Material.BLUE_STAINED_GLASS;
    private static final Material BOOLEAN_TRUE = Material.LIME_CONCRETE;
    private static final Material BOOLEAN_FALSE = Material.RED_CONCRETE;
    private static final Material NUMERIC = Material.ORANGE_CONCRETE;
    private static final Material NAVIGATION = Material.GRAY_STAINED_GLASS;

    /**
     * Build the inventory for a specific category and page, returning both inventory and slot mapping.
     *
     * @param category Current category to display
     * @param page Current page number
     * @return Object array: [Inventory, Map<Integer slot, String key>]
     */
    public Object[] buildCategoryInventory(String category, int page) {
        String title = "§6Vanish++ Config — " + category;
        Inventory inv = Bukkit.createInventory(null, INVENTORY_SIZE, Component.text(title));
        Map<Integer, String> slotToKey = new HashMap<>();

        // Row 0: Category tabs
        placeCategoryTabs(inv, category);

        // Rows 2+: Settings with wrapping layout
        ConfigCategory cat = ConfigCategory.valueOf(category);
        List<ConfigCategory.ConfigValue> settings = new ArrayList<>(cat.getSettings().values());
        placeSettings(inv, settings, page, slotToKey);

        // Row 5: Navigation buttons
        placeNavigation(inv, page, settings.size());

        return new Object[]{inv, slotToKey};
    }

    /**
     * Place category tabs in row 0.
     */
    private void placeCategoryTabs(Inventory inv, String activeCategory) {
        int slot = 0;
        for (ConfigCategory category : ConfigCategory.values()) {
            if (slot >= 9) break;  // Only 9 slots in row 0
            boolean isActive = category.name().equals(activeCategory);
            ItemStack tab = createCategoryTab(category.getDisplayName(), isActive);
            inv.setItem(slot++, tab);
        }
    }

    /**
     * Create a category tab item.
     */
    private ItemStack createCategoryTab(String categoryName, boolean isActive) {
        ItemStack item = new ItemStack(isActive ? CATEGORY_ACTIVE : CATEGORY_INACTIVE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(categoryName,
                    isActive ? NamedTextColor.YELLOW : NamedTextColor.BLUE)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Place settings with wrapping layout and pagination.
     */
    private void placeSettings(Inventory inv, List<ConfigCategory.ConfigValue> allSettings, int page,
                               Map<Integer, String> slotToKey) {
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, allSettings.size());

        if (startIndex >= allSettings.size()) return;

        int slot = SETTINGS_START_ROW * 9;
        int row = SETTINGS_START_ROW;
        int colInRow = 0;

        for (int i = startIndex; i < endIndex; i++) {
            ConfigCategory.ConfigValue value = allSettings.get(i);

            // Wrap with 2-indent if exceeding items per row
            if (colInRow >= ITEMS_PER_ROW) {
                row++;
                slot = row * 9 + INDENT_WRAPPING;
                colInRow = 0;
            }

            // Safety check: don't overflow inventory (BEFORE placing item)
            if (slot >= NAVIGATION_ROW * 9) break;

            ItemStack setting = createSettingItem(value);
            inv.setItem(slot, setting);
            slotToKey.put(slot, value.key);  // Track slot->key mapping

            slot++;
            colInRow++;
        }
    }

    /**
     * Create a setting item based on its type.
     */
    private ItemStack createSettingItem(ConfigCategory.ConfigValue value) {
        ItemStack item;

        if (value.type.isBoolean()) {
            // Boolean: show true/false state
            Object defaultVal = value.defaultValue;
            boolean isTrue = defaultVal instanceof Boolean && (Boolean) defaultVal;
            item = new ItemStack(isTrue ? BOOLEAN_TRUE : BOOLEAN_FALSE);
        } else if (value.type.isNumeric()) {
            // Numeric: show range and current value
            item = new ItemStack(NUMERIC);
        } else {
            // String: display only
            item = new ItemStack(Material.PAPER);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Display name
            meta.displayName(Component.text(value.key, NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));

            // Lore with instructions
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(value.description, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());

            if (value.type.isBoolean()) {
                Object val = value.defaultValue;
                String status = (val instanceof Boolean && (Boolean) val) ? "§aTRUE" : "§cFALSE";
                lore.add(Component.text("Current: " + status, NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Click to toggle", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
            } else if (value.type.isNumeric()) {
                Object val = value.defaultValue;
                String current = val != null ? String.valueOf(val) : "?";
                lore.add(Component.text("Current: " + current, NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(Component.text("§7Left: -1  |  Right: +1", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("§7Shift: ±10", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(Component.text("Range: " + value.minBound + " → " + value.maxBound,
                        NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Display only", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }

            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Place navigation buttons in row 5.
     */
    private void placeNavigation(Inventory inv, int currentPage, int totalSettings) {
        int totalPages = (totalSettings + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;

        // Previous button (slot 45)
        if (currentPage > 0) {
            ItemStack prev = createNavigationButton("§6◀ PREVIOUS", NAVIGATION);
            inv.setItem(45, prev);
        }

        // Info button (slot 49)
        String info = "Page " + (currentPage + 1) + " / " + totalPages;
        ItemStack infoItem = createNavigationButton("§eINFO", NAVIGATION);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.displayName(Component.text(info, NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            infoItem.setItemMeta(infoMeta);
        }
        inv.setItem(49, infoItem);

        // Next button (slot 53)
        if (currentPage < totalPages - 1) {
            ItemStack next = createNavigationButton("§6NEXT ▶", NAVIGATION);
            inv.setItem(53, next);
        }
    }

    /**
     * Create a navigation button.
     */
    private ItemStack createNavigationButton(String label, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(label)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Get the slot index for a category tab by name.
     */
    public int getCategoryTabSlot(String categoryName) {
        int index = 0;
        for (ConfigCategory cat : ConfigCategory.values()) {
            if (cat.name().equals(categoryName)) {
                return index;
            }
            index++;
            if (index >= 9) break;
        }
        return -1;
    }

    /**
     * Determine if a slot is a category tab (row 0).
     */
    public boolean isCategoryTab(int slot) {
        return slot >= 0 && slot < 9;
    }

    /**
     * Determine if a slot is a navigation button (row 5).
     */
    public boolean isNavigation(int slot) {
        return slot >= 45 && slot < 54;
    }

    /**
     * Get category name from tab slot.
     */
    public String getCategoryFromSlot(int slot) {
        ConfigCategory[] cats = ConfigCategory.values();
        if (slot >= 0 && slot < cats.length) {
            return cats[slot].name();
        }
        return null;
    }

    /**
     * Calculate total pages for a category.
     */
    public int getTotalPages(String categoryName) {
        ConfigCategory cat = ConfigCategory.valueOf(categoryName);
        int totalSettings = cat.getSettingCount();
        return Math.max(1, (totalSettings + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
    }
}
