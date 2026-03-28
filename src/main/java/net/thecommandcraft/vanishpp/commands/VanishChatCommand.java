package net.thecommandcraft.vanishpp.commands;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;

public class VanishChatCommand implements CommandExecutor {
    private final Vanishpp plugin;

    public VanishChatCommand(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!player.hasPermission("vanishpp.chat")) {
            plugin.getMessageManager().sendMessage(player,
                    plugin.getConfigManager().getLanguageManager().getMessage("unknown-command"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
            if (plugin.pendingChatMessages.containsKey(player.getUniqueId())) {
                String msg = plugin.pendingChatMessages.remove(player.getUniqueId());

                // Mark as confirmed for local chat listener
                player.setMetadata("vanishpp_chat_bypass", new FixedMetadataValue(plugin, true));

                // Mark as confirmed for DiscordSRV hook
                if (plugin.getIntegrationManager().getDiscordSRV() != null) {
                    plugin.getIntegrationManager().getDiscordSRV().setConfirmed(player.getUniqueId());
                }

                // Force player to say the message
                player.chat(msg);

                plugin.getMessageManager().sendMessage(player, plugin.getConfigManager().chatSentMessage);
            } else {
                plugin.getMessageManager().sendMessage(player, plugin.getConfigManager().noChatPendingMessage);
            }
            return true;
        }

        plugin.getMessageManager().sendMessage(player,
                plugin.getConfigManager().getLanguageManager().getMessage("chat.usage"));
        return true;
    }
}