package net.thecommandcraft.vanishpp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PermissionManager {

    private final Vanishpp plugin;
    private final File permissionsFile;
    private FileConfiguration permissionsConfig;
    private final Map<UUID, List<String>> playerPermissions = new HashMap<>();

    // Definitions for custom permission groups
    private static final Map<String, Set<String>> CUSTOM_GROUPS = new HashMap<>();

    static {
        // Group: vanishpp.abilities
        Set<String> abilities = new HashSet<>();
        abilities.add("vanishpp.silentchest");
        abilities.add("vanishpp.chat");
        abilities.add("vanishpp.notarget");
        abilities.add("vanishpp.nohunger");
        abilities.add("vanishpp.nightvision");
        abilities.add("vanishpp.fly");
        abilities.add("vanishpp.no-raid");
        abilities.add("vanishpp.no-sculk");
        abilities.add("vanishpp.no-trample");
        abilities.add("vanishpp.join-vanished");
        CUSTOM_GROUPS.put("vanishpp.abilities", abilities);

        // Group: vanishpp.management
        Set<String> management = new HashSet<>();
        management.add("vanishpp.manageperms");
        management.add("vanishpp.ignorewarning");
        management.add("vanishpp.rules");
        management.add("vanishpp.rules.others");
        management.add("vanishpp.pickup");
        management.add("vanishpp.pickup.others");
        CUSTOM_GROUPS.put("vanishpp.management", management);

        // Group: vanishpp.core
        Set<String> core = new HashSet<>();
        core.add("vanishpp.vanish");
        core.add("vanishpp.vanish.others");
        core.add("vanishpp.see");
        CUSTOM_GROUPS.put("vanishpp.core", core);
    }

    public PermissionManager(Vanishpp plugin) {
        this.plugin = plugin;
        this.permissionsFile = new File(plugin.getDataFolder(), "permissions.yml");
    }

    public void load() {
        if (!permissionsFile.exists()) {
            plugin.saveResource("permissions.yml", false);
        }
        this.permissionsConfig = YamlConfiguration.loadConfiguration(permissionsFile);
        playerPermissions.clear();

        ConfigurationSection permsSection = permissionsConfig.getConfigurationSection("permissions");
        if (permsSection != null) {
            for (String uuidString : permsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    List<String> perms = permsSection.getStringList(uuidString);
                    playerPermissions.put(uuid, new ArrayList<>(perms));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID found in permissions.yml: " + uuidString);
                }
            }
        }
        plugin.getLogger().info("Loaded custom permissions for " + playerPermissions.size() + " players.");
    }

    private synchronized void save() {
        permissionsConfig.set("permissions", null);
        ConfigurationSection permsSection = permissionsConfig.createSection("permissions");
        for (Map.Entry<UUID, List<String>> entry : playerPermissions.entrySet()) {
            permsSection.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            permissionsConfig.save(permissionsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save permissions.yml!");
            e.printStackTrace();
        }
    }

    public void addPermission(UUID uuid, String permission) {
        List<String> perms = playerPermissions.computeIfAbsent(uuid, k -> new ArrayList<>());
        if (!perms.contains(permission)) {
            perms.add(permission);
        }
        save();
    }

    public void removePermission(UUID uuid, String permission) {
        List<String> perms = playerPermissions.get(uuid);
        if (perms != null) {
            perms.remove(permission);
            if (perms.isEmpty()) {
                playerPermissions.remove(uuid);
            }
        }
        save();
    }

    /**
     * Checks if a UUID has a permission in the custom file.
     * Supports 'vanishpp.*' and category groups like 'vanishpp.abilities'.
     */
    public boolean hasPermission(UUID uuid, String permission) {
        List<String> perms = playerPermissions.get(uuid);
        if (perms == null) return false;

        // 1. Check for Super Wildcard
        if (perms.contains("vanishpp.*")) return true;

        // 2. Check for Exact Match
        if (perms.contains(permission)) return true;

        // 3. Check for Group Inheritance
        // Example: User wants "vanishpp.fly". We check if user has "vanishpp.abilities"
        // because "vanishpp.abilities" contains "vanishpp.fly".
        for (Map.Entry<String, Set<String>> group : CUSTOM_GROUPS.entrySet()) {
            if (group.getValue().contains(permission)) {
                if (perms.contains(group.getKey())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks permission via Bukkit (OP/Plugins) OR Custom File.
     */
    public boolean hasPermission(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        return hasPermission(player.getUniqueId(), permission);
    }

    public boolean canSee(Player observer, Player target) {
        if (!plugin.isVanished(target)) return true;
        if (!hasPermission(observer, "vanishpp.see")) return false;

        ConfigManager cm = plugin.getConfigManager();
        if (cm.layeredPermsEnabled) {
            int targetVanishLevel = getLevel(target, "vanishpp.vanish.level.", cm.defaultVanishLevel);
            int observerSeeLevel = getLevel(observer, "vanishpp.see.level.", cm.defaultSeeLevel);
            if (observerSeeLevel < targetVanishLevel) {
                return false;
            }
        }
        return true;
    }

    private int getLevel(Player player, String prefix, int def) {
        if (hasPermission(player, "vanishpp.*")) return plugin.getConfigManager().maxLevel;

        int max = plugin.getConfigManager().maxLevel;
        for (int i = max; i > 0; i--) {
            if (hasPermission(player, prefix + i)) {
                return i;
            }
        }
        return def;
    }
}