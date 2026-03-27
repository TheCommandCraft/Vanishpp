package net.thecommandcraft.vanishpp.config;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;

import java.util.*;

public class RuleManager {

    private final Vanishpp plugin;

    // Definition of available rules
    public static final String CAN_BREAK_BLOCKS = "can_break_blocks";
    public static final String CAN_PLACE_BLOCKS = "can_place_blocks";
    public static final String CAN_HIT_ENTITIES = "can_hit_entities";
    public static final String CAN_PICKUP_ITEMS = "can_pickup_items";
    public static final String CAN_DROP_ITEMS = "can_drop_items";
    public static final String CAN_TRIGGER_PHYSICAL = "can_trigger_physical";
    public static final String CAN_INTERACT = "can_interact";
    public static final String CAN_THROW = "can_throw";
    public static final String CAN_CHAT = "can_chat";
    public static final String MOB_TARGETING = "mob_targeting";
    public static final String SHOW_NOTIFICATIONS = "show_notifications";
    public static final String SPECTATOR_GAMEMODE = "spectator_gamemode";

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
        hardDefaults.put(CAN_THROW, false);
        hardDefaults.put(CAN_CHAT, false);
        hardDefaults.put(MOB_TARGETING, false);
        hardDefaults.put(SHOW_NOTIFICATIONS, true);
        hardDefaults.put(SPECTATOR_GAMEMODE, true);
    }

    public void load() {
        // Data is now loaded lazily or pre-loaded by StorageProvider
    }

    public void save() {
        // StorageProvider handles immediate or batched saves
    }

    public boolean getRule(Player player, String rule) {
        return getRule(player.getUniqueId(), rule);
    }

    public boolean getRule(UUID uuid, String rule) {
        // 1. Check Storage Provider for Player Specific Override
        Map<String, Object> playerRules = plugin.getStorageProvider().getRules(uuid);
        if (playerRules.containsKey(rule)) {
            Object val = playerRules.get(rule);
            if (val instanceof Boolean)
                return (Boolean) val;
            if (val instanceof String)
                return Boolean.parseBoolean((String) val);
        }

        // 2. Check Config Default (Global setting)
        Map<String, Boolean> configDefaults = plugin.getConfigManager().defaultRules;
        if (configDefaults.containsKey(rule))
            return configDefaults.get(rule);

        // 3. Check Hardcoded Default (Fallback)
        return hardDefaults.getOrDefault(rule, false);
    }

    public void setRule(Player player, String rule, boolean value) {
        plugin.getStorageProvider().setRule(player.getUniqueId(), rule, value);
    }

    public void setAllRules(Player player, boolean value) {
        for (String key : hardDefaults.keySet()) {
            if (key.equals(SHOW_NOTIFICATIONS))
                continue;
            plugin.getStorageProvider().setRule(player.getUniqueId(), key, value);
        }
    }

    public Set<String> getAvailableRules() {
        return hardDefaults.keySet();
    }
}