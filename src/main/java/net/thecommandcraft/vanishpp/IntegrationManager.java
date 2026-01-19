package net.thecommandcraft.vanishpp;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapAPI;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

public class IntegrationManager {

    private final Vanishpp plugin;
    private Essentials essentials;
    private DynmapAPI dynmap;

    public IntegrationManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess instanceof Essentials) {
            this.essentials = (Essentials) ess;
            plugin.getLogger().info("Hooked into EssentialsX.");
        }

        Plugin dmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dmap instanceof DynmapAPI) {
            this.dynmap = (DynmapAPI) dmap;
            plugin.getLogger().info("Hooked into Dynmap.");
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VanishExpansion(plugin).register();
            plugin.getLogger().info("Hooked into PlaceholderAPI.");
        }
    }

    public void updateHooks(Player player, boolean isVanished) {
        if (essentials != null) {
            User user = essentials.getUser(player);
            if (user != null) {
                user.setHidden(isVanished);
            }
        }

        if (dynmap != null) {
            dynmap.assertPlayerInvisibility(player, isVanished, plugin);
        }
    }

    private static class VanishExpansion extends PlaceholderExpansion {
        private final Vanishpp plugin;

        public VanishExpansion(Vanishpp plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "vanishpp";
        }

        @Override
        public @NotNull String getAuthor() {
            return "tcc";
        }

        @Override
        public @NotNull String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(Player player, @NotNull String identifier) {
            if (identifier.equalsIgnoreCase("is_vanished")) {
                if (player == null) return "";
                return plugin.isVanished(player) ? "Yes" : "No";
            }
            if (identifier.equalsIgnoreCase("is_vanished_bool")) {
                if (player == null) return "false";
                return String.valueOf(plugin.isVanished(player));
            }
            if (identifier.equalsIgnoreCase("vanished_count")) {
                long onlineVanished = Bukkit.getOnlinePlayers().stream().filter(plugin::isVanished).count();
                return String.valueOf(onlineVanished);
            }
            if (identifier.equalsIgnoreCase("visible_online")) {
                long onlineVanished = Bukkit.getOnlinePlayers().stream().filter(plugin::isVanished).count();
                int total = Bukkit.getOnlinePlayers().size();
                return String.valueOf(Math.max(0, total - onlineVanished));
            }
            if (identifier.equalsIgnoreCase("pickup")) {
                if (player == null) return "";
                boolean canPickup = plugin.getRuleManager().getRule(player, RuleManager.CAN_PICKUP_ITEMS);
                return canPickup ? "Enabled" : "Disabled";
            }
            if (identifier.equalsIgnoreCase("prefix")) {
                if (player == null) return "";
                if (plugin.isVanished(player)) {
                    return plugin.getConfigManager().vanishTabPrefix;
                }
                return "";
            }
            if (identifier.equalsIgnoreCase("vanished_list")) {
                return Bukkit.getOnlinePlayers().stream()
                        .filter(plugin::isVanished)
                        .map(Player::getName)
                        .collect(Collectors.joining(", "));
            }
            return null;
        }
    }
}