package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/** Owns private overlays; original scoreboards are never used as writable overlays. */
public final class ViewerTeams {
    private static final String NAME = "Vanishpp_Vanished";
    private static final int RECONCILE_BUDGET = 256;
    private final Vanishpp plugin;
    private final Map<UUID, Overlay> overlays = new HashMap<>();
    private final Set<UUID> departing = new HashSet<>();
    private final Map<Scoreboard, Source> sources = new IdentityHashMap<>();
    private int nextOverlay;

    private static final class Overlay {
        final Scoreboard original;
        final Scoreboard board;
        Set<String> visible = Set.of();
        Set<String> applied = Set.of();
        Cursor cursor;
        Overlay(Scoreboard original, Scoreboard board) { this.original = original; this.board = board; }
    }

    /** Bukkit returns whole snapshots. Share each acquisition across viewers of the same board. */
    private static final class Source {
        Set<Objective> objectives;
        Set<Team> teams;
        Set<String> entries;
        final Map<String, Set<String>> members = new HashMap<>();
    }

    private static final class Budget {
        boolean snapshot;
        boolean acquireSnapshot() {
            if (snapshot) return false;
            snapshot = true;
            return true;
        }
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
        List<Overlay> active = new ArrayList<>();
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
            applyVisibility(overlay, viewer);
            Source source = sources.computeIfAbsent(overlay.original, ignored -> new Source());
            if (overlay.cursor == null || overlay.cursor.source != source) overlay.cursor = new Cursor(overlay, source);
            active.add(overlay);
        }
        Budget budget = new Budget();
        int work = 0, stalled = 0;
        while (!active.isEmpty() && work < RECONCILE_BUDGET && stalled < active.size()) {
            Overlay overlay = active.get(Math.floorMod(nextOverlay++, active.size()));
            if (overlay.cursor.step(budget)) { work++; stalled = 0; }
            else stalled++;
        }
        // Rotate the first chance to acquire a snapshot even when all cursors are waiting for one.
        nextOverlay++;
        sources.entrySet().removeIf(entry -> active.stream().noneMatch(overlay ->
                overlay.cursor.source == entry.getValue() && !overlay.cursor.done));
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
        sources.clear();
    }

    private void applyVisibility(Overlay overlay, Player viewer) {
        Team team = overlay.board.getTeam(NAME);
        if (team == null) team = overlay.board.registerNewTeam(NAME);
        var prefix = plugin.getMessageManager().parse(plugin.getConfigManager().vanishNametagPrefix, viewer);
        if (!team.prefix().equals(prefix)) team.prefix(prefix);
        team.setCanSeeFriendlyInvisibles(true);
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        for (String name : overlay.applied) if (!overlay.visible.contains(name)) team.removeEntry(name);
        for (String name : overlay.visible) if (!team.hasEntry(name)) team.addEntry(name);
        overlay.applied = overlay.visible;
    }

    /** No eager traversal or Runnable batch: a step consumes one element or acquires one snapshot. */
    private final class Cursor {
        final Overlay overlay;
        final Source source;
        int phase;
        boolean done;
        Iterator<Objective> objectives, removedObjectives;
        Iterator<Team> teams, removedTeams;
        Iterator<String> entries;
        Set<String> targetEntries;
        String name;

        Cursor(Overlay overlay, Source source) { this.overlay = overlay; this.source = source; }

        boolean step(Budget budget) {
            Scoreboard original = overlay.original, target = overlay.board;
            switch (phase) {
                case 0 -> {
                    if (source.objectives == null) {
                        if (!budget.acquireSnapshot()) return false;
                        source.objectives = original.getObjectives();
                    }
                    objectives = source.objectives.iterator();
                    phase++;
                }
                case 1 -> {
                    if (!budget.acquireSnapshot()) return false;
                    removedObjectives = target.getObjectives().iterator();
                    phase++;
                }
                case 2 -> {
                    if (removedObjectives.hasNext()) {
                        Objective value = removedObjectives.next();
                        if (original.getObjective(value.getName()) == null) value.unregister();
                    } else phase++;
                }
                case 3 -> {
                    if (source.entries == null) {
                        if (!budget.acquireSnapshot()) return false;
                        source.entries = original.getEntries();
                    }
                    phase++;
                }
                case 4 -> {
                    if (!budget.acquireSnapshot()) return false;
                    targetEntries = target.getEntries();
                    phase++;
                }
                case 5 -> {
                    if (objectives.hasNext()) {
                        name = objectives.next().getName();
                        syncObjective(original, target, name);
                        entries = source.entries.iterator();
                        phase++;
                    } else phase = 8;
                }
                case 6 -> {
                    if (entries.hasNext()) syncScore(original, target, name, entries.next());
                    else { entries = targetEntries.iterator(); phase++; }
                }
                case 7 -> {
                    if (entries.hasNext()) syncScore(original, target, name, entries.next());
                    else phase = 5;
                }
                case 8 -> {
                    if (source.teams == null) {
                        if (!budget.acquireSnapshot()) return false;
                        source.teams = original.getTeams();
                    }
                    teams = source.teams.iterator();
                    phase++;
                }
                case 9 -> {
                    if (!budget.acquireSnapshot()) return false;
                    removedTeams = target.getTeams().iterator();
                    phase++;
                }
                case 10 -> {
                    if (removedTeams.hasNext()) {
                        Team value = removedTeams.next();
                        if (!value.getName().equals(NAME) && original.getTeam(value.getName()) == null) value.unregister();
                    } else phase++;
                }
                case 11 -> {
                    if (!teams.hasNext()) { done = true; return false; }
                    name = teams.next().getName();
                    if (!name.equals(NAME)) { syncTeam(original, target, name); phase++; }
                }
                case 12 -> {
                    Set<String> members = source.members.get(name);
                    if (members == null) {
                        if (!budget.acquireSnapshot()) return false;
                        Team team = original.getTeam(name);
                        members = team == null ? Set.of() : team.getEntries();
                        source.members.put(name, members);
                    }
                    entries = members.iterator();
                    phase++;
                }
                case 13 -> {
                    if (entries.hasNext()) syncMember(overlay, name, entries.next());
                    else phase++;
                }
                case 14 -> {
                    if (!budget.acquireSnapshot()) return false;
                    Team team = target.getTeam(name);
                    entries = (team == null ? Set.<String>of() : team.getEntries()).iterator();
                    phase++;
                }
                case 15 -> {
                    if (entries.hasNext()) syncMember(overlay, name, entries.next());
                    else phase = 11;
                }
                default -> throw new IllegalStateException("Unknown scoreboard cursor phase " + phase);
            }
            return true;
        }
    }

    private void syncMember(Overlay overlay, String name, String entry) {
        Team original = overlay.original.getTeam(name), copy = overlay.board.getTeam(name);
        if (copy == null) return;
        boolean belongs = original != null && original.hasEntry(entry) && !overlay.visible.contains(entry);
        if (belongs && !copy.hasEntry(entry)) copy.addEntry(entry);
        else if (!belongs && copy.hasEntry(entry)) copy.removeEntry(entry);
    }

    @SuppressWarnings("deprecation")
    private void syncObjective(Scoreboard source, Scoreboard target, String name) {
        Objective original = source.getObjective(name);
        if (original == null) return;
        String criteria = original.isModifiable() ? original.getCriteria() : "dummy";
        Objective copy = target.getObjective(name);
        if (copy != null && !copy.getCriteria().equals(criteria)) { copy.unregister(); copy = null; }
        if (copy == null) copy = target.registerNewObjective(name, criteria, original.displayName(), original.getRenderType());
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
