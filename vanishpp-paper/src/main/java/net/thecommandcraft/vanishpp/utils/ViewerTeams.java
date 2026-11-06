package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.stream.Stream;

/** Owns private overlays; original scoreboards are never used as writable overlays. */
public final class ViewerTeams {
    private static final String NAME = "Vanishpp_Vanished";
    private static final int RECONCILE_BUDGET = 256;
    private final Vanishpp plugin;
    private final Map<UUID, Overlay> overlays = new HashMap<>();
    private final Set<UUID> departing = new HashSet<>();

    private static final class Overlay {
        final Scoreboard original;
        final Scoreboard board;
        Set<String> visible = Set.of();
        Iterator<Runnable> updates = Collections.emptyIterator();
        Overlay(Scoreboard original, Scoreboard board) { this.original = original; this.board = board; }
    }

    public ViewerTeams(Vanishpp plugin) { this.plugin = plugin; }

    public void joined(UUID player) { departing.remove(player); }

    public void update(Player subject, boolean vanished) {
        if (vanished) departing.remove(subject.getUniqueId());
        refresh();
    }

    public void retireSubject(Player subject) {
        departing.add(subject.getUniqueId());
        forget(subject.getUniqueId());
        refresh();
    }

    public void refresh() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        departing.removeIf(uuid -> online.stream().noneMatch(player -> player.getUniqueId().equals(uuid)));
        for (Player viewer : online) {
            UUID id = viewer.getUniqueId();
            if (departing.contains(id)) continue;
            Set<String> visible = new HashSet<>();
            for (Player subject : online) {
                if (!subject.equals(viewer) && !departing.contains(subject.getUniqueId()) && plugin.isVanished(subject)
                        && plugin.getPermissionManager().canSee(viewer, subject)) visible.add(subject.getName());
            }
            if (visible.isEmpty()) { forget(id); continue; }
            Overlay overlay = overlays.get(id);
            if (overlay == null || viewer.getScoreboard() != overlay.board) {
                overlay = new Overlay(viewer.getScoreboard(), Bukkit.getScoreboardManager().getNewScoreboard());
                overlays.put(id, overlay);
                viewer.setScoreboard(overlay.board);
            }
            overlay.visible = Set.copyOf(visible);
            if (!overlay.updates.hasNext()) overlay.updates = updates(overlay).iterator();
            for (int work = 0; work < RECONCILE_BUDGET && overlay.updates.hasNext(); work++) overlay.updates.next().run();
            applyVisibility(overlay, viewer);
        }
    }

    public void forget(UUID viewerId) {
        Overlay overlay = overlays.remove(viewerId);
        Player viewer = Bukkit.getPlayer(viewerId);
        if (overlay != null && viewer != null && viewer.getScoreboard() == overlay.board)
            viewer.setScoreboard(overlay.original);
    }

    public void close() {
        for (UUID id : new ArrayList<>(overlays.keySet())) forget(id);
        departing.clear();
    }

    private void applyVisibility(Overlay overlay, Player viewer) {
        Team team = overlay.board.getTeam(NAME);
        if (team == null) team = overlay.board.registerNewTeam(NAME);
        var prefix = plugin.getMessageManager().parse(plugin.getConfigManager().vanishNametagPrefix, viewer);
        if (!team.prefix().equals(prefix)) team.prefix(prefix);
        team.setCanSeeFriendlyInvisibles(true);
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        for (String name : new HashSet<>(team.getEntries())) if (!overlay.visible.contains(name)) team.removeEntry(name);
        for (String name : overlay.visible) if (!team.hasEntry(name)) team.addEntry(name);
    }

    /** Each refresh advances a bounded number of metadata, score, or membership operations. */
    private Stream<Runnable> updates(Overlay overlay) {
        Scoreboard source = overlay.original, target = overlay.board;
        Set<String> objectives = new HashSet<>();
        source.getObjectives().forEach(value -> objectives.add(value.getName()));
        Set<String> teams = new HashSet<>();
        source.getTeams().forEach(value -> { if (!value.getName().equals(NAME)) teams.add(value.getName()); });
        Set<String> entries = new HashSet<>(source.getEntries());
        entries.addAll(target.getEntries());
        Stream<Runnable> removedObjectives = new ArrayList<>(target.getObjectives()).stream().filter(value -> !objectives.contains(value.getName()))
                .map(value -> () -> value.unregister());
        Stream<Runnable> objectiveUpdates = objectives.stream().flatMap(name -> Stream.concat(
                Stream.of((Runnable) () -> syncObjective(source, target, name)),
                entries.stream().map(entry -> (Runnable) () -> syncScore(source, target, name, entry))));
        Stream<Runnable> removedTeams = new ArrayList<>(target.getTeams()).stream().filter(value -> !value.getName().equals(NAME) && !teams.contains(value.getName()))
                .map(value -> () -> value.unregister());
        Stream<Runnable> teamUpdates = teams.stream().flatMap(name -> Stream.concat(
                Stream.of((Runnable) () -> syncTeam(source, target, name)), teamEntries(overlay, name)));
        return Stream.of(removedObjectives, objectiveUpdates, removedTeams, teamUpdates).flatMap(stream -> stream);
    }

    private Stream<Runnable> teamEntries(Overlay overlay, String name) {
        Set<String> entries = new HashSet<>();
        Team source = overlay.original.getTeam(name), target = overlay.board.getTeam(name);
        if (source != null) entries.addAll(source.getEntries());
        if (target != null) entries.addAll(target.getEntries());
        return entries.stream().map(entry -> () -> {
            Team original = overlay.original.getTeam(name), copy = overlay.board.getTeam(name);
            if (copy == null) return;
            boolean belongs = original != null && original.hasEntry(entry) && !overlay.visible.contains(entry);
            if (belongs && !copy.hasEntry(entry)) copy.addEntry(entry);
            else if (!belongs && copy.hasEntry(entry)) copy.removeEntry(entry);
        });
    }

    @SuppressWarnings("deprecation")
    private void syncObjective(Scoreboard source, Scoreboard target, String name) {
        Objective original = source.getObjective(name);
        if (original == null) return;
        Objective copy = target.getObjective(name);
        if (copy != null && !copy.getCriteria().equals(original.getCriteria())) { copy.unregister(); copy = null; }
        if (copy == null) copy = target.registerNewObjective(name, original.getCriteria(), original.displayName(), original.getRenderType());
        if (!copy.displayName().equals(original.displayName())) copy.displayName(original.displayName());
        if (copy.getRenderType() != original.getRenderType()) copy.setRenderType(original.getRenderType());
        if (copy.getDisplaySlot() != original.getDisplaySlot()) copy.setDisplaySlot(original.getDisplaySlot());
        try {
            if (!Objects.equals(copy.numberFormat(), original.numberFormat())) copy.numberFormat(original.numberFormat());
        } catch (UnsupportedOperationException ignored) { /* MockBukkit has no number-format implementation. */ }
    }

    private void syncScore(Scoreboard source, Scoreboard target, String objective, String entry) {
        Objective original = source.getObjective(objective), copy = target.getObjective(objective);
        if (original == null || copy == null) return;
        Score from = original.getScore(entry), to = copy.getScore(entry);
        if (from.isScoreSet()) {
            if (!to.isScoreSet() || to.getScore() != from.getScore()) to.setScore(from.getScore());
            try {
                if (!Objects.equals(to.customName(), from.customName())) to.customName(from.customName());
                if (!Objects.equals(to.numberFormat(), from.numberFormat())) to.numberFormat(from.numberFormat());
            } catch (UnsupportedOperationException ignored) { /* Optional scoreboard formatting API. */ }
        } else if (to.isScoreSet()) to.resetScore();
    }

    @SuppressWarnings("deprecation")
    private void syncTeam(Scoreboard source, Scoreboard target, String name) {
        Team original = source.getTeam(name);
        if (original == null) return;
        Team copy = target.getTeam(name);
        if (copy == null) copy = target.registerNewTeam(name);
        if (!copy.displayName().equals(original.displayName())) copy.displayName(original.displayName());
        if (!copy.prefix().equals(original.prefix())) copy.prefix(original.prefix());
        if (!copy.suffix().equals(original.suffix())) copy.suffix(original.suffix());
        if (copy.getColor() != original.getColor()) copy.setColor(original.getColor());
        if (copy.allowFriendlyFire() != original.allowFriendlyFire()) copy.setAllowFriendlyFire(original.allowFriendlyFire());
        if (copy.canSeeFriendlyInvisibles() != original.canSeeFriendlyInvisibles()) copy.setCanSeeFriendlyInvisibles(original.canSeeFriendlyInvisibles());
        for (Team.Option option : Team.Option.values())
            if (copy.getOption(option) != original.getOption(option)) copy.setOption(option, original.getOption(option));
    }
}
