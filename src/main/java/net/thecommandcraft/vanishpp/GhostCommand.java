package net.thecommandcraft.vanishpp;

import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public class GhostCommand implements CommandExecutor {

    private final Vanishpp plugin;
    private final ConfigManager configManager;
    private static final EnumSet<GameMode> VALID_GAMEMODES = EnumSet.of(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE);

    public GhostCommand(Vanishpp plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("vanishpp.ghost")) {
            player.sendMessage(configManager.noPermissionMessage);
            return true;
        }

        if (plugin.isGhosted(player)) {
            plugin.exitGhostMode(player);
        } else {
            if (plugin.isVanished(player)) {
                player.sendMessage(configManager.ghostFailVanishMessage);
                return true;
            }
            if (!VALID_GAMEMODES.contains(player.getGameMode())) {
                player.sendMessage(configManager.ghostFailGamemodeMessage);
                return true;
            }
            plugin.enterGhostMode(player);
        }
        return true;
    }
}