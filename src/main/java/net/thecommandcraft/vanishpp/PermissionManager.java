package net.thecommandcraft.vanishpp;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.File;
import java.util.*;

public class PermissionManager {

    private final Vanishpp plugin;
    private final File permissionsFile;
    private FileConfiguration permissionsConfig;
    private final Map<UUID, List<String>> playerPermissions = new HashMap<>();

    public PermissionManager(Vanishpp plugin) {
        this.plugin = plugin;
        this.permissionsFile = new File(plugin.getDataFolder(), "permissions.yml");
    }

    public void load() {
        if (!permissionsFile.exists()) plugin.saveResource("permissions.yml", false);
        this.permissionsConfig = YamlConfiguration.loadConfiguration(permissionsFile);
        playerPermissions.clear();
        if (permissionsConfig.contains("permissions")) {
            for (String key : permissionsConfig.getConfigurationSection("permissions").getKeys(false)) {
                try {
                    playerPermissions.put(UUID.fromString(key), permissionsConfig.getStringList("permissions." + key));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void addPermission(UUID uuid, String permission) {
        List<String> list = playerPermissions.computeIfAbsent(uuid, k -> new ArrayList<>());
        if (!list.contains(permission)) list.add(permission);
        save();
    }

    public void removePermission(UUID uuid, String permission) {
        if (playerPermissions.containsKey(uuid)) {
            playerPermissions.get(uuid).remove(permission);
            if (playerPermissions.get(uuid).isEmpty()) playerPermissions.remove(uuid);
            save();
        }
    }

    private void save() {
        permissionsConfig.set("permissions", null);
        for (Map.Entry<UUID, List<String>> entry : playerPermissions.entrySet()) {
            permissionsConfig.set("permissions." + entry.getKey().toString(), entry.getValue());
        }
        try { permissionsConfig.save(permissionsFile); } catch (Exception e) { e.printStackTrace(); }
    }

    public boolean hasPermission(Player player, String permission) {
        if (player.isOp()) return true;
        if (player.hasPermission("vanishpp.*")) return true;
        if (player.hasPermission(permission)) return true;
        return hasPermission(player.getUniqueId(), permission);
    }

    public boolean hasPermission(UUID uuid, String permission) {
        List<String> perms = playerPermissions.get(uuid);
        return perms != null && perms.contains(permission);
    }

    /**
     * Determines if an observer can see a target based on Layered Permissions.
     */
    public boolean canSee(Player observer, Player target) {
        // If target isn't vanished, everyone sees them (handled in updateVanishVisibility, but good safety)
        if (!plugin.isVanished(target)) return true;

        // Check simple perm first
        if (!hasPermission(observer, "vanishpp.see")) return false;

        // Layered Check
        ConfigManager cm = plugin.getConfigManager();
        if (cm.layeredPermsEnabled) {
            int targetVanishLevel = getLevel(target, "vanishpp.vanish.level.", cm.defaultVanishLevel);
            int observerSeeLevel = getLevel(observer, "vanishpp.see.level.", cm.defaultSeeLevel);

            // If Observer See Level is LESS than Target Vanish Level, they CANNOT see.
            if (observerSeeLevel < targetVanishLevel) {
                return false;
            }
        }

        return true;
    }

    private int getLevel(Player player, String prefix, int def) {
        if (player.isOp() || player.hasPermission("vanishpp.*")) return plugin.getConfigManager().maxLevel;

        // Loop permissions to find highest level
        // Ideally we start from Max and go down
        int max = plugin.getConfigManager().maxLevel;
        for (int i = max; i > 0; i--) {
            if (player.hasPermission(prefix + i)) {
                return i;
            }
        }
        return def;
    }
}