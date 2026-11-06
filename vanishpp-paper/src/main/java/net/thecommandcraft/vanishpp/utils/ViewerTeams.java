package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/** Private team delivery when no packet filter can protect a shared scoreboard. */
public final class ViewerTeams {
    private static final String NAME = "Vanishpp_Vanished";
    private final Vanishpp plugin;
    private final Map<UUID, Scoreboard> originals = new HashMap<>();
    private final Map<UUID, Scoreboard> copies = new HashMap<>();

    public ViewerTeams(Vanishpp plugin) { this.plugin = plugin; }

    public void update(Player subject, boolean vanished) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            boolean visible = vanished && plugin.getPermissionManager().canSee(viewer, subject);
            Team team = board.getTeam(NAME);
            Scoreboard current = board;
            boolean shared = board == Bukkit.getScoreboardManager().getMainScoreboard()
                    || Bukkit.getOnlinePlayers().stream().anyMatch(other -> !other.equals(viewer) && other.getScoreboard() == current);
            if (shared) {
                if (team != null) for (String entry : new HashSet<>(team.getEntries())) team.removeEntry(entry);
                if (!visible) continue;
                Scoreboard copy = copy(board);
                originals.put(viewer.getUniqueId(), board);
                copies.put(viewer.getUniqueId(), copy);
                viewer.setScoreboard(copy);
                board = copy;
                team = null;
            }
            if (team == null && visible) team = board.registerNewTeam(NAME);
            if (team == null) continue;
            if (visible) {
                team.prefix(plugin.getMessageManager().parse(plugin.getConfigManager().vanishNametagPrefix, viewer));
                team.setCanSeeFriendlyInvisibles(true);
                team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
                team.addEntry(subject.getName());
            } else team.removeEntry(subject.getName());
        }
    }

    public void forget(UUID viewer) {
        originals.remove(viewer);
        copies.remove(viewer);
    }

    public void close() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getScoreboard() == copies.get(viewer.getUniqueId()))
                viewer.setScoreboard(originals.get(viewer.getUniqueId()));
        }
        originals.clear();
        copies.clear();
    }

    @SuppressWarnings("deprecation")
    private Scoreboard copy(Scoreboard source) {
        Scoreboard copy = Bukkit.getScoreboardManager().getNewScoreboard();
        for (Objective original : source.getObjectives()) {
            Objective objective = copy.registerNewObjective(original.getName(), original.getCriteria(),
                    original.displayName(), original.getRenderType());
            if (original.getDisplaySlot() != null) objective.setDisplaySlot(original.getDisplaySlot());
            for (String entry : source.getEntries()) {
                Score score = original.getScore(entry);
                if (score.isScoreSet()) objective.getScore(entry).setScore(score.getScore());
            }
        }
        for (Team original : source.getTeams()) {
            if (original.getName().equals(NAME)) continue;
            Team team = copy.registerNewTeam(original.getName());
            team.displayName(original.displayName());
            team.prefix(original.prefix());
            team.suffix(original.suffix());
            team.setColor(original.getColor());
            team.setAllowFriendlyFire(original.allowFriendlyFire());
            team.setCanSeeFriendlyInvisibles(original.canSeeFriendlyInvisibles());
            for (Team.Option option : Team.Option.values()) team.setOption(option, original.getOption(option));
            original.getEntries().forEach(team::addEntry);
        }
        return copy;
    }
}
