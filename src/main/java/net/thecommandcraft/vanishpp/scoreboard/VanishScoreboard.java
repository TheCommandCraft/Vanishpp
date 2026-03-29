package net.thecommandcraft.vanishpp.scoreboard;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class VanishScoreboard {

    // 16 unique dummy entries — one per line slot.
    // Actual text lives in team prefix so updating a line only sends UpdateTeams packet (no flicker).
    private static final String[] ENTRIES = {
        "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7",
        "§8", "§9", "§a", "§b", "§c", "§d", "§e", "§f"
    };

    private final Vanishpp plugin;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Set<UUID> manuallyHidden = ConcurrentHashMap.newKeySet();

    // Movement-triggered update state
    private final Map<UUID, Long> movementCooldowns = new ConcurrentHashMap<>();
    private PacketAdapter movementListener;
    private BukkitTask updateTask;

    public VanishScoreboard(Vanishpp plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

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
        movementCooldowns.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    public void cleanup(UUID uuid) {
        boards.remove(uuid);
        manuallyHidden.remove(uuid);
        movementCooldowns.remove(uuid);
    }

    // -------------------------------------------------------------------------
    // Task management (called from Vanishpp.reloadPluginConfig / onEnable)
    // -------------------------------------------------------------------------

    /** Start/restart both the timer task and the movement listener. */
    public void reload() {
        startTimerTask();
        registerMovementListener();
    }

    private void startTimerTask() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        int interval = plugin.getScoreboardConfig().getInt("update-interval", 40);
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : new ArrayList<>(boards.keySet())) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) tick(p);
            }
        }, interval, interval);
    }

    private void registerMovementListener() {
        // Remove stale listener before re-adding
        if (movementListener != null) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListener(movementListener);
            } catch (Exception ignored) {}
            movementListener = null;
        }
        if (!plugin.hasProtocolLib()) return;
        if (!plugin.getScoreboardConfig().getBoolean("movement-update", true)) return;

        long cooldownMs = plugin.getScoreboardConfig().getLong("movement-cooldown", 100);

        movementListener = new PacketAdapter(plugin,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (!boards.containsKey(uuid)) return;

                long now = System.currentTimeMillis();
                Long last = movementCooldowns.get(uuid);
                if (last != null && now - last < cooldownMs) return;
                movementCooldowns.put(uuid, now);

                Player player = event.getPlayer();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) tick(player);
                });
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(movementListener);
    }

    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (movementListener != null) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListener(movementListener);
            } catch (Exception ignored) {}
            movementListener = null;
        }
        for (UUID uuid : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline())
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
        manuallyHidden.clear();
        movementCooldowns.clear();
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private void tick(Player player) {
        Scoreboard sb = boards.get(player.getUniqueId());
        if (sb == null) return;
        Objective obj = sb.getObjective("vanishpp");
        if (obj != null) update(player, sb, obj);
    }

    private void update(Player player, Scoreboard sb, Objective obj) {
        String title = plugin.getScoreboardConfig().getString("title", "&8[ &7Vanish&f++ &8]");
        obj.displayName(parse(title));

        List<String> rawLines = plugin.getScoreboardConfig().getStringList("lines");
        List<String> lines = padColumns(rawLines); // align | separators automatically
        int maxLines = Math.min(lines.size(), ENTRIES.length);

        for (int i = 0; i < ENTRIES.length; i++) {
            String entry = ENTRIES[i];
            Team team = sb.getTeam("vpp_" + entry.charAt(1));
            if (team == null) continue;

            if (i < maxLines) {
                String resolved = applyPlaceholders(player, lines.get(i));
                team.prefix(parse(resolved));
                team.suffix(Component.empty());

                Score score = obj.getScore(entry);
                score.setScore(maxLines - i);
                score.numberFormat(NumberFormat.blank()); // hide the score numbers entirely
            } else {
                sb.resetScores(entry);
            }
        }
    }

    /**
     * Scans all lines for a {@code |} column separator, measures each label's
     * visible character count (after stripping &amp;X color codes), and pads
     * shorter labels with spaces so every {@code |} lands at the same column.
     *
     * <p>Lines without a {@code |} are returned unchanged. Because Minecraft uses
     * a proportional font this gives near-perfect visual alignment for typical
     * label words; it cannot be pixel-perfect without a resource pack.</p>
     */
    private static List<String> padColumns(List<String> lines) {
        int maxLen = 0;
        int[] lens = new int[lines.size()];
        int[] pipes = new int[lines.size()];
        Arrays.fill(pipes, -1);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int p = line.indexOf('|');
            if (p > 0) {
                pipes[i] = p;
                String vis = stripColors(line.substring(0, p)).stripTrailing();
                lens[i] = vis.length();
                if (lens[i] > maxLen) maxLen = lens[i];
            }
        }

        if (maxLen == 0) return lines; // no | found — return as-is

        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (pipes[i] < 0) {
                out.add(lines.get(i));
            } else {
                int pad = maxLen - lens[i];
                // Insert spaces just before the | so padding appears in the
                // label's current color (invisible against the scoreboard bg).
                String before = lines.get(i).substring(0, pipes[i]);
                String from   = lines.get(i).substring(pipes[i]);
                out.add(before + " ".repeat(pad) + from);
            }
        }
        return out;
    }

    /** Strip {@code &X} and {@code §X} legacy color/format codes from a string. */
    private static String stripColors(String s) {
        return s.replaceAll("[&§][0-9a-fk-orxA-FK-ORX]", "");
    }

    private Component parse(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    // -------------------------------------------------------------------------
    // Placeholders
    // -------------------------------------------------------------------------

    private String applyPlaceholders(Player player, String text) {
        if (!text.contains("%")) return text; // fast path

        // --- Server stats (cheap) ---
        double tps = Math.min(20.0, Bukkit.getServer().getTPS()[0]);
        String tpsColor = tps >= 18 ? "&a" : tps >= 15 ? "&e" : "&c";

        long memUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        long memMax  = Runtime.getRuntime().maxMemory() / 1024 / 1024;

        // --- Time with configured timezone ---
        String tzId = plugin.getScoreboardConfig().getString("timezone", "default");
        TimeZone tz = "default".equalsIgnoreCase(tzId)
                ? TimeZone.getDefault()
                : TimeZone.getTimeZone(tzId);
        Date now = new Date();
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
        timeFmt.setTimeZone(tz);
        SimpleDateFormat dateFmt = new SimpleDateFormat("dd/MM/yyyy");
        dateFmt.setTimeZone(tz);

        // --- Player position & direction ---
        org.bukkit.Location loc = player.getLocation();
        double yaw = loc.getYaw();
        if (yaw < 0) yaw += 360;
        String direction;
        if      (yaw >= 337.5 || yaw < 22.5)  direction = "S";
        else if (yaw < 67.5)                   direction = "SW";
        else if (yaw < 112.5)                  direction = "W";
        else if (yaw < 157.5)                  direction = "NW";
        else if (yaw < 202.5)                  direction = "N";
        else if (yaw < 247.5)                  direction = "NE";
        else if (yaw < 292.5)                  direction = "E";
        else                                    direction = "SE";

        // --- Biome ---
        String biome = titleCase(loc.getBlock().getBiome().name().replace("_", " "));

        // --- Gamemode ---
        String gm = switch (player.getGameMode()) {
            case SURVIVAL  -> "Survival";
            case CREATIVE  -> "Creative";
            case ADVENTURE -> "Adventure";
            case SPECTATOR -> "Spectator";
        };

        // --- Attributes ---
        double maxHp = 20.0;
        try { maxHp = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue(); } catch (Exception ignored) {}
        double armorVal = 0.0;
        try { armorVal = player.getAttribute(Attribute.GENERIC_ARMOR).getValue(); } catch (Exception ignored) {}

        text = text
            // Coords & movement
            .replace("%x%",          String.valueOf(loc.getBlockX()))
            .replace("%y%",          String.valueOf(loc.getBlockY()))
            .replace("%z%",          String.valueOf(loc.getBlockZ()))
            .replace("%direction%",  direction)
            .replace("%biome%",      biome)
            // Player info
            .replace("%player%",     player.getName())
            .replace("%displayname%", PlainTextComponentSerializer.plainText().serialize(player.displayName()))
            .replace("%ping%",       String.valueOf(player.getPing()))
            .replace("%gamemode%",   gm)
            .replace("%health%",     String.format("%.1f", player.getHealth()))
            .replace("%max_health%", String.format("%.1f", maxHp))
            .replace("%food%",       String.valueOf(player.getFoodLevel()))
            .replace("%armor%",      String.valueOf((int) armorVal))
            .replace("%level%",      String.valueOf(plugin.getStorageProvider().getVanishLevel(player.getUniqueId())))
            .replace("%vanish_level%", String.valueOf(plugin.getStorageProvider().getVanishLevel(player.getUniqueId())))
            .replace("%world%",      player.getWorld().getName())
            // Server stats
            .replace("%tps%",        tpsColor + String.format("%.1f", tps))
            .replace("%tps_raw%",    String.format("%.1f", tps))
            .replace("%online%",     String.valueOf(Bukkit.getOnlinePlayers().size()))
            .replace("%max_players%", String.valueOf(Bukkit.getMaxPlayers()))
            .replace("%vanished_count%", String.valueOf(plugin.getRawVanishedPlayers().size()))
            .replace("%memory_used%", memUsed + "MB")
            .replace("%memory_max%", memMax  + "MB")
            .replace("%entities%",   String.valueOf(player.getWorld().getEntityCount()))
            .replace("%chunks%",     String.valueOf(player.getWorld().getChunkCount()))
            // Time
            .replace("%time%",       timeFmt.format(now))
            .replace("%date%",       dateFmt.format(now));

        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null)
                text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable ignored) {}

        return text;
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder();
        for (String word : s.split(" ")) {
            if (!word.isEmpty())
                out.append(Character.toUpperCase(word.charAt(0)))
                   .append(word.substring(1).toLowerCase())
                   .append(' ');
        }
        return out.toString().trim();
    }
}
