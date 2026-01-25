package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VanishHelpCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishHelpCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        sender.sendMessage(Component.text("\n      Vanish++ Help Menu      ", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Click a command to see details:", NamedTextColor.GRAY));

        addHelpLine(sender, "/vanish", "Toggle vanish state.", "vanishpp.vanish");
        addHelpLine(sender, "/vrules", "Configure personal ghost rules.", "vanishpp.rules");
        addHelpLine(sender, "/vconfig", "Change plugin settings live.", "vanishpp.config");
        addHelpLine(sender, "/vpickup", "Toggle item pickup quickly.", "vanishpp.pickup");
        addHelpLine(sender, "/vlist", "List all vanished players.", "vanishpp.list");
        addHelpLine(sender, "/vperms", "Manage local permissions.", "vanishpp.manageperms");
        addHelpLine(sender, "/vignore", "Stop ProtocolLib warnings.", "vanishpp.ignorewarning");

        sender.sendMessage(Component.text("-------------------------------", NamedTextColor.GRAY));
        return true;
    }

    private void addHelpLine(CommandSender sender, String cmd, String desc, String perm) {
        Component line = Component.text(" • ", NamedTextColor.GOLD)
                .append(Component.text(cmd, NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand(cmd + " "))
                        .hoverEvent(HoverEvent.showText(Component.text("Perm: " + perm, NamedTextColor.YELLOW))))
                .append(Component.text(" - " + desc, NamedTextColor.WHITE));
        sender.sendMessage(line);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return new ArrayList<>(); // Basic help doesn't need completion currently
    }
}