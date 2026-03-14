package net.thecommandcraft.vanishpp.commands;

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

            // Unknown sub-command — acknowledge it before showing the list
            plugin.getMessageManager().sendMessage(sender,
                    "<red>Unknown command '<white>" + args[0] + "<red>'. Showing full list:");
        }

        plugin.getMessageManager().sendMessage(sender, "\n<gold><bold>      Vanish++ Help Menu      ");
        plugin.getMessageManager().sendMessage(sender, "<gray>Click a command to see details:");

        addHelpLine(sender, "vanish", "/vanish", "Toggle vanish state.");
        addHelpLine(sender, "vanishrules", "/vrules", "Configure personal ghost rules.");
        addHelpLine(sender, "vanishconfig", "/vconfig", "Change plugin settings live.");
        addHelpLine(sender, "vanishpickup", "/vpickup", "Toggle item pickup quickly.");
        addHelpLine(sender, "vanishlist", "/vlist", "List all vanished players.");
        addHelpLine(sender, "vperms", "/vperms", "Manage local permissions.");
        addHelpLine(sender, "vanishignore", "/vignore", "Stop ProtocolLib warnings.");

        plugin.getMessageManager().sendMessage(sender, "<gray>-------------------------------");
        return true;
    }

    private void addHelpLine(CommandSender sender, String internalName, String displayCmd, String desc) {
        String line = " • <click:run_command:/vhelp " + internalName + ">" +
                "<hover:show_text:'<yellow>Click for more details'><aqua><bold>" + displayCmd
                + "</bold></aqua></hover></click> " +
                "- <white>" + desc + "</white>";
        plugin.getMessageManager().sendMessage(sender, line);
    }

    private boolean showDetailedHelp(CommandSender sender, String cmdName) {
        var cmd = plugin.getCommand(cmdName);
        if (cmd == null)
            return false;

        plugin.getMessageManager().sendMessage(sender, "\n<gold><bold>--- Command: " + cmd.getName() + " ---");
        plugin.getMessageManager().sendMessage(sender, "<gray>Description: <white>" + cmd.getDescription());
        plugin.getMessageManager().sendMessage(sender, "<gray>Usage: <yellow>" + cmd.getUsage());

        if (!cmd.getAliases().isEmpty()) {
            plugin.getMessageManager().sendMessage(sender,
                    "<gray>Aliases: <white>" + String.join(", ", cmd.getAliases()));
        }

        plugin.getMessageManager().sendMessage(sender,
                "<gray>Permission: <red>" + (cmd.getPermission() != null ? cmd.getPermission() : "None"));

        String buttons = "\n<click:run_command:/vhelp><hover:show_text:'<gray>Return to list'><gray><bold>[BACK TO HELP]</bold></gray></hover></click>   "
                +
                "<click:suggest_command:/ " + cmd.getName()
                + " ><hover:show_text:'<green>Fill in chat box'><green><bold>[USE COMMAND]</bold></green></hover></click>";

        plugin.getMessageManager().sendMessage(sender, buttons);
        plugin.getMessageManager().sendMessage(sender, "<gray>-------------------------------");
        return true;
    }

    private static final List<String> KNOWN_COMMANDS = List.of(
            "vanish", "vrules", "vconfig", "vlist", "vperms", "vignore", "vhelp", "vreload", "vack");

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1)
            return StringUtil.copyPartialMatches(args[0], KNOWN_COMMANDS, new ArrayList<>());
        return new ArrayList<>();
    }
}