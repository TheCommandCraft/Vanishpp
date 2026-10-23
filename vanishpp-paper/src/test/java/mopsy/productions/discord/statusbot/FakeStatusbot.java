package mopsy.productions.discord.statusbot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.stream.Collectors;

/** Mirrors StatusbotMainSpigot: registers itself as listener and reads getOnlinePlayers() raw. */
public class FakeStatusbot extends JavaPlugin implements Listener, IStatusbotMain {

    @Override
    public void onEnable() {
        regDefaultEmbedVarProviders();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Calls.rawEvents.add("join " + e.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent e) {
        Calls.rawEvents.add("quit " + e.getPlayer().getName());
    }

    @Override
    public void regDefaultEmbedVarProviders() {
        EmbedManager.regVarSupplier("amount-of-players", m -> "raw " + Bukkit.getOnlinePlayers().size());
        EmbedManager.regVarSupplier("player-list", m -> "raw " + Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).collect(Collectors.joining(", ")));
    }
}
