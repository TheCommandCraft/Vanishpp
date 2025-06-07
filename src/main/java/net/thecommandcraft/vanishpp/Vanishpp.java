package net.thecommandcraft.vanishpp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class Vanishpp extends JavaPlugin {

    private final Set<UUID> vanishedPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        // Register the /vanish command and pass an instance of this class
        this.getCommand("vanish").setExecutor(new VanishCommand(this));
    }

    @Override
    public void onDisable() {
        // Good practice to make sure everyone is visible on shutdown
        for (UUID uuid : vanishedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    onlinePlayer.showPlayer(this, player);
                }
            }
        }
    }

    public boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public void vanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());

        // Hide the vanished player from all other players without the bypass permission
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.hasPermission("vanishpp.see") && !onlinePlayer.equals(player)) {
                onlinePlayer.hidePlayer(this, player);
            }
        }
        player.sendMessage(ChatColor.GOLD + "You are now vanished.");
    }

    public void unvanish(Player player) {
        vanishedPlayers.remove(player.getUniqueId());

        // Show the player to everyone
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }
        player.sendMessage(ChatColor.GOLD + "You are no longer vanished.");
    }
}