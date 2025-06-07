package net.thecommandcraft.vanishpp;

// Imports remain the same
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VanishCommand implements CommandExecutor {

    private final Vanishpp plugin;

    public VanishCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("vanishpp.vanish")) {
            // Use the message from the config
            player.sendMessage(plugin.getConfigManager().noPermissionMessage);
            return true;
        }

        if (plugin.isVanished(player)) {
            plugin.unvanish(player);
        } else {
            plugin.vanish(player);
        }

        return true;
    }
}