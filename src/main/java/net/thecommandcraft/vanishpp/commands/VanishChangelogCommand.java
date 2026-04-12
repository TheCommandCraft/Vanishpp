package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /vchangelog — shows the latest in-game changelog.
 * Requires vanishpp.changelog permission.
 * Reads lines from the language file key "changelog.entries" (list).
 */
public class VanishChangelogCommand implements CommandExecutor {

    private static final List<String> HARDCODED_CHANGELOG = List.of(
            "&6&lVanish++ Changelog &7(1.1.7)",
            "",
            "&e● &aNew: &f/vspec &7— Quick spectate any player while vanished",
            "&e● &aNew: &f/vfollow &7— Lock spectator camera onto target with HUD overlay",
            "&e● &aNew: &f/vhistory &7— Audit log of vanish/unvanish events",
            "&e● &aNew: &f/vautovanish &7— Toggle auto-vanish on join per player",
            "&e● &aNew: &f/vstats &7— View vanish statistics (total time, sessions)",
            "&e● &aNew: &f/vadmin &7— Admin dashboard GUI showing all vanished players",
            "&e● &aNew: &f/vwand &7— Give yourself the vanish toggle wand",
            "&e● &aNew: &f/vincognito &7— Hide your identity with a random fake name",
            "&e● &aNew: &fVanish Reason &7— Attach a reason to every vanish session",
            "&e● &aNew: &fTimed Vanish &7— Auto-unvanish after a configurable duration",
            "&e● &aNew: &fAnti-Combat Vanish &7— Block vanish during PvP/PvE cooldown",
            "&e● &aNew: &fBossbar &7— Show vanish status in a configurable bossbar",
            "&e● &aNew: &fRule Presets &7— Save and load sets of vanish rules",
            "&e● &aNew: &fInteractive Rules GUI &7— Edit rules in-game via inventory",
            "&e● &aNew: &fLuckPerms Context &7— vanishpp:vanished context for LP rules",
            "&e● &aNew: &fWorldGuard Flags &7— deny-vanish / force-vanish region flags",
            "&e● &aNew: &fWebhook Notifications &7— POST vanish events to any URL",
            "&e● &aNew: &fAFK Auto-Vanish &7— Auto-vanish after configurable idle time",
            "&e● &aNew: &fPer-World Rules &7— Override default rules per world",
            "&e● &aNew: &fPartial Visibility &7— Show vanished player to specific staff only",
            "",
            "&7Use &b/vhelp &7for command details."
    );

    private final Vanishpp plugin;

    public VanishChangelogCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vanishpp.changelog")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        // Try language file first; fall back to hardcoded list
        List<String> entries = plugin.getConfigManager().getLanguageManager()
                .getStringList("changelog.entries");
        if (entries == null || entries.isEmpty()) {
            entries = HARDCODED_CHANGELOG;
        }

        for (String line : entries) {
            if (line.isBlank()) {
                sender.sendMessage(Component.empty());
            } else {
                plugin.getMessageManager().sendMessage(sender, line);
            }
        }
        return true;
    }
}
