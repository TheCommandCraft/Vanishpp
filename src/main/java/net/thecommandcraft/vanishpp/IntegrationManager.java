package net.thecommandcraft.vanishpp;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapAPI;
import org.jetbrains.annotations.NotNull;

public class IntegrationManager {

    private final Vanishpp plugin;
    private Essentials essentials;
    private DynmapAPI dynmap;
    private boolean papiEnabled;

    public IntegrationManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        // Essentials Hook
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess instanceof Essentials) {
            this.essentials = (Essentials) ess;
            plugin.getLogger().info("Hooked into EssentialsX.");
        }

        // Dynmap Hook
        Plugin dmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dmap instanceof DynmapAPI) {
            this.dynmap = (DynmapAPI) dmap;
            plugin.getLogger().info("Hooked into Dynmap.");
        }

        // PlaceholderAPI Hook
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VanishExpansion(plugin).register();
            this.papiEnabled = true;
            plugin.getLogger().info("Hooked into PlaceholderAPI.");
        }
    }

    public void updateHooks(Player player, boolean isVanished) {
        // Essentials: Marks player as hidden (hides from /who, /list, etc.)
        if (essentials != null) {
            User user = essentials.getUser(player);
            if (user != null) {
                user.setHidden(isVanished);
            }
        }

        // Dynmap: Hides player marker from the web map
        if (dynmap != null) {
            dynmap.assertPlayerInvisibility(player, isVanished, plugin);
        }
    }

    // PAPI Expansion Class
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
        public String onPlaceholderRequest(Player player, @NotNull String identifier) {
            if (player == null) return "";

            // %vanishpp_is_vanished%
            if (identifier.equalsIgnoreCase("is_vanished")) {
                return plugin.isVanished(player) ? "Yes" : "No";
            }
            // %vanishpp_vanished_count%
            if (identifier.equalsIgnoreCase("vanished_count")) {
                return String.valueOf(plugin.getRawVanishedPlayers().size());
            }
            return null;
        }
    }
}