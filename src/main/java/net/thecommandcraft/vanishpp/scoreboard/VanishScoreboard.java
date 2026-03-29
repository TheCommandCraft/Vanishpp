package net.thecommandcraft.vanishpp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VanishScoreboard {

    // 16 unique dummy entries — one per possible line slot.
    // The actual text is stored in the team prefix, so updating a line
    // only sends a UpdateTeams packet (no flicker).
    private static final String[] ENTRIES = {
        "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7",
        "§8", "§9", "§a", "§b", "§c", "§d", "§e", "§f"
    };

    private final Vanishpp plugin;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Set<UUID> manuallyHidden = ConcurrentHashMap.newKeySet();

    public VanishScoreboard(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void onVanish(Player player) {
        if (!plugin.getConfigManager().scoreboardEnabled) return;
        if (!plugin.getConfigManager().scoreboardAutoShow) return;
        if (!player.hasPermission("vanishpp.scoreboard")) return;
        if (player.hasPermission("vanishpp.scoreboard.bypass")) return;
        if (manuallyHidden.contains(player.getUniqueId())) return;
        show(player);
    }

    public void onUnvanish(Player player) {
        manuallyHidden.remove(player.getUniqueId());
        hide(player);
    }

    public boolean toggle(Player player) {
        if (boards.containsKey(player.getUniqueId())) {
            manuallyHidden.add(player.getUniqueId());
            hide(player);
            return false;
        } else {
            manuallyHidden.remove(player.getUniqueId());
            show(player);
            return true;
        }
    }

    public boolean isVisible(Player player) {
        return boards.containsKey(player.getUniqueId());
    }

    public void show(Player player) {
        if (boards.containsKey(player.getUniqueId())) return;

        ScoreboardManager sm = Bukkit.getScoreboardManager();
        Scoreboard sb = sm.getNewScoreboard();

        String title = plugin.getScoreboardConfig().getString("title", "&8[ &7Vanish&f++ &8]");
        Objective obj = sb.registerNewObjective("vanishpp", Criteria.DUMMY, parse(title));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (String entry : ENTRIES) {
            Team team = sb.registerNewTeam("vpp_" + entry.charAt(1));
            team.addEntry(entry);
        }

        boards.put(player.getUniqueId(), sb);
        update(player, sb, obj);
        player.setScoreboard(sb);
    }

    public void hide(Player player) {
        if (!boards.containsKey(player.getUniqueId())) return;
        boards.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    public void cleanup(UUID uuid) {
        boards.remove(uuid);
        manuallyHidden.remove(uuid);
    }

    public void startUpdateTask() {
        int interval = plugin.getScoreboardConfig().getInt("update-interval", 20);
        plugin.getVanishScheduler().runTimerGlobal(() -> {
            for (UUID uuid : new ArrayList<>(boards.keySet())) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    Scoreboard sb = boards.get(uuid);
                    if (sb == null) continue;
                    Objective obj = sb.getObjective("vanishpp");
                    if (obj != null) update(p, sb, obj);
                }
            }
        }, interval, interval);
    }

    public void shutdown() {
        for (UUID uuid : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
        manuallyHidden.clear();
    }

    private void update(Player player, Scoreboard sb, Objective obj) {
        String title = plugin.getScoreboardConfig().getString("title", "&8[ &7Vanish&f++ &8]");
        obj.displayName(parse(title));

        List<String> lines = plugin.getScoreboardConfig().getStringList("lines");
        int maxLines = Math.min(lines.size(), ENTRIES.length);

        for (int i = 0; i < ENTRIES.length; i++) {
            String entry = ENTRIES[i];
            Team team = sb.getTeam("vpp_" + entry.charAt(1));
            if (team == null) continue;

            if (i < maxLines) {
                team.prefix(parse(applyPlaceholders(player, lines.get(i))));
                team.suffix(Component.empty());
                obj.getScore(entry).setScore(maxLines - i);
            } else {
                sb.resetScores(entry);
            }
        }
    }

    private Component parse(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private String applyPlaceholders(Player player, String text) {
        double tps = Math.min(20.0, Bukkit.getServer().getTPS()[0]);
        String tpsColor = tps >= 18 ? "&a" : tps >= 15 ? "&e" : "&c";

        text = text
                .replace("%world%", player.getWorld().getName())
                .replace("%tps%", tpsColor + String.format("%.1f", tps))
                .replace("%tps_raw%", String.format("%.1f", tps))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max_players%", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("%player%", player.getName())
                .replace("%level%", String.valueOf(plugin.getStorageProvider().getVanishLevel(player.getUniqueId())))
                .replace("%x%", String.valueOf(player.getLocation().getBlockX()))
                .replace("%y%", String.valueOf(player.getLocation().getBlockY()))
                .replace("%z%", String.valueOf(player.getLocation().getBlockZ()))
                .replace("%time%", new SimpleDateFormat("HH:mm").format(new Date()));

        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            }
        } catch (Throwable ignored) {}

        return text;
    }
}
