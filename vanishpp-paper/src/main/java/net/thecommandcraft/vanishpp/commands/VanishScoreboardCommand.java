package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VanishScoreboardCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishScoreboardCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("vanishpp.scoreboard")) {
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        if (!plugin.getConfigManager().scoreboardEnabled) {
            plugin.getMessageManager().sendMessage(player,
                    "<gray>The vanish scoreboard is disabled in <white>config.yml</white>.");
            return true;
        }

        boolean visible = plugin.getVanishScoreboard().toggle(player);
        plugin.getMessageManager().sendMessage(player, visible
                ? "<gray>Vanish scoreboard <green>enabled</green>."
                : "<gray>Vanish scoreboard <red>disabled</red>.");
        return true;
    }
}
