package net.thecommandcraft.vanishpp.hooks;

import net.ess3.api.events.PrivateMessagePreSendEvent;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Essentials resolves nicknames, wildcards and its actual reply recipient before this event. */
public final class EssentialsMessageListener implements Listener {
    private final Vanishpp plugin;

    public EssentialsMessageListener(Vanishpp plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMessage(PrivateMessagePreSendEvent event) {
        var senderId = event.getSender().getUUID();
        var recipientId = event.getRecipient().getUUID();
        if (senderId == null || recipientId == null) return;
        var sender = Bukkit.getPlayer(senderId);
        if (sender != null && !plugin.getPermissionManager().canSee(sender, recipientId)) {
            event.setCancelled(true);
            plugin.getMessageManager().sendMessage(sender,
                    plugin.getLanguageManager().getMessage("commands.msg-player-not-found"));
        }
    }
}
