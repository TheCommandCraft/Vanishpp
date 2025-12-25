package net.thecommandcraft.vanishpp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

public class RuleManager {

    private final Vanishpp plugin;
    private final Map<UUID, Map<String, Boolean>> playerRules = new HashMap<>();

    // Definition of available rules and their defaults
    public static final String CAN_BREAK_BLOCKS = "can_break_blocks";
    public static final String CAN_PLACE_BLOCKS = "can_place_blocks";
    public static final String CAN_HIT_ENTITIES = "can_hit_entities";
    public static final String CAN_PICKUP_ITEMS = "can_pickup_items";
    public static final String CAN_TRIGGER_PHYSICAL = "can_trigger_physical";
    public static final String CAN_INTERACT = "can_interact";
    public static final String CAN_CHAT = "can_chat";
    public static final String MOB_TARGETING = "mob_targeting";

    private final Map<String, Boolean> defaults = new HashMap<>();

    public RuleManager(Vanishpp plugin) {
        this.plugin = plugin;
        defaults.put(CAN_BREAK_BLOCKS, true);
        defaults.put(CAN_PLACE_BLOCKS, true);
        defaults.put(CAN_HIT_ENTITIES, false);
        defaults.put(CAN_PICKUP_ITEMS, false);
        defaults.put(CAN_TRIGGER_PHYSICAL, false);
        defaults.put(CAN_INTERACT, true);
        defaults.put(CAN_CHAT, false);
        defaults.put(MOB_TARGETING, false);
    }

    public void load() {
        FileConfiguration config = plugin.getConfigManager().getConfig();
        if (config.contains("data.rules")) {
            ConfigurationSection section = config.getConfigurationSection("data.rules");
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
        FileConfiguration config = plugin.getConfigManager().getConfig();
        config.set("data.rules", null); // Clear old
        for (Map.Entry<UUID, Map<String, Boolean>> entry : playerRules.entrySet()) {
            for (Map.Entry<String, Boolean> rule : entry.getValue().entrySet()) {
                config.set("data.rules." + entry.getKey().toString() + "." + rule.getKey(), rule.getValue());
            }
        }
        plugin.saveConfig(); // Ensure main config save is called via wrapper if needed, or rely on main save
    }

    public boolean getRule(Player player, String rule) {
        return getRule(player.getUniqueId(), rule);
    }

    public boolean getRule(UUID uuid, String rule) {
        if (!playerRules.containsKey(uuid)) return defaults.getOrDefault(rule, false);
        return playerRules.get(uuid).getOrDefault(rule, defaults.getOrDefault(rule, false));
    }

    public void setRule(Player player, String rule, boolean value) {
        playerRules.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(rule, value);
        save();
    }

    public void setAllRules(Player player, boolean value) {
        Map<String, Boolean> rules = playerRules.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        for (String key : defaults.keySet()) {
            rules.put(key, value);
        }
        save();
    }

    public Set<String> getAvailableRules() {
        return defaults.keySet();
    }
}