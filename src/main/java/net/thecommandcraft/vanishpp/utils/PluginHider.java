package net.thecommandcraft.vanishpp.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

        String message = event.getMessage().toLowerCase().trim();
        if (!message.equals("/plugins") && !message.equals("/pl")
                && !message.startsWith("/plugins ") && !message.startsWith("/pl ")
                && !message.startsWith("/bukkit:plugins") && !message.startsWith("/bukkit:pl"))
            return;

        Player player = event.getPlayer();
        event.setCancelled(true);

        // Build filtered plugin list (exclude Vanishpp)
        List<Plugin> filtered = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (!p.getName().equalsIgnoreCase("Vanishpp"))
                filtered.add(p);
        }

        // Build colored plugin name components (green = enabled, red = disabled)
        List<Component> nameComponents = new ArrayList<>();
        for (Plugin p : filtered) {
            nameComponents.add(Component.text(p.getName(),
                    p.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }

        Component header = Component.text("Plugins (" + filtered.size() + "): ", NamedTextColor.WHITE);
        Component list = Component.join(JoinConfiguration.separator(Component.text(", ", NamedTextColor.WHITE)),
                nameComponents);
        player.sendMessage(header.append(list));

        // If seer/op — also show info that the list is filtered
        boolean isSeer = plugin.getPermissionManager().hasPermission(player, "vanishpp.see");
        if (isSeer) {
            Component info = Component.text("ℹ ", NamedTextColor.GRAY)
                    .append(Component.text("Plugin list is filtered (Vanish++ hidden from non-staff).", NamedTextColor.DARK_GRAY))
                    .append(Component.text(" "))
                    .append(Component.text("[Disable hiding]", NamedTextColor.RED, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/vconfig hide-announcements.hide-from-plugin-list false"))
                            .hoverEvent(HoverEvent.showText(Component.text("Sets hide-from-plugin-list to false", NamedTextColor.GRAY))));
            player.sendMessage(info);
        }
    }
}
