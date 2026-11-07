package net.thecommandcraft.vanishpp.config;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.visibility.AudiencePolicy;
import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

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
    private final Map<UUID, List<String>> playerPermissions = new java.util.concurrent.ConcurrentHashMap<>();

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
        plugin.voiceAudience.invalidateAll();
        playerPermissions.clear();

        ConfigurationSection permsSection = permissionsConfig.getConfigurationSection("permissions");
        if (permsSection != null) {
            for (String uuidString : permsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    List<String> perms = permsSection.getStringList(uuidString);
                    playerPermissions.put(uuid, List.copyOf(perms));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID found in permissions.yml: " + uuidString);
                }
            }
        }
        plugin.voiceAudience.invalidateAll();
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
        plugin.voiceAudience.invalidate(uuid);
        playerPermissions.compute(uuid, (key, old) -> {
            List<String> permissions = new ArrayList<>(old == null ? List.of() : old);
            if (!permissions.contains(permission)) permissions.add(permission);
            return List.copyOf(permissions);
        });
        plugin.voiceAudience.invalidate(uuid);
        save();
    }

    public void removePermission(UUID uuid, String permission) {
        plugin.voiceAudience.invalidate(uuid);
        playerPermissions.computeIfPresent(uuid, (key, old) -> {
            List<String> permissions = new ArrayList<>(old);
            permissions.remove(permission);
            return permissions.isEmpty() ? null : List.copyOf(permissions);
        });
        plugin.voiceAudience.invalidate(uuid);
        save();
    }


    /**
     * Checks if a UUID has a permission in the custom file.
     * Supports 'vanishpp.*' and category groups like 'vanishpp.abilities'.
     */
    public boolean hasPermission(UUID uuid, String permission) {
        List<String> perms = playerPermissions.get(uuid);
        if (perms == null)
            return false;

        // 1. Check for Super Wildcard
        if (perms.contains("vanishpp.*"))
            return true;

        // 2. Check for Exact Match
        if (perms.contains(permission))
            return true;

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
        if (!plugin.getVanishScheduler().owns(player)) {
            var facts = plugin.voiceAudience.facts(player.getUniqueId());
            return hasPermission(player.getUniqueId(), permission)
                    || (permission.equals("vanishpp.see") && facts != null && facts.permission());
        }
        try {
            if (player.hasPermission(permission)) {
                return true;
            }
        } catch (UnsupportedOperationException ignored) {
            // ProtocolLib creates temporary player stubs during Geyser/Floodgate login
            // that throw UnsupportedOperationException on hasPermission — treat as no permission.
            return false;
        }
        return hasPermission(player.getUniqueId(), permission);
    }

    public boolean hasPermission(CommandSender sender, String permission) {
        return sender instanceof Player player ? hasPermission(player, permission) : sender.hasPermission(permission);
    }

    public boolean canSee(Player observer, Player target) {
        if (!plugin.isVanished(target)) return true;
        ConfigManager cm = plugin.getConfigManager();
        return decide(observer, target.getUniqueId(), plugin.isVanished(target),
                getLevel(target, "vanishpp.vanish.level.", cm.defaultVanishLevel));
    }

    public boolean canSee(CommandSender observer, Player target) {
        return !(observer instanceof Player player) || canSee(player, target);
    }

    public boolean canSee(Player observer, UUID target) {
        Player player = Bukkit.getPlayer(target);
        if (player != null) return canSee(observer, player);
        // No local permission facts exist for an offline/network-only packet entry.
        return !plugin.isVanished(target) || (observer != null && observer.getUniqueId().equals(target));
    }

    public boolean canSee(CommandSender observer, UUID target) {
        return !(observer instanceof Player player) || canSee(player, target);
    }

    public net.thecommandcraft.vanishpp.utils.VoiceAudience.Facts voiceFacts(Player player) {
        ConfigManager cm = plugin.getConfigManager();
        return new net.thecommandcraft.vanishpp.utils.VoiceAudience.Facts(hasPermission(player, "vanishpp.see"),
                getLevel(player, "vanishpp.see.level.", cm.defaultSeeLevel), vanishLevel(player));
    }

    public int vanishLevel(Player target) {
        return getLevel(target, "vanishpp.vanish.level.", plugin.getConfigManager().defaultVanishLevel);
    }

    public boolean canSee(CommandSender observer, NetworkVanishState target) {
        if (!(observer instanceof Player player)) return true;
        Player local = Bukkit.getPlayer(target.uuid());
        return local != null ? canSee(player, local) : decide(player, target.uuid(), true, target.vanishLevel());
    }

    public boolean canReceiveStaffNotice(Player observer, Player target) {
        ConfigManager cm = plugin.getConfigManager();
        return hasPermission(observer, "vanishpp.see") && decide(observer, target.getUniqueId(), true,
                getLevel(target, "vanishpp.vanish.level.", cm.defaultVanishLevel));
    }

    public List<Player> visiblePlayers(CommandSender observer) {
        return Bukkit.getOnlinePlayers().stream().filter(player -> canSee(observer, player)).map(player -> (Player) player).toList();
    }

    public int publicOnlineCount() {
        return AudiencePolicy.publicCount(Bukkit.getOnlinePlayers(), plugin::isVanished);
    }

    private boolean decide(Player observer, UUID target, boolean vanished, int vanishLevel) {
        ConfigManager cm = plugin.getConfigManager();
        return AudiencePolicy.canSee(observer == null ? null : observer.getUniqueId(), target, vanished,
                observer != null && hasPermission(observer, "vanishpp.see"), cm.layeredPermsEnabled,
                observer == null ? 0 : getLevel(observer, "vanishpp.see.level.", cm.defaultSeeLevel), vanishLevel);
    }

    private int getLevel(Player player, String prefix, int def) {
        if (!plugin.getVanishScheduler().owns(player)) {
            var facts = plugin.voiceAudience.facts(player.getUniqueId());
            boolean subject = prefix.equals("vanishpp.vanish.level.");
            return facts == null ? (subject ? Integer.MAX_VALUE : 0) : subject ? facts.vanishLevel() : facts.seeLevel();
        }
        if (hasPermission(player, "vanishpp.*"))
            return plugin.getConfigManager().maxLevel;

        int max = plugin.getConfigManager().maxLevel;
        for (int i = max; i > 0; i--) {
            if (hasPermission(player, prefix + i)) {
                return i;
            }
        }
        return def;
    }
}
