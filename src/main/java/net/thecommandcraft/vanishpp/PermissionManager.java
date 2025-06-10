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

    public boolean hasPermission(UUID uuid, String permission) {
        List<String> perms = playerPermissions.get(uuid);
        return perms != null && perms.contains(permission);
    }

    public boolean hasPermission(Player player, String permission) {
        if (player.isOp()) {
            return true;
        }
        if (player.hasPermission(permission)) {
            return true;
        }
        return hasPermission(player.getUniqueId(), permission);
    }
}