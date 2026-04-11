package net.thecommandcraft.vanishpp.scoreboard;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Holds the ProtocolLib PacketAdapter for scoreboard movement-triggered updates.
 * Kept in a separate class so VanishScoreboard can load without ProtocolLib on the classpath.
 */
class ScoreboardMovementListener {

    private final PacketAdapter adapter;

    ScoreboardMovementListener(Vanishpp plugin,
                               Map<UUID, Scoreboard> boards,
                               Map<UUID, Long> cooldowns,
                               long cooldownMs,
                               Consumer<Player> tickCallback) {
        this.adapter = new PacketAdapter(plugin,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (!boards.containsKey(uuid)) return;

                long now = System.currentTimeMillis();
                Long last = cooldowns.get(uuid);
                if (last != null && now - last < cooldownMs) return;
                cooldowns.put(uuid, now);

                Player player = event.getPlayer();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) tickCallback.accept(player);
                });
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(this.adapter);
    }

    void unregister() {
        try {
            ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
        } catch (Exception ignored) {}
    }
}
