package net.thecommandcraft.vanishpp.scoreboard;

import net.kyori.adventure.bossbar.BossBar;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player vanish-status bossbar.
 * Config keys: {@code bossbar.enabled}, {@code bossbar.title}, {@code bossbar.color}, {@code bossbar.style}.
 */
public class VanishBossbar {

    private final Vanishpp plugin;
    /** UUID → the bossbar instance shown to that player. */
    private final Map<UUID, BossBar> bossbars = new ConcurrentHashMap<>();

    public VanishBossbar(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /** Show the vanish bossbar to this player (called on vanish). */
    public void show(Player player) {
        if (!plugin.getConfigManager().bossbarEnabled) return;
        hide(player); // remove any stale bar first
        BossBar bar = createBar();
        bossbars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
    }

    /** Remove the vanish bossbar from this player (called on unvanish). */
    public void hide(Player player) {
        BossBar bar = bossbars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    /** Toggle: if showing, hide; if hidden, show. Returns new visible state. */
    public boolean toggle(Player player) {
        if (bossbars.containsKey(player.getUniqueId())) {
            hide(player);
            return false;
        } else {
            show(player);
            return true;
        }
    }

    public boolean isVisible(Player player) {
        return bossbars.containsKey(player.getUniqueId());
    }

    /** Clean up on player quit. */
    public void cleanup(UUID uuid) {
        bossbars.remove(uuid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BossBar createBar() {
        String rawTitle = plugin.getConfigManager().bossbarTitle;
        BossBar.Color color  = parseColor(plugin.getConfigManager().bossbarColor);
        BossBar.Overlay overlay = parseOverlay(plugin.getConfigManager().bossbarStyle);
        return BossBar.bossBar(
                plugin.getMessageManager().parse(rawTitle, null),
                1.0f, color, overlay);
    }

    private static BossBar.Color parseColor(String name) {
        try { return BossBar.Color.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return BossBar.Color.BLUE; }
    }

    private static BossBar.Overlay parseOverlay(String name) {
        try { return BossBar.Overlay.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return BossBar.Overlay.PROGRESS; }
    }
}
