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
        if (!sender.hasPermission("vanishpp.rules")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }
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
                        plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
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
            if (plugin.isVanished(target)) {
                try {
                    plugin.applyVanishEffects(target);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error re-applying vanish effects: " + e.getMessage());
                }
            }
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
            String color = currentVal ? "green" : "red";
            plugin.getMessageManager().sendMessage(sender,
                    rule + " is: <" + color + ">" + status);
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
                try {
                    plugin.applyVanishEffects(target);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error re-applying vanish effects: " + e.getMessage());
                }
            }
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        RuleManager rules = plugin.getRuleManager();
        List<String> booleans = List.of("true", "false");
        List<String> durations = List.of("30", "60", "120", "300", "600");

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers())
                completions.add(p.getName());
            completions.addAll(rules.getAvailableRules());
            completions.add("all");
            completions.add("none");
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }

        String arg0 = args[0].toLowerCase();
        boolean arg0IsPlayer = Bukkit.getPlayer(arg0) != null;
        boolean arg0IsRule = rules.getAvailableRules().contains(arg0);
        boolean arg0IsAllNone = arg0.equals("all") || arg0.equals("none");

        if (args.length == 2) {
            if (arg0IsPlayer) {
                List<String> completions = new ArrayList<>(rules.getAvailableRules());
                completions.add("all");
                completions.add("none");
                return StringUtil.copyPartialMatches(args[1], completions, new ArrayList<>());
            } else if (arg0IsRule || arg0IsAllNone) {
                return StringUtil.copyPartialMatches(args[1], new ArrayList<>(booleans), new ArrayList<>());
            }
        }

        if (args.length == 3) {
            String arg1 = args[1].toLowerCase();
            boolean arg1IsRuleOrAllNone = rules.getAvailableRules().contains(arg1)
                    || arg1.equals("all") || arg1.equals("none");
            boolean arg1IsBool = arg1.equals("true") || arg1.equals("false");

            if (arg0IsPlayer && arg1IsRuleOrAllNone) {
                return StringUtil.copyPartialMatches(args[2], new ArrayList<>(booleans), new ArrayList<>());
            } else if ((arg0IsRule || arg0IsAllNone) && arg1IsBool) {
                List<String> completions = new ArrayList<>(durations);
                if (arg0IsAllNone && sender.hasPermission("vanishpp.rules.others")) {
                    for (Player p : Bukkit.getOnlinePlayers())
                        completions.add(p.getName());
                }
                return StringUtil.copyPartialMatches(args[2], completions, new ArrayList<>());
            }
        }

        if (args.length == 4) {
            String arg1 = args[1].toLowerCase();
            String arg2 = args[2].toLowerCase();
            boolean arg1IsRuleOrAllNone = rules.getAvailableRules().contains(arg1)
                    || arg1.equals("all") || arg1.equals("none");
            boolean arg2IsBool = arg2.equals("true") || arg2.equals("false");
            if (arg0IsPlayer && arg1IsRuleOrAllNone && arg2IsBool) {
                return StringUtil.copyPartialMatches(args[3], new ArrayList<>(durations), new ArrayList<>());
            }
        }

        return new ArrayList<>();
    }
}