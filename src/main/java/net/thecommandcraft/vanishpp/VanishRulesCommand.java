package net.thecommandcraft.vanishpp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VanishRulesCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishRulesCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /vrules [player] <rule|all|none> [true|false]", NamedTextColor.RED));
            return true;
        }

        Player target;
        int argOffset = 0;

        // Logic to determine if first arg is a player or a rule
        String firstArg = args[0].toLowerCase();
        RuleManager rules = plugin.getRuleManager();
        boolean isRule = rules.getAvailableRules().contains(firstArg) || firstArg.equals("all") || firstArg.equals("none");

        if (isRule) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Console must specify a player: /vrules <player> <rule> [val]", NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;
        } else {
            // First arg is NOT a recognized rule, try as player
            target = Bukkit.getPlayer(firstArg);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found or invalid rule.", NamedTextColor.RED));
                return true;
            }
            if (!sender.hasPermission("vanishpp.rules.others")) {
                sender.sendMessage(Component.text("You do not have permission to modify rules for others.", NamedTextColor.RED));
                return true;
            }
            argOffset = 1; // Shift args
        }

        if (args.length <= argOffset) {
            // List rules for target
            sender.sendMessage(Component.text("Rules for " + target.getName() + ":", NamedTextColor.GOLD));
            for (String r : rules.getAvailableRules()) {
                boolean current = rules.getRule(target, r);
                NamedTextColor color = current ? NamedTextColor.GREEN : NamedTextColor.RED;
                sender.sendMessage(Component.text(" - " + r + ": " + current, color));
            }
            return true;
        }

        String rule = args[argOffset].toLowerCase();

        // Handle ALL / NONE
        if (rule.equals("all") || rule.equals("none")) {
            boolean value = rule.equals("all");
            // Check for explicit value override (e.g. /vrules all false)
            if (args.length > argOffset + 1) {
                String valStr = args[argOffset + 1].toLowerCase();
                if (valStr.equals("true")) value = true;
                else if (valStr.equals("false")) value = false;
            }

            rules.setAllRules(target, value);
            if (plugin.isVanished(target)) plugin.applyVanishEffects(target);

            sender.sendMessage(Component.text("Set ALL rules for " + target.getName() + " to: " + value, value ? NamedTextColor.GREEN : NamedTextColor.RED));
            return true;
        }

        if (!rules.getAvailableRules().contains(rule)) {
            sender.sendMessage(Component.text("Invalid rule name.", NamedTextColor.RED));
            return true;
        }

        // Get or Set
        if (args.length == argOffset + 1) {
            boolean current = rules.getRule(target, rule);
            NamedTextColor color = current ? NamedTextColor.GREEN : NamedTextColor.RED;
            sender.sendMessage(Component.text(rule + " for " + target.getName() + " is: " + current, color));
        } else {
            String valStr = args[argOffset + 1].toLowerCase();
            if (!valStr.equals("true") && !valStr.equals("false")) {
                sender.sendMessage(Component.text("Value must be 'true' or 'false'.", NamedTextColor.RED));
                return true;
            }
            boolean newValue = Boolean.parseBoolean(valStr);
            rules.setRule(target, rule, newValue);

            if (rule.equals(RuleManager.MOB_TARGETING) && plugin.isVanished(target)) {
                plugin.applyVanishEffects(target);
            }

            NamedTextColor color = newValue ? NamedTextColor.GREEN : NamedTextColor.RED;
            sender.sendMessage(Component.text("Set " + rule + " for " + target.getName() + " to " + newValue, color));
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        RuleManager rules = plugin.getRuleManager();

        // Complex tab completion logic based on offset
        // Simplified:
        // Arg 1: Players OR Rules
        // Arg 2: Rules (if Arg 1 was Player) OR Boolean (if Arg 1 was Rule)

        if (args.length == 1) {
            // Suggest Players
            for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            // Suggest Rules
            completions.addAll(rules.getAvailableRules());
            completions.add("all");
            completions.add("none");
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }

        String first = args[0].toLowerCase();
        boolean firstIsRule = rules.getAvailableRules().contains(first) || first.equals("all") || first.equals("none");

        if (args.length == 2) {
            if (firstIsRule) {
                // Arg 1 was rule, so Arg 2 is value
                return StringUtil.copyPartialMatches(args[1], Arrays.asList("true", "false"), new ArrayList<>());
            } else {
                // Arg 1 was probably player, so Arg 2 is rule
                List<String> r = new ArrayList<>(rules.getAvailableRules());
                r.add("all"); r.add("none");
                return StringUtil.copyPartialMatches(args[1], r, new ArrayList<>());
            }
        }

        if (args.length == 3 && !firstIsRule) {
            // Player -> Rule -> Value
            return StringUtil.copyPartialMatches(args[2], Arrays.asList("true", "false"), new ArrayList<>());
        }

        return new ArrayList<>();
    }
}