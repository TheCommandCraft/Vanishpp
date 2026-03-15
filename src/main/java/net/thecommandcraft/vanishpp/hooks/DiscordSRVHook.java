package net.thecommandcraft.vanishpp.hooks;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.hooks.vanish.VanishHook;
import github.scarsz.discordsrv.util.DiscordUtil;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class DiscordSRVHook implements VanishHook {

    private final Vanishpp plugin;
    private final Set<UUID> confirmedPlayers = new HashSet<>();

    public DiscordSRVHook(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void register() {
        DiscordSRV.api.subscribe(this);
        // Register as a vanish hook so DiscordSRV suppresses join/quit announcements
        // for vanished players automatically via PlayerUtil.isVanished()
        DiscordSRV.getPlugin().getPluginHooks().add(this);
    }

    public void unregister() {
        DiscordSRV.api.unsubscribe(this);
        DiscordSRV.getPlugin().getPluginHooks().remove(this);
    }

    // VanishHook — tells DiscordSRV whether a player is vanished
    @Override
    public boolean isVanished(Player player) {
        return plugin.isVanished(player);
    }

    // PluginHook
    @Override
    public Plugin getPlugin() {
        return plugin;
    }

    /**
     * Mark a player's next message as confirmed so it bypasses our DiscordSRV
     * suppression.
     */
    public void setConfirmed(UUID uuid) {
        confirmedPlayers.add(uuid);
    }

    @Subscribe
    public void onDiscordChatPreProcess(GameChatMessagePreProcessEvent event) {
        Player player = event.getPlayer();
        if (player == null)
            return;

        // If the player is vanished and hasn't confirmed their message yet, suppress it
        // in Discord.
        if (plugin.isVanished(player)
                && !plugin.getRuleManager().getRule(player, net.thecommandcraft.vanishpp.config.RuleManager.CAN_CHAT)) {
            if (!confirmedPlayers.contains(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }

        // Always remove from confirmedPlayers after a check - it's a one-time bypass
        // for the confirmed message.
        confirmedPlayers.remove(player.getUniqueId());
    }

    public void sendFakeJoin(Player player) {
        String message = plugin.getIntegrationManager().getEssentialsJoinMessage();
        if (message == null)
            message = "joined the game";
        broadcastToDiscord(player, message);
    }

    public void sendFakeQuit(Player player) {
        String message = plugin.getIntegrationManager().getEssentialsQuitMessage();
        if (message == null)
            message = "left the game";
        broadcastToDiscord(player, message);
    }

    private void broadcastToDiscord(Player player, String message) {
        try {
            String name = player.getName();
            String formatted = "**" + name + "** " + message;

            TextChannel channel = DiscordSRV.getPlugin().getMainTextChannel();
            if (channel != null) {
                DiscordUtil.sendMessage(channel, formatted);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send DiscordSRV message: " + e.getMessage());
        }
    }
}
