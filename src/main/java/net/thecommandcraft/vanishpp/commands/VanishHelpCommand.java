package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.utils.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VanishHelpCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    // Detailed help info: command -> {description, usage, notes}
    private static final Map<String, String[]> DETAILED_HELP = Map.ofEntries(
            Map.entry("vanish", new String[]{
                    "Toggle vanish for yourself or another player.",
                    "/vanish [player]",
                    "Omit player to toggle yourself. Requires vanishpp.vanish.others for other players."
            }),
            Map.entry("vanishrules", new String[]{
                    "View or modify personal vanish rules.",
                    "/vrules [player] <rule> [true|false] [seconds]",
                    "Seconds is optional — omit it for a permanent change. Use 'all' or 'none' instead of a rule name to set all rules at once."
            }),
            Map.entry("vanishconfig", new String[]{
                    "View or change plugin settings in-game.",
                    "/vconfig <key> [value]",
                    "Sensitive settings (storage, permissions) require re-entering with --confirm. Changes apply immediately."
            }),
            Map.entry("vanishlist", new String[]{
                    "Lists all currently vanished players.",
                    "/vlist",
                    "Shows vanished player count and names."
            }),
            Map.entry("vperms", new String[]{
                    "Manage Vanish++ specific permissions for players.",
                    "/vperms <player> <permission> <set|remove|get>",
                    "Use '/vperms reload' to reload permission data. Works with offline players."
            }),
            Map.entry("vanishignore", new String[]{
                    "Toggle the ProtocolLib missing warning on join.",
                    "/vignore [player]",
                    "Stops showing the 'ProtocolLib not installed' warning. Omit player for yourself."
            }),
            Map.entry("vanishchat", new String[]{
                    "Confirm sending a chat message while vanished.",
                    "/vchat confirm",
                    "When chat confirmation is enabled, messages are held until you run this."
            }),
            Map.entry("vanishreload", new String[]{
                    "Reloads the Vanish++ configuration from disk.",
                    "/vreload",
                    "Reloads config.yml and messages.yml. Does not affect currently vanished players."
            }),
            Map.entry("vanishhelp", new String[]{
                    "Shows this interactive help menu.",
                    "/vhelp [command]",
                    "Click any command in the list for detailed info."
            })
    );

    public VanishHelpCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        LanguageManager lm = plugin.getConfigManager().getLanguageManager();

        if (args.length > 0) {
            String sub = args[0].toLowerCase();
            // Map common aliases to internal names
            if (sub.equals("v") || sub.equals("sv")) sub = "vanish";
            if (sub.equals("vrules") || sub.equals("vsettings")) sub = "vanishrules";
            if (sub.equals("vchat")) sub = "vanishchat";
            if (sub.equals("vignore")) sub = "vanishignore";
            if (sub.equals("vlist")) sub = "vanishlist";
            if (sub.equals("vhelp")) sub = "vanishhelp";
            if (sub.equals("vconfig")) sub = "vanishconfig";
            if (sub.equals("vperms")) sub = "vperms";
            if (sub.equals("vreload")) sub = "vanishreload";

            if (showDetailedHelp(sender, sub))
                return true;

            // Unknown sub-command — acknowledge it before showing the list
            plugin.getMessageManager().sendMessage(sender,
                    lm.getMessage("help.unknown-command").replace("%command%", args[0]));
        }

        plugin.getMessageManager().sendMessage(sender, lm.getMessage("help.header"));
        plugin.getMessageManager().sendMessage(sender, lm.getMessage("help.subheader"));

        addHelpLine(sender, "vanish", "/vanish", lm.getMessage("help.cmd-vanish"));
        addHelpLine(sender, "vanishrules", "/vrules", lm.getMessage("help.cmd-vrules"));
        addHelpLine(sender, "vanishconfig", "/vconfig", lm.getMessage("help.cmd-vconfig"));
        addHelpLine(sender, "vanishlist", "/vlist", lm.getMessage("help.cmd-vlist"));
        addHelpLine(sender, "vperms", "/vperms", lm.getMessage("help.cmd-vperms"));
        addHelpLine(sender, "vanishignore", "/vignore", lm.getMessage("help.cmd-vignore"));
        addHelpLine(sender, "vanishchat", "/vchat", lm.getMessage("help.cmd-vchat"));
        addHelpLine(sender, "vanishreload", "/vreload", lm.getMessage("help.cmd-vreload"));

        plugin.getMessageManager().sendMessage(sender, lm.getMessage("help.footer"));
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
        String[] info = DETAILED_HELP.get(cmdName);
        if (info == null) return false;

        LanguageManager lm = plugin.getConfigManager().getLanguageManager();
        var cmd = plugin.getCommand(cmdName);
        String permission = cmd != null && cmd.getPermission() != null ? cmd.getPermission() : "None";
        String aliases = cmd != null && !cmd.getAliases().isEmpty() ? String.join(", ", cmd.getAliases()) : "";

        plugin.getMessageManager().sendMessage(sender, "\n<gold><bold>--- " + cmdName + " ---");
        plugin.getMessageManager().sendMessage(sender, "<gray>Description: <white>" + info[0]);
        plugin.getMessageManager().sendMessage(sender, "<gray>Usage: <yellow>" + info[1]);
        if (!aliases.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "<gray>Aliases: <white>" + aliases);
        }
        plugin.getMessageManager().sendMessage(sender, "<gray>Permission: <red>" + permission);
        plugin.getMessageManager().sendMessage(sender, "<gray>Note: <white>" + info[2]);

        String cmdShortName = cmd != null && !cmd.getAliases().isEmpty() ? cmd.getAliases().get(0) : cmdName;
        String buttons = "\n<click:run_command:/vhelp><hover:show_text:'<gray>Return to list'><gray><bold>[BACK]</bold></gray></hover></click>   "
                + "<click:suggest_command:/" + cmdShortName
                + "><hover:show_text:'<green>Fill in chat box'><green><bold>[USE COMMAND]</bold></green></hover></click>";

        plugin.getMessageManager().sendMessage(sender, buttons);
        plugin.getMessageManager().sendMessage(sender, lm.getMessage("help.footer"));
        return true;
    }

    private static final List<String> KNOWN_COMMANDS = List.of(
            "vanish", "vrules", "vconfig", "vlist", "vperms", "vignore", "vhelp", "vreload", "vchat");

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1)
            return StringUtil.copyPartialMatches(args[0], KNOWN_COMMANDS, new ArrayList<>());
        return new ArrayList<>();
    }
}
