package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class PluginHider implements Listener {

    private final Vanishpp plugin;

    public PluginHider(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().hideFromPluginList)
            return;

        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/plugins") || message.startsWith("/pl") || message.startsWith("/bukkit:plugins")
                || message.startsWith("/bukkit:pl")) {
            Player player = event.getPlayer();
            if (player.hasPermission("vanishpp.staff"))
                return;

            event.setCancelled(true);

            // Send a filtered list or a "no permission" style fake list
            List<String> plugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .map(p -> p.getName())
                    .filter(name -> !name.equalsIgnoreCase("Vanishpp"))
                    .collect(Collectors.toList());

            String pluginList = "Plugins (" + plugins.size() + "): " + String.join(", ", plugins);
            player.sendMessage(pluginList);
        }
    }
}
