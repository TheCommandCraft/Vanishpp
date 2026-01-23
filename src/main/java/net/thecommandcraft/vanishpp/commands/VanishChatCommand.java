package net.thecommandcraft.vanishpp.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;

public class VanishChatCommand implements CommandExecutor {
    private final Vanishpp plugin;
    public VanishChatCommand(Vanishpp plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
            if (plugin.pendingChatMessages.containsKey(player.getUniqueId())) {
                String msg = plugin.pendingChatMessages.remove(player.getUniqueId());

                // Add Metadata flag to bypass the PlayerListener check for exactly one tick/event
                player.setMetadata("vanishpp_chat_bypass", new FixedMetadataValue(plugin, true));

                // Force player to say the message
                player.chat(msg);

                player.sendMessage(Component.text(plugin.getConfigManager().chatSentMessage, NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text(plugin.getConfigManager().noChatPendingMessage, NamedTextColor.RED));
            }
            return true;
        }

        return false;
    }
}