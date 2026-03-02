package net.thecommandcraft.vanishpp.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MessageManager {

    private final MiniMessage miniMessage;
    private final boolean hasPlaceholderAPI;

    public MessageManager(Vanishpp plugin) {
        this.miniMessage = MiniMessage.miniMessage();
        this.hasPlaceholderAPI = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * Parses a message string into a Component, handling Placeholders and
     * MiniMessage tags.
     * 
     * @param message The message string to parse.
     * @param player  The player for context-aware placeholders (can be null).
     * @return The parsed Component.
     */
    public Component parse(String message, Player player) {
        if (message == null || message.isEmpty())
            return Component.empty();

        // 1. PlaceholderAPI Expansion
        if (hasPlaceholderAPI && player != null) {
            message = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, message);
        }

        // 2. Legacy Color Support Transition
        // We convert legacy & colors to MiniMessage tags so users can slowly migrate.
        // This is a simple conversion.
        if (message.contains("&") || message.contains("§")) {
            message = legacyToMiniMessage(message);
        }

        return miniMessage.deserialize(message);
    }

    /**
     * Sends a formatted message to a CommandSender.
     */
    public void sendMessage(CommandSender sender, String message) {
        if (message == null || message.isEmpty())
            return;
        Player player = (sender instanceof Player) ? (Player) sender : null;
        sender.sendMessage(parse(message, player));
    }

    /**
     * Sends a formatted action bar message to a player.
     */
    public void sendActionBar(Player player, String message) {
        if (message == null || message.isEmpty())
            return;
        player.sendActionBar(parse(message, player));
    }

    private String legacyToMiniMessage(String message) {
        // Use Adventure's legacy serializer to convert to a component, then back to
        // MiniMessage
        // This is often cleaner than regex.
        Component legacyComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(message.replace("§", "&"));
        return miniMessage.serialize(legacyComponent);
    }
}
