package net.thecommandcraft.vanishpp.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

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
        if (!player.hasPermission("bukkit.command.plugins")) return;
        event.setCancelled(true);

        List<Plugin> filtered = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (!p.getName().equalsIgnoreCase("Vanishpp"))
                filtered.add(p);
        }
        for (Component line : buildPluginList(filtered))
            player.sendMessage(line);

        // Only show the "filtered" info to players with vanishpp.see permission
        // AND who haven't acknowledged it for this version
        if (plugin.getPermissionManager().hasPermission(player, "vanishpp.see")) {
            String ackKey = "hiding_v" + plugin.getConfigManager().getLatestVersion();
            if (!plugin.getPlayerData().acknowledged(player.getUniqueId(), ackKey)) {
                var lm = plugin.getConfigManager().getLanguageManager();
                Component info = Component.text("ℹ ", NamedTextColor.GRAY)
                        .append(plugin.getMessageManager().parse(lm.getMessage("plugins.hidden-info"), player))
                        .append(Component.text(" "))
                        .append(plugin.getMessageManager().parse(lm.getMessage("plugins.button-disable-hiding"), player)
                                .clickEvent(ClickEvent.runCommand("/vack disable_hiding"))
                                .hoverEvent(HoverEvent.showText(plugin.getMessageManager().parse(
                                        lm.getMessage("plugins.button-disable-hiding-hover"), player))))
                        .append(Component.text("  "))
                        .append(plugin.getMessageManager().parse(lm.getMessage("warnings.button-dismiss"), player)
                                .clickEvent(ClickEvent.runCommand("/vack acknowledge_hiding"))
                                .hoverEvent(HoverEvent.showText(plugin.getMessageManager().parse(
                                        lm.getMessage("warnings.button-dismiss-hover"), player))));
                player.sendMessage(info);
            }
        }
    }

    // ── Paper's /plugins layout, copied from io.papermc.paper.command.PaperPluginsCommand ──
    // Paper has no API to filter that output, so we reproduce it exactly (same colors, grouping,
    // hover texts, click action and 10-per-line wrapping).

    private static final TextColor INFO_COLOR = TextColor.color(52, 159, 218);
    private static final Component SERVER_PLUGIN_INFO = Component.text("ℹ What is a server plugin?", INFO_COLOR)
            .append(plain("""
                    Server plugins can add new behavior to your server!
                    You can find new plugins on Paper's plugin repository, Hangar.

                    https://hangar.papermc.io/
                    """));
    private static final Component LEGACY_PLUGIN_INFO = Component.text("ℹ What is a legacy plugin?", INFO_COLOR)
            .append(plain("""
                    A legacy plugin is a plugin that was made on
                    very old unsupported versions of the game.

                    It is encouraged that you replace this plugin,
                    as they might not work in the future and may cause
                    performance issues.
                    """));
    private static final Component LEGACY_PLUGIN_STAR = Component.text('*', TextColor.color(255, 212, 42))
            .hoverEvent(LEGACY_PLUGIN_INFO);
    private static final Component INFO_ICON = Component.text("ℹ ", INFO_COLOR)
            .hoverEvent(SERVER_PLUGIN_INFO)
            .clickEvent(ClickEvent.openUrl("https://docs.papermc.io/paper/adding-plugins"));
    private static final Component PLUGIN_TICK = Component.text("- ", NamedTextColor.DARK_GRAY);

    /** The lines Paper's own /plugins prints for these plugins. */
    public static List<Component> buildPluginList(List<Plugin> plugins) {
        TreeMap<String, Plugin> paper = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        TreeMap<String, Plugin> bukkit = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Plugin p : plugins)
            (isPaperPlugin(p) ? paper : bukkit).put(displayName(p), p);

        boolean both = !paper.isEmpty() && !bukkit.isEmpty();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text().append(INFO_ICON)
                .append(Component.text("Server Plugins (" + plugins.size() + "):", NamedTextColor.WHITE)).build());
        if (!paper.isEmpty())
            lines.add(header("Paper Plugins", 0x0288D1, paper.size(), both));
        lines.addAll(format(paper));
        if (!bukkit.isEmpty())
            lines.add(header("Bukkit Plugins", 0xED8106, bukkit.size(), both));
        lines.addAll(format(bukkit));
        return lines;
    }

    private static List<Component> format(TreeMap<String, Plugin> plugins) {
        List<Component> names = new ArrayList<>();
        for (Plugin p : plugins.values()) {
            TextComponent.Builder b = Component.text();
            if (isLegacy(p))
                b.append(LEGACY_PLUGIN_STAR);
            b.append(Component.text(p.getName(), p.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/version " + p.getName())));
            names.add(b.build());
        }
        List<Component> out = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < names.size(); i += 10) {
            Component start = first ? Component.space().append(PLUGIN_TICK) : Component.text(" ");
            first = false;
            out.add(start.append(Component.join(JoinConfiguration.commas(true),
                    names.subList(i, Math.min(i + 10, names.size())))));
        }
        return out;
    }

    private static Component header(String text, int color, int count, boolean showSize) {
        TextComponent.Builder b = Component.text().color(TextColor.color(color)).append(Component.text(text));
        if (showSize)
            b.appendSpace().append(Component.text("(" + count + ")"));
        return b.append(Component.text(":")).build();
    }

    private static Component plain(String lines) {
        TextComponent.Builder b = Component.text();
        for (String line : lines.split("\n")) {
            b.append(Component.newline());
            b.append(Component.text(line, NamedTextColor.WHITE));
        }
        return b.build();
    }

    private static String displayName(Plugin p) {
        try {
            return p.getPluginMeta().getDisplayName();
        } catch (Throwable t) {
            return p.getName();
        }
    }

    /** Paper plugins (paper-plugin.yml) load through their own class loader; everything else is a Bukkit plugin. */
    private static boolean isPaperPlugin(Plugin p) {
        ClassLoader cl = p.getClass().getClassLoader();
        return cl != null && cl.getClass().getName().contains("PaperPluginClassLoader");
    }

    /** Same rule as Paper's CraftMagicNumbers.isLegacy: no api-version, or older than 1.13. */
    private static boolean isLegacy(Plugin p) {
        if (isPaperPlugin(p))
            return false;
        String api = p.getPluginMeta().getAPIVersion();
        if (api == null)
            return true;
        String[] parts = api.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major == 1 && minor < 13;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
