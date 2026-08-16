package net.thecommandcraft.vanishpp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Renders the ConfigGUI inventory with categories, settings, and pagination.
 * Handles layout calculations, item creation, and responsive design.
 *
 * <p>All display text is looked up fresh from {@code messages.yml} on every render (never
 * cached) so a {@code /vconfig reload} takes effect the next time a viewer's GUI redraws.
 */
public class ConfigRenderer {

    private final Vanishpp plugin;

    public ConfigRenderer(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /** Fresh language-file lookup, parsed through MessageManager, with default italics off. */
    private Component guiText(String key) {
        return plugin.getMessageManager().parse(plugin.getLanguageManager().getMessage(key), null)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static final int INVENTORY_SIZE = 54;
    private static final int ITEMS_PER_ROW = 7;
    private static final int INDENT_WRAPPING = 2;
    private static final int CATEGORY_ROW = 0;
    private static final int SPACER_ROW = 1;
    private static final int SETTINGS_START_ROW = 2;
    private static final int NAVIGATION_ROW = 5;
    private static final int SETTINGS_CONTENT_ROWS = 3;  // Rows 2, 3, 4
    // Actual per-page rendering capacity: placeSettings() reserves INDENT_WRAPPING slots
    // on EVERY wrapped row (not just once total), so each of the 3 rows only ever holds
    // ITEMS_PER_ROW items — 3*7=21, not (3*9)-2=25 as this previously computed. Harmless
    // while every category has <=21 settings (true today), but a category with 22-25
    // settings would silently drop items past #21 off the end of the page with no error,
    // and anything beyond 25 would misalign page boundaries entirely.
    private static final int ITEMS_PER_PAGE = SETTINGS_CONTENT_ROWS * ITEMS_PER_ROW;

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
        ConfigCategory cat0 = ConfigCategory.valueOf(category);
        String titlePrefix = plugin.getLanguageManager().getMessage("gui.config.title-prefix");
        Component categoryName = plugin.getMessageManager().parse(
                plugin.getLanguageManager().getMessage("gui.config.category." + cat0.getCategoryKey()), null);
        Component title = plugin.getMessageManager().parse(titlePrefix, null).append(categoryName);
        Inventory inv = Bukkit.createInventory(null, INVENTORY_SIZE, title);
        Map<Integer, String> slotToKey = new HashMap<>();

        // Row 0: Category tabs
        placeCategoryTabs(inv, category);

        // Rows 2+: Settings with wrapping layout
        List<ConfigCategory.ConfigValue> settings = new ArrayList<>(cat0.getSettings().values());
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
            ItemStack tab = createCategoryTab(category, isActive);
            inv.setItem(slot++, tab);
        }
    }

    /**
     * Create a category tab item.
     */
    private ItemStack createCategoryTab(ConfigCategory category, boolean isActive) {
        ItemStack item = new ItemStack(isActive ? CATEGORY_ACTIVE : CATEGORY_INACTIVE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = plugin.getLanguageManager().getMessage("gui.config.category." + category.getCategoryKey());
            meta.displayName(Component.text(name, isActive ? NamedTextColor.YELLOW : NamedTextColor.BLUE)
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
            String descriptionKey = "gui.config.setting-description." + value.key;
            lore.add(Component.text(plugin.getLanguageManager().getMessage(descriptionKey), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());

            if (value.type.isBoolean()) {
                Object val = value.defaultValue;
                boolean isTrue = val instanceof Boolean && (Boolean) val;
                String statusKey = isTrue ? "gui.config.setting.value-true" : "gui.config.setting.value-false";
                String current = plugin.getLanguageManager().getMessage("gui.config.setting.current-prefix")
                        + plugin.getLanguageManager().getMessage(statusKey);
                lore.add(plugin.getMessageManager().parse(current, null).decoration(TextDecoration.ITALIC, false));
                lore.add(guiText("gui.config.setting.click-to-toggle"));
            } else if (value.type.isNumeric()) {
                Object val = value.defaultValue;
                String currentVal = val != null ? String.valueOf(val) : "?";
                String current = plugin.getLanguageManager().getMessage("gui.config.setting.current-prefix") + currentVal;
                lore.add(plugin.getMessageManager().parse(current, null).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(guiText("gui.config.setting.numeric-controls"));
                lore.add(guiText("gui.config.setting.numeric-controls-shift"));
                lore.add(Component.empty());
                String range = plugin.getLanguageManager().getMessage("gui.config.setting.range")
                        .replace("%min%", String.valueOf(value.minBound))
                        .replace("%max%", String.valueOf(value.maxBound));
                lore.add(plugin.getMessageManager().parse(range, null).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(guiText("gui.config.setting.display-only"));
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
            inv.setItem(45, createNavigationButton(guiText("gui.config.nav.previous")));
        }

        // Info button (slot 49)
        String info = plugin.getLanguageManager().getMessage("gui.config.nav.page-info")
                .replace("%page%", String.valueOf(currentPage + 1))
                .replace("%total%", String.valueOf(totalPages));
        inv.setItem(49, createNavigationButton(
                plugin.getMessageManager().parse(info, null).decoration(TextDecoration.ITALIC, false)));

        // Next button (slot 53)
        if (currentPage < totalPages - 1) {
            inv.setItem(53, createNavigationButton(guiText("gui.config.nav.next")));
        }
    }

    /**
     * Create a navigation button.
     */
    private ItemStack createNavigationButton(Component label) {
        ItemStack item = new ItemStack(NAVIGATION);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(label);
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
