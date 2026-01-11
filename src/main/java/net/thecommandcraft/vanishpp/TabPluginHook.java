package net.thecommandcraft.vanishpp;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class TabPluginHook {

    private final Vanishpp plugin;
    private boolean enabled = false;

    public TabPluginHook(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (Bukkit.getPluginManager().getPlugin("TAB") != null) {
            this.enabled = true;
            plugin.getLogger().info("Hooked into TAB (NEZNAMY).");
        }
    }

    public void update(Player player, boolean isVanished) {
        if (!enabled) return;

        try {
            TabAPI api = TabAPI.getInstance();
            TabPlayer tabPlayer = api.getPlayer(player.getUniqueId());

            if (tabPlayer != null) {
                TabListFormatManager formatManager = api.getTabListFormatManager();
                if (formatManager != null) {
                    if (isVanished) {
                        String prefix = plugin.getConfigManager().vanishTabPrefix;
                        formatManager.setPrefix(tabPlayer, prefix);
                    } else {
                        formatManager.setPrefix(tabPlayer, null);
                    }
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            // Fail silently if API is incompatible
        }
    }
}