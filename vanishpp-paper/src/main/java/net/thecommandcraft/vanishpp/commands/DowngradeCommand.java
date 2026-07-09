package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.storage.SqlStorage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public class DowngradeCommand implements CommandExecutor, TabCompleter {

    private final Vanishpp plugin;

    public DowngradeCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.admin") && !sender.isOp()) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (!plugin.downgradeDetected) {
            sender.sendMessage(Component.text("No downgrade situation is currently active.", NamedTextColor.GREEN));
            return true;
        }

        if (!(plugin.getStorageProvider() instanceof SqlStorage sql)) {
            sender.sendMessage(Component.text("This command requires SQL storage.", NamedTextColor.RED));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "info";

        switch (sub) {
            case "info" -> sendInfo(sender, sql);
            case "allow" -> handleAllow(sender, sql);
            case "reset" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
                    handleReset(sender, sql);
                } else {
                    sendResetConfirmPrompt(sender, sql);
                }
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendInfo(CommandSender sender, SqlStorage sql) {
        Map<String, Long> summary = sql.getDataSummary();
        sender.sendMessage(Component.text("┌─────────────────────────────────────────────┐", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("│  VANISH++ DOWNGRADE — DATA AT STAKE          │", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("│  DB version: ", NamedTextColor.GOLD)
                .append(Component.text(plugin.downgradeFromVersion, NamedTextColor.RED))
                .append(Component.text("  →  Running: ", NamedTextColor.GOLD))
                .append(Component.text(plugin.getDescription().getVersion(), NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("│", NamedTextColor.GOLD));
        for (Map.Entry<String, Long> entry : summary.entrySet()) {
            long count = entry.getValue();
            NamedTextColor color = count > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
            sender.sendMessage(Component.text("│  " + entry.getKey() + ": ", NamedTextColor.WHITE)
                    .append(Component.text(count >= 0 ? String.valueOf(count) : "error", color)));
        }
        sender.sendMessage(Component.text("│", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("│  Resetting will delete ALL of the above.     │", NamedTextColor.RED));
        sender.sendMessage(Component.text("│  All DB writes are currently SUSPENDED.      │", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("└─────────────────────────────────────────────┘", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("")
                .append(Component.text("[ Allow writes (risky) ]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/vdowngrade allow"))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Lets the older plugin write to the DB.\nData may be corrupted.", NamedTextColor.RED))))
                .append(Component.text("  "))
                .append(Component.text("[ Reset DB (fresh start) ]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/vdowngrade reset"))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Wipes all Vanish++ data from the DB\nand starts fresh.", NamedTextColor.GOLD)))));
    }

    private void handleAllow(CommandSender sender, SqlStorage sql) {
        sql.writeSuspended = false;
        plugin.clearDowngradeState();
        plugin.getLogger().severe("DOWNGRADE OVERRIDE: " + sender.getName()
                + " allowed writes from " + plugin.getDescription().getVersion()
                + " to DB previously written by " + plugin.downgradeFromVersion + ". Data may corrupt.");
        sender.sendMessage(Component.text(
                "DB writes resumed. The plugin is now running on a DB from a newer version. Proceed with caution.",
                NamedTextColor.YELLOW));
    }

    private void sendResetConfirmPrompt(CommandSender sender, SqlStorage sql) {
        sendInfo(sender, sql);
        sender.sendMessage(Component.text("")
                .append(Component.text("⚠ This will permanently delete all data shown above.", NamedTextColor.RED, TextDecoration.BOLD)));
        sender.sendMessage(Component.text("Click to confirm: ", NamedTextColor.WHITE)
                .append(Component.text("[ WIPE DATABASE AND START FRESH ]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/vdowngrade reset confirm"))
                        .hoverEvent(HoverEvent.showText(Component.text("This cannot be undone!", NamedTextColor.RED)))));
    }

    private void handleReset(CommandSender sender, SqlStorage sql) {
        sender.sendMessage(Component.text("Wiping all Vanish++ data from the database...", NamedTextColor.YELLOW));
        sql.resetDatabase();
        plugin.clearDowngradeState();
        plugin.getLogger().warning("DOWNGRADE RESET: " + sender.getName()
                + " wiped the Vanish++ database and started fresh on version "
                + plugin.getDescription().getVersion() + ".");
        sender.sendMessage(Component.text(
                "Database reset complete. All writes are now active on " + plugin.getDescription().getVersion() + ".",
                NamedTextColor.GREEN));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /vdowngrade <info|allow|reset [confirm]>", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return List.of("info", "allow", "reset");
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) return List.of("confirm");
        return List.of();
    }
}
