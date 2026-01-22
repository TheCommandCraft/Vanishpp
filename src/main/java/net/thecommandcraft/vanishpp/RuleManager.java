package net.thecommandcraft.vanishpp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

public class RuleManager {

    private final Vanishpp plugin;
    private final Map<UUID, Map<String, Boolean>> playerRules = new HashMap<>();

    // Definition of available rules
    public static final String CAN_BREAK_BLOCKS = "can_break_blocks";
    public static final String CAN_PLACE_BLOCKS = "can_place_blocks";
    public static final String CAN_HIT_ENTITIES = "can_hit_entities";
    public static final String CAN_PICKUP_ITEMS = "can_pickup_items";
    public static final String CAN_DROP_ITEMS = "can_drop_items";
    public static final String CAN_TRIGGER_PHYSICAL = "can_trigger_physical";
    public static final String CAN_INTERACT = "can_interact";
    public static final String CAN_CHAT = "can_chat";
    public static final String MOB_TARGETING = "mob_targeting";
    public static final String SHOW_NOTIFICATIONS = "show_notifications";

    private final Map<String, Boolean> hardDefaults = new HashMap<>();

    public RuleManager(Vanishpp plugin) {
        this.plugin = plugin;
        hardDefaults.put(CAN_BREAK_BLOCKS, false);
        hardDefaults.put(CAN_PLACE_BLOCKS, false);
        hardDefaults.put(CAN_HIT_ENTITIES, false);
        hardDefaults.put(CAN_PICKUP_ITEMS, false);
        hardDefaults.put(CAN_DROP_ITEMS, false);
        hardDefaults.put(CAN_TRIGGER_PHYSICAL, false);
        hardDefaults.put(CAN_INTERACT, true);
        hardDefaults.put(CAN_CHAT, false);
        hardDefaults.put(MOB_TARGETING, false);
        hardDefaults.put(SHOW_NOTIFICATIONS, true);
    }

    public void load() {
        // Load overrides from DATA.YML (User specific)
        FileConfiguration data = plugin.getDataManager().getConfig();
        if (data.contains("rules")) {
            ConfigurationSection section = data.getConfigurationSection("rules");
            if (section != null) {
                for (String uuidStr : section.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        ConfigurationSection playerSection = section.getConfigurationSection(uuidStr);
                        Map<String, Boolean> rules = new HashMap<>();
                        if (playerSection != null) {
                            for (String key : playerSection.getKeys(false)) {
                                rules.put(key, playerSection.getBoolean(key));
                            }
                        }
                        playerRules.put(uuid, rules);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public void save() {
        // Save overrides to DATA.YML
        FileConfiguration data = plugin.getDataManager().getConfig();
        data.set("rules", null); // Clear old to handle removals
        for (Map.Entry<UUID, Map<String, Boolean>> entry : playerRules.entrySet()) {
            for (Map.Entry<String, Boolean> rule : entry.getValue().entrySet()) {
                data.set("rules." + entry.getKey().toString() + "." + rule.getKey(), rule.getValue());
            }
        }
        plugin.getDataManager().save();
    }

    public boolean getRule(Player player, String rule) {
        return getRule(player.getUniqueId(), rule);
    }

    public boolean getRule(UUID uuid, String rule) {
        // 1. Check Player Specific Override
        if (playerRules.containsKey(uuid)) {
            Map<String, Boolean> pRules = playerRules.get(uuid);
            if (pRules.containsKey(rule)) return pRules.get(rule);
        }

        // 2. Check Config Default (Global setting)
        Map<String, Boolean> configDefaults = plugin.getConfigManager().defaultRules;
        if (configDefaults.containsKey(rule)) return configDefaults.get(rule);

        // 3. Check Hardcoded Default (Fallback)
        return hardDefaults.getOrDefault(rule, false);
    }

    public void setRule(Player player, String rule, boolean value) {
        playerRules.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(rule, value);
        save();
    }

    public void setAllRules(Player player, boolean value) {
        Map<String, Boolean> rules = playerRules.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        for (String key : hardDefaults.keySet()) {
            // Notifications usually shouldn't be toggled by "all"
            if (key.equals(SHOW_NOTIFICATIONS)) continue;
            rules.put(key, value);
        }
        save();
    }

    public Set<String> getAvailableRules() {
        return hardDefaults.keySet();
    }
}