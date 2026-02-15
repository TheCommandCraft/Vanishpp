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
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase();
            // Map common aliases to internal names if needed
            if (sub.equals("v") || sub.equals("sv"))
                sub = "vanish";
            if (sub.equals("vrules") || sub.equals("vsettings"))
                sub = "vanishrules";
            if (sub.equals("vchat"))
                sub = "vanishchat";
            if (sub.equals("vpickup") || sub.equals("tipu"))
                sub = "vanishpickup";
            if (sub.equals("vignore"))
                sub = "vanishignore";
            if (sub.equals("vlist"))
                sub = "vanishlist";
            if (sub.equals("vhelp"))
                sub = "vanishhelp";
            if (sub.equals("vconfig"))
                sub = "vanishconfig";
            if (sub.equals("vperms"))
                sub = "vperms";

            if (showDetailedHelp(sender, sub))
                return true;
        }

        sender.sendMessage(
                Component.text("\n      Vanish++ Help Menu      ", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Click a command to see details:", NamedTextColor.GRAY));

        addHelpLine(sender, "vanish", "/vanish", "Toggle vanish state.");
        addHelpLine(sender, "vanishrules", "/vrules", "Configure personal ghost rules.");
        addHelpLine(sender, "vanishconfig", "/vconfig", "Change plugin settings live.");
        addHelpLine(sender, "vanishpickup", "/vpickup", "Toggle item pickup quickly.");
        addHelpLine(sender, "vanishlist", "/vlist", "List all vanished players.");
        addHelpLine(sender, "vperms", "/vperms", "Manage local permissions.");
        addHelpLine(sender, "vanishignore", "/vignore", "Stop ProtocolLib warnings.");

        sender.sendMessage(Component.text("-------------------------------", NamedTextColor.GRAY));
        return true;
    }

    private void addHelpLine(CommandSender sender, String internalName, String displayCmd, String desc) {
        Component line = Component.text(" • ", NamedTextColor.GOLD)
                .append(Component.text(displayCmd, NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/vhelp " + internalName))
                        .hoverEvent(
                                HoverEvent.showText(Component.text("Click for more details", NamedTextColor.YELLOW))))
                .append(Component.text(" - " + desc, NamedTextColor.WHITE));
        sender.sendMessage(line);
    }

    private boolean showDetailedHelp(CommandSender sender, String cmdName) {
        var cmd = plugin.getCommand(cmdName);
        if (cmd == null)
            return false;

        sender.sendMessage(
                Component.text("\n--- Command: " + cmd.getName() + " ---", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Description: ", NamedTextColor.GRAY)
                .append(Component.text(cmd.getDescription(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Usage: ", NamedTextColor.GRAY)
                .append(Component.text(cmd.getUsage(), NamedTextColor.YELLOW)));

        if (!cmd.getAliases().isEmpty()) {
            sender.sendMessage(Component.text("Aliases: ", NamedTextColor.GRAY)
                    .append(Component.text(String.join(", ", cmd.getAliases()), NamedTextColor.WHITE)));
        }

        sender.sendMessage(Component.text("Permission: ", NamedTextColor.GRAY).append(
                Component.text(cmd.getPermission() != null ? cmd.getPermission() : "None", NamedTextColor.RED)));

        Component buttons = Component.text("\n")
                .append(Component.text("[BACK TO HELP]", NamedTextColor.GRAY, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/vhelp"))
                        .hoverEvent(HoverEvent.showText(Component.text("Return to list", NamedTextColor.GRAY))))
                .append(Component.text("   "))
                .append(Component.text("[USE COMMAND]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand("/" + cmd.getName() + " "))
                        .hoverEvent(HoverEvent.showText(Component.text("Fill in chat box", NamedTextColor.GREEN))));

        sender.sendMessage(buttons);
        sender.sendMessage(Component.text("-------------------------------", NamedTextColor.GRAY));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        return new ArrayList<>(); // Basic help doesn't need completion currently
    }
}