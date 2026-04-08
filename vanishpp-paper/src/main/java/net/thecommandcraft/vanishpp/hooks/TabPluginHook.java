package net.thecommandcraft.vanishpp.hooks;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.thecommandcraft.vanishpp.Vanishpp;
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
                // Tab list prefix
                TabListFormatManager formatManager = api.getTabListFormatManager();
                if (formatManager != null) {
                    if (isVanished) {
                        String prefix = plugin.getConfigManager().vanishTabPrefix;
                        formatManager.setPrefix(tabPlayer, prefix);
                    } else {
                        formatManager.setPrefix(tabPlayer, null);
                    }
                }

                // Nametag prefix (above head) — TAB overrides scoreboard teams, so we must use its API
                NameTagManager nameTagManager = api.getNameTagManager();
                if (nameTagManager != null) {
                    if (isVanished) {
                        String nametagPrefix = plugin.getConfigManager().vanishNametagPrefix;
                        nameTagManager.setPrefix(tabPlayer, nametagPrefix != null ? nametagPrefix : "");
                    } else {
                        nameTagManager.setPrefix(tabPlayer, null);
                    }
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            // Fail silently if API is incompatible
        }
    }
}
