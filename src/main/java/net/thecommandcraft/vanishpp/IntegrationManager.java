package net.thecommandcraft.vanishpp;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapAPI;

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
}