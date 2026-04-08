package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.state.NetworkVanishState;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VanishListCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishListCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.list")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        // If a proxy is connected and cross-server list is enabled, show all proxy-wide vanished players.
        boolean useProxy = plugin.getProxyBridge() != null
                && plugin.getProxyBridge().isProxyDetected()
                && plugin.getConfigManager().getConfig().getBoolean("proxy.cross-server-list", true);

        if (useProxy) {
            plugin.getProxyBridge().requestPlayerList(states ->
                    plugin.getVanishScheduler().runGlobal(() -> renderProxyList(sender, states)));
            return true;
        }

        // Standalone / proxy disabled: local list only
        renderLocalList(sender);
        return true;
    }

    // ── Local list (per-server, unchanged behaviour) ──────────────────────────

    private void renderLocalList(CommandSender sender) {
        List<Player> vanished = new ArrayList<>();
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) vanished.add(p);
        }

        var lm = plugin.getConfigManager().getLanguageManager();

        if (vanished.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("list.no-vanished-online"));
            return;
        }

        plugin.getMessageManager().sendMessage(sender,
                lm.getMessage("list.header").replace("%count%", String.valueOf(vanished.size())));

        boolean canUnvanish = sender.hasPermission("vanishpp.vanish");

        Component line = Component.empty();
        for (int i = 0; i < vanished.size(); i++) {
            Player p = vanished.get(i);
            int level = plugin.getStorageProvider().getVanishLevel(p.getUniqueId());

            Component name = Component.text(p.getName(), NamedTextColor.GRAY);

            if (canUnvanish) {
                name = name
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/vanish " + p.getName()))
                    .hoverEvent(HoverEvent.showText(
                        Component.text("Click to unvanish ", NamedTextColor.YELLOW)
                            .append(Component.text(p.getName(), NamedTextColor.WHITE))
                            .append(Component.newline())
                            .append(Component.text("Vanish level: ", NamedTextColor.GRAY))
                            .append(Component.text(level, NamedTextColor.AQUA))
                            .append(Component.newline())
                            .append(Component.text("World: ", NamedTextColor.GRAY))
                            .append(Component.text(p.getWorld().getName(), NamedTextColor.WHITE))
                    ));
            }

            line = line.append(name);
            if (i < vanished.size() - 1)
                line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
        }

        sender.sendMessage(line);
    }

    // ── Cross-server proxy list ───────────────────────────────────────────────

    private void renderProxyList(CommandSender sender, List<NetworkVanishState> states) {
        var lm = plugin.getConfigManager().getLanguageManager();

        if (states.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("list.no-vanished-online"));
            return;
        }

        plugin.getMessageManager().sendMessage(sender,
                lm.getMessage("list.header").replace("%count%", String.valueOf(states.size())));

        // Group by server for readability: show server prefix in dark aqua
        String thisServer = plugin.getProxyBridge() != null ? plugin.getProxyBridge().getServerName() : "";

        Component line = Component.empty();
        for (int i = 0; i < states.size(); i++) {
            NetworkVanishState s = states.get(i);
            boolean isLocal = s.serverName().equals(thisServer);

            // Name in gray; server tag in dark aqua (only for remote players)
            Component name = Component.text(s.playerName(), NamedTextColor.GRAY);

            if (!isLocal) {
                name = name.append(
                        Component.text(" [" + s.serverName() + "]", NamedTextColor.DARK_AQUA));
            }

            name = name.hoverEvent(HoverEvent.showText(
                    Component.text("Server: ", NamedTextColor.GRAY)
                             .append(Component.text(s.serverName(), NamedTextColor.WHITE))
                             .append(Component.newline())
                             .append(Component.text("Vanish level: ", NamedTextColor.GRAY))
                             .append(Component.text(s.vanishLevel(), NamedTextColor.AQUA))
            ));

            // Click-to-unvanish only works for players on THIS server
            if (isLocal && sender.hasPermission("vanishpp.vanish")) {
                name = name
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.runCommand("/vanish " + s.playerName()));
            }

            line = line.append(name);
            if (i < states.size() - 1)
                line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
        }

        sender.sendMessage(line);
    }
}
