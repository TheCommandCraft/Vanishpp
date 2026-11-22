package net.thecommandcraft.vanishpp.hooks;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
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
            if (tabPlayer == null) return;
            // Only the incognito alias goes through TAB's API. The [VANISHED] and [V] prefixes are never set here:
            // that would replace TAB's own prefixes (ranks, LuckPerms meta). Staff get them in front of TAB's
            // prefixes in their packets instead (decor.StaffDecorations).
            TabListFormatManager formatManager = api.getTabListFormatManager();
            if (formatManager != null) formatManager.setName(tabPlayer, plugin.getIncognitoName(player.getUniqueId()));
        } catch (NoClassDefFoundError | Exception e) {
            // Fail silently if API is incompatible
        }
    }
}
