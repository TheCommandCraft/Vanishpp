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
            sender.sendMessage(Component.text("Usage: /vrules [player] <rule> [true|false] [seconds]", NamedTextColor.RED));
            return true;
        }

        Player target;
        int argOffset = 0;

        String firstArg = args[0].toLowerCase();
        RuleManager rules = plugin.getRuleManager();
        boolean isRule = rules.getAvailableRules().contains(firstArg) || firstArg.equals("all") || firstArg.equals("none");

        if (isRule) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;
        } else {
            target = Bukkit.getPlayer(firstArg);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found or invalid rule.", NamedTextColor.RED));
                return true;
            }
            if (!sender.hasPermission("vanishpp.rules.others")) {
                sender.sendMessage(Component.text("Permission denied.", NamedTextColor.RED));
                return true;
            }
            argOffset = 1;
        }

        if (args.length <= argOffset) {
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
            sender.sendMessage(Component.text(rule + " is: " + current, color));
        } else {
            String valStr = args[argOffset + 1].toLowerCase();
            if (!valStr.equals("true") && !valStr.equals("false")) {
                sender.sendMessage(Component.text("Value must be 'true' or 'false'.", NamedTextColor.RED));
                return true;
            }
            boolean newValue = Boolean.parseBoolean(valStr);
            boolean oldValue = rules.getRule(target, rule);

            rules.setRule(target, rule, newValue);

            // Temporary Logic
            if (args.length > argOffset + 2) {
                try {
                    int seconds = Integer.parseInt(args[argOffset + 2]);
                    plugin.scheduleRuleRevert(target, rule, oldValue, seconds);
                    sender.sendMessage(Component.text("Set " + rule + " to " + newValue + " for " + seconds + "s.", NamedTextColor.YELLOW));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid seconds.", NamedTextColor.RED));
                }
            } else {
                NamedTextColor color = newValue ? NamedTextColor.GREEN : NamedTextColor.RED;
                sender.sendMessage(Component.text("Set " + rule + " to " + newValue, color));
            }

            if (rule.equals(RuleManager.MOB_TARGETING) && plugin.isVanished(target)) {
                plugin.applyVanishEffects(target);
            }
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        RuleManager rules = plugin.getRuleManager();
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            completions.addAll(rules.getAvailableRules());
            completions.add("all"); completions.add("none");
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}