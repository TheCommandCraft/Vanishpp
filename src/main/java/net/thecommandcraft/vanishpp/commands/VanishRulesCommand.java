package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.Vanishpp;
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
import java.util.List;

public class VanishRulesCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public VanishRulesCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (args.length == 0) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("rules.usage"));
            return true;
        }

        Player target;
        int argOffset = 0;

        String firstArg = args[0].toLowerCase();
        RuleManager rules = plugin.getRuleManager();
        boolean isRule = rules.getAvailableRules().contains(firstArg) || firstArg.equals("all")
                || firstArg.equals("none");

        if (isRule) {
            if (!(sender instanceof Player)) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("ignore-warning.console-specify"));
                return true;
            }
            target = (Player) sender;
        } else {
            target = Bukkit.getPlayer(firstArg);
            if (target == null) {
                // If it looks like a rule attempt (args[1] is true/false), give a better error
                if (args.length > 1 && (args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("false"))) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("rules.invalid-name"));
                } else {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("player-not-found"));
                }
                return true;
            }
            if (!sender.hasPermission("vanishpp.rules.others")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("no-permission"));
                return true;
            }
            argOffset = 1;
        }

        if (args.length <= argOffset) {
            plugin.getMessageManager().sendMessage(sender, plugin.getConfigManager().getLanguageManager()
                    .getMessage("rules.header").replace("%player%", target.getName()));
            for (String r : rules.getAvailableRules()) {
                boolean currentR = rules.getRule(target, r);
                String color = currentR ? "green" : "red";
                String statusKey = currentR ? "rules.status-on" : "rules.status-off";
                String status = plugin.getConfigManager().getLanguageManager().getMessage(statusKey);
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("rules.line")
                                .replace("%rule%", r).replace("%color%", color).replace("%status%", status));
            }
            return true;
        }

        String rule = args[argOffset].toLowerCase();

        // Handle ALL / NONE
        if (rule.equals("all") || rule.equals("none")) {
            boolean value = rule.equals("all");
            int nextIdx = argOffset + 1;
            if (args.length > nextIdx) {
                String valStr = args[nextIdx].toLowerCase();
                if (valStr.equals("true")) { value = true; nextIdx++; }
                else if (valStr.equals("false")) { value = false; nextIdx++; }
            }
            // Allow trailing player name: /vrules all true <player>
            if (args.length > nextIdx && sender.hasPermission("vanishpp.rules.others")) {
                Player namedTarget = Bukkit.getPlayer(args[nextIdx]);
                if (namedTarget != null) target = namedTarget;
            }
            rules.setAllRules(target, value);
            if (plugin.isVanished(target))
                plugin.applyVanishEffects(target);
            String statusKey = value ? "rules.status-on" : "rules.status-off";
            String status = plugin.getConfigManager().getLanguageManager().getMessage(statusKey);
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("rules.set")
                            .replace("%rule%", "ALL").replace("%player%", target.getName())
                            .replace("%status%", status));
            return true;
        }

        if (!rules.getAvailableRules().contains(rule)) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("rules.invalid-name"));
            return true;
        }

        // Get or Set
        if (args.length == argOffset + 1) {
            boolean currentVal = rules.getRule(target, rule);
            String statusKey = currentVal ? "rules.status-on" : "rules.status-off";
            String status = plugin.getConfigManager().getLanguageManager().getMessage(statusKey);
            plugin.getMessageManager().sendMessage(sender,
                    rule + " is: <" + (currentVal ? "green" : "red") + ">" + status);
        } else {
            String valStr = args[argOffset + 1].toLowerCase();
            if (!valStr.equals("true") && !valStr.equals("false")) {
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("rules.invalid-value"));
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
                    String statusKey = newValue ? "rules.status-on" : "rules.status-off";
                    String status = plugin.getConfigManager().getLanguageManager().getMessage(statusKey);
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("rules.set-temp")
                                    .replace("%rule%", rule).replace("%player%", target.getName())
                                    .replace("%status%", status).replace("%seconds%", String.valueOf(seconds)));
                } catch (NumberFormatException e) {
                    plugin.getMessageManager().sendMessage(sender,
                            plugin.getConfigManager().getLanguageManager().getMessage("rules.invalid-seconds"));
                }
            } else {
                String statusKey = newValue ? "rules.status-on" : "rules.status-off";
                String status = plugin.getConfigManager().getLanguageManager().getMessage(statusKey);
                plugin.getMessageManager().sendMessage(sender,
                        plugin.getConfigManager().getLanguageManager().getMessage("rules.set")
                                .replace("%rule%", rule).replace("%player%", target.getName())
                                .replace("%status%", status));
            }

            if (rule.equals(RuleManager.MOB_TARGETING) && plugin.isVanished(target)) {
                plugin.applyVanishEffects(target);
            }
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        RuleManager rules = plugin.getRuleManager();
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers())
                completions.add(p.getName());
            completions.addAll(rules.getAvailableRules());
            completions.add("all");
            completions.add("none");
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}