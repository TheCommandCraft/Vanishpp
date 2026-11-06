package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.common.visibility.AudiencePolicy;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Audio callbacks only read immutable facts published from player-owned tasks. */
public final class VoiceAudience {
    public record Facts(boolean permission, int seeLevel, int vanishLevel) {}
    private record Settings(boolean enabled, boolean layered) {}
    private final Vanishpp plugin;
    private final Map<UUID, Facts> players = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> departing = ConcurrentHashMap.newKeySet();
    private volatile Settings settings = new Settings(false, true);
    private volatile long generation;

    public VoiceAudience(Vanishpp plugin) { this.plugin = plugin; }

    public void refresh() {
        var config = plugin.getConfigManager();
        settings = new Settings(config.voiceChatIsolate, config.layeredPermsEnabled);
        java.util.Set<UUID> online = new java.util.HashSet<>();
        plugin.getServer().getOnlinePlayers().forEach(player -> online.add(player.getUniqueId()));
        departing.removeIf(uuid -> !online.contains(uuid));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (departing.contains(player.getUniqueId())) continue;
            long ticket = generation;
            plugin.getVanishScheduler().runEntityNow(player, () -> {
                if (!player.isOnline()) return;
                Facts facts = plugin.getPermissionManager().voiceFacts(player);
                synchronized (this) {
                    if (ticket == generation) players.put(player.getUniqueId(), facts);
                }
            });
        }
    }

    public synchronized void invalidate(UUID player) { generation++; players.remove(player); }
    public synchronized void invalidateAll() { generation++; players.clear(); }
    public synchronized void retire(UUID player) { departing.add(player); invalidate(player); }
    public synchronized void joined(UUID player) { departing.remove(player); invalidate(player); }

    public synchronized void close() {
        generation++;
        settings = new Settings(false, true);
        players.clear();
        departing.clear();
    }

    public boolean canHear(UUID receiver, UUID sender) {
        Settings current = settings;
        return !current.enabled() || (canSee(receiver, sender, current.layered())
                && (receiver == null || canSee(sender, receiver, current.layered())));
    }

    private boolean canSee(UUID viewer, UUID subject, boolean layered) {
        if (!plugin.isVanished(subject)) return true;
        Facts observer = viewer == null ? null : players.get(viewer);
        Facts target = players.get(subject);
        return AudiencePolicy.canSee(viewer, subject, true,
                observer != null && target != null && observer.permission(), layered,
                observer == null ? 0 : observer.seeLevel(), target == null ? Integer.MAX_VALUE : target.vanishLevel());
    }
}
