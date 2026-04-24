package net.thecommandcraft.vanishpp.hooks;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapAPI;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class IntegrationManager {

    private final Vanishpp plugin;
    private Essentials essentials;
    private DynmapAPI dynmap;
    private DiscordSRVHook discordSRV;

    public IntegrationManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess instanceof Essentials) {
            this.essentials = (Essentials) ess;
            plugin.getLogger().info("Hooked into EssentialsX.");
        }

        try {
            Plugin dmap = Bukkit.getPluginManager().getPlugin("dynmap");
            if (dmap instanceof DynmapAPI) {
                this.dynmap = (DynmapAPI) dmap;
                plugin.getLogger().info("Hooked into Dynmap.");
            }
        } catch (Throwable ignored) {
            plugin.getLogger().warning("Dynmap found but hook failed to load.");
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VanishExpansion(plugin).register();
            plugin.getLogger().info("Hooked into PlaceholderAPI.");
        }

        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") != null) {
            try {
                this.discordSRV = new DiscordSRVHook(plugin);
                this.discordSRV.register();
                plugin.getLogger().info("Hooked into DiscordSRV.");
            } catch (Throwable e) {
                plugin.getLogger().warning("DiscordSRV found but hook failed to load: " + e.getMessage());
            }
        }
    }

    public void unregister() {
        if (discordSRV != null) {
            discordSRV.unregister();
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

    public String getEssentialsJoinMessage() {
        if (essentials == null)
            return null;
        try {
            String m = essentials.getSettings().getCustomJoinMessage();
            return (m != null && !m.equals("none")) ? m.replace("&", "§") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public String getEssentialsQuitMessage() {
        if (essentials == null)
            return null;
        try {
            String m = essentials.getSettings().getCustomQuitMessage();
            return (m != null && !m.equals("none")) ? m.replace("&", "§") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public DiscordSRVHook getDiscordSRV() {
        return discordSRV;
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
                if (player == null)
                    return "";
                return plugin.isVanished(player) ? "Yes" : "No";
            }
            if (identifier.equalsIgnoreCase("is_vanished_bool")) {
                if (player == null)
                    return "false";
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
                if (player == null)
                    return "";
                boolean canPickup = plugin.getRuleManager().getRule(player, RuleManager.CAN_PICKUP_ITEMS);
                return canPickup ? "Enabled" : "Disabled";
            }
            if (identifier.equalsIgnoreCase("prefix")) {
                if (player == null)
                    return "";
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
            if (identifier.equalsIgnoreCase("visible_player_list")) {
                return Bukkit.getOnlinePlayers().stream()
                        .filter(p -> !plugin.isVanished(p))
                        .map(Player::getName)
                        .collect(Collectors.joining(", "));
            }

            // %vanishpp_vanish_duration% — how long this player has been vanished
            if (identifier.equalsIgnoreCase("vanish_duration")) {
                if (player == null) return "";
                long start = plugin.getVanishStartTime(player.getUniqueId());
                if (start < 0) return "0s";
                long secs = (System.currentTimeMillis() - start) / 1000L;
                return formatDuration(secs);
            }

            // %vanishpp_vanish_reason% — current vanish reason or empty string
            if (identifier.equalsIgnoreCase("vanish_reason")) {
                if (player == null) return "";
                String reason = plugin.getVanishReason(player.getUniqueId());
                return reason != null ? reason : "";
            }

            // %vanishpp_vanish_level_<player>% — vanish level of the named player
            if (identifier.toLowerCase().startsWith("vanish_level_")) {
                String targetName = identifier.substring("vanish_level_".length());
                Player target = Bukkit.getPlayer(targetName);
                if (target == null) return "0";
                return String.valueOf(plugin.getStorageProvider().getVanishLevel(target.getUniqueId()));
            }

            // %vanishpp_is_vanished_<player>% — true/false for a named player
            if (identifier.toLowerCase().startsWith("is_vanished_") && identifier.length() > "is_vanished_".length()) {
                // Must not be "is_vanished" (already handled) or "is_vanished_bool"
                String targetName = identifier.substring("is_vanished_".length());
                if (!targetName.equalsIgnoreCase("bool")) {
                    Player target = Bukkit.getPlayer(targetName);
                    if (target == null) return "false";
                    return String.valueOf(plugin.isVanished(target));
                }
            }

            // %vanishpp_rule_<rule>% — current value of a rule for the requesting player
            if (identifier.toLowerCase().startsWith("rule_")) {
                if (player == null) return "";
                String ruleName = identifier.substring("rule_".length());
                boolean val = plugin.getRuleManager().getRule(player, ruleName);
                return String.valueOf(val);
            }

            // %vanishpp_can_see_<player>% — whether the requesting player can see the named player
            if (identifier.toLowerCase().startsWith("can_see_")) {
                if (player == null) return "false";
                String targetName = identifier.substring("can_see_".length());
                Player target = Bukkit.getPlayer(targetName);
                if (target == null) return "false";
                return String.valueOf(plugin.getPermissionManager().canSee(player, target));
            }

            return null;
        }

        private static String formatDuration(long totalSeconds) {
            long h = totalSeconds / 3600;
            long m = (totalSeconds % 3600) / 60;
            long s = totalSeconds % 60;
            if (h > 0) return h + "h " + m + "m " + s + "s";
            if (m > 0) return m + "m " + s + "s";
            return s + "s";
        }
    }
}