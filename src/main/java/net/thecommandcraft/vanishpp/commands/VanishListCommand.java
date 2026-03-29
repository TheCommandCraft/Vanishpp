package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
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

        List<Player> vanished = new ArrayList<>();
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) vanished.add(p);
        }

        var lm = plugin.getConfigManager().getLanguageManager();

        if (vanished.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, lm.getMessage("list.no-vanished-online"));
            return true;
        }

        plugin.getMessageManager().sendMessage(sender,
                lm.getMessage("list.header").replace("%count%", String.valueOf(vanished.size())));

        boolean canUnvanish = sender.hasPermission("vanishpp.vanish");

        // Build a component where each name is clickable (if sender has permission)
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
        return true;
    }
}
