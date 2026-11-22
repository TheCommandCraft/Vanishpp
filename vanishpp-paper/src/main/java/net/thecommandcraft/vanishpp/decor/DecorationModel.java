package net.thecommandcraft.vanishpp.decor;

import java.util.*;

/**
 * What one viewer's client believes about teams and tab names, and what Vanish++ changes in it.
 *
 * <p>Vanish++ never touches the server's scoreboards or tab names. It only edits the team and tab packets a viewer
 * receives:
 * <ul>
 *   <li>{@link State#HIDDEN}: a vanished player the viewer may not see. Team packets naming them are stripped, so the
 *       viewer learns nothing new about them; what the client already knew stays as it was.</li>
 *   <li>{@link State#TAGGED}: a vanished player the viewer may see (staff). On this client only, they are moved to a
 *       team of their own that copies their real team (prefix, suffix, color, options) with the [V] prefix in front.
 *       Packets that would move them back are stripped; the copy follows changes of the real team.</li>
 *   <li>{@link State#NORMAL}: everything passes; after a vanished state ends the client gets the real team back.</li>
 * </ul>
 * Tab names work the same way: a decorated subject's display name gets the tab prefix in front of whatever name the
 * server or another plugin sends, and the plain name comes back when the decoration ends.
 *
 * <p>The client rules this keeps intact (vanilla {@code Scoreboard}): joining a team leaves the previous one, and
 * removing a player from a team they are not on throws, so leave packets only go out for real members.
 *
 * <p>Pure logic with opaque parameter and component types, so it is unit-testable without a server. All methods are
 * synchronized: packets arrive on the network thread, refreshes come from the server thread.
 *
 * @param <P> the team parameters type (prefix, suffix, color, options)
 * @param <C> the chat component type
 */
public final class DecorationModel<P, C> {

    public enum State { NORMAL, TAGGED, HIDDEN }

    public static final int ADD = 0, REMOVE = 1, CHANGE = 2, JOIN = 3, LEAVE = 4;

    /** A team packet: {@code params} is set for ADD and CHANGE, {@code players} for ADD, JOIN and LEAVE. */
    public record TeamOp<P>(String team, int method, P params, List<String> players) {
        public TeamOp { players = players == null ? List.of() : List.copyOf(players); }
    }

    /** A tab display-name update for one subject; {@code display} null means the plain profile name. */
    public record DisplayOp<C>(UUID subject, C display) {}

    /** A player that is online, by entry name and id. */
    public record Subject(String name, UUID id) {}

    /** What the model needs to know about the viewer and the decorations. */
    public interface Env<P, C> {
        /** The state of the online player with this team entry name for this viewer; NORMAL for anything else. */
        State stateOf(String entry);
        /** Whether this subject's tab name gets the tab prefix for this viewer. */
        boolean tabDecorated(UUID subject);
        /** The name of the private team a tagged player is put in on this client. */
        String privateTeam(String entry);
        /** The real team's parameters with the [V] prefix in front; {@code real} null means no team. */
        P tag(P real);
        /** The tab prefix in front of {@code display}; {@code display} null means the plain name of {@code subject}. */
        C decorate(C display, UUID subject);
        /** Whether two team parameter sets look the same on a client. */
        boolean sameParams(P a, P b);
    }

    private final Env<P, C> env;

    // Teams.
    private final Set<String> clientTeams = new HashSet<>();
    private final Map<String, P> params = new HashMap<>();
    private final Map<String, String> intended = new HashMap<>(); // entry -> team the server wants this client to show
    private final Map<String, String> actual = new HashMap<>();   // entry -> team the client shows
    private final Map<String, String> privateTeams = new HashMap<>(); // tagged entry -> private team on the client
    private final Map<String, P> privateParams = new HashMap<>();
    private final Set<String> privateNames = new HashSet<>();
    // Tab names.
    private final Set<UUID> listed = new HashSet<>();
    private final Map<UUID, C> intendedDisplay = new HashMap<>();
    private final Map<UUID, C> sentDisplay = new HashMap<>();
    private final Set<UUID> decorated = new HashSet<>();
    private boolean dirty;

    public DecorationModel(Env<P, C> env) { this.env = env; }

    /** True when a change seen in a packet needs a refresh (for example a tagged player's real team changed). */
    public synchronized boolean takeDirty() { boolean d = dirty; dirty = false; return d; }

    /**
     * A team packet the server or another plugin sends to this viewer. Returns the packet to send instead (the same
     * object when nothing changes), or null to drop it.
     */
    public synchronized TeamOp<P> onTeamPacket(TeamOp<P> op) {
        String team = op.team();
        if (privateNames.contains(team)) return op; // never expected: our names are reserved
        switch (op.method()) {
            case ADD -> {
                clientTeams.add(team);
                params.put(team, op.params());
                for (String entry : op.players()) intended.put(entry, team);
                List<String> kept = new ArrayList<>();
                for (String entry : op.players()) {
                    State state = env.stateOf(entry);
                    if (state == State.NORMAL) { kept.add(entry); actual.put(entry, team); }
                    else if (state == State.TAGGED) dirty = true;
                }
                return kept.size() == op.players().size() ? op : new TeamOp<>(team, ADD, op.params(), kept);
            }
            case REMOVE -> {
                for (String entry : privateTeams.keySet()) if (team.equals(intended.get(entry))) dirty = true;
                clientTeams.remove(team);
                params.remove(team);
                intended.values().removeIf(team::equals);
                actual.values().removeIf(team::equals);
                return op;
            }
            case CHANGE -> {
                params.put(team, op.params());
                for (String entry : privateTeams.keySet()) if (team.equals(intended.get(entry))) dirty = true;
                return op;
            }
            case JOIN -> {
                List<String> kept = new ArrayList<>();
                for (String entry : op.players()) {
                    intended.put(entry, team);
                    State state = env.stateOf(entry);
                    if (state == State.NORMAL) { kept.add(entry); actual.put(entry, team); }
                    else if (state == State.TAGGED) dirty = true;
                }
                if (kept.isEmpty()) return null;
                return kept.size() == op.players().size() ? op : new TeamOp<>(team, JOIN, null, kept);
            }
            case LEAVE -> {
                List<String> kept = new ArrayList<>();
                for (String entry : op.players()) {
                    if (team.equals(intended.get(entry))) intended.remove(entry);
                    State state = env.stateOf(entry);
                    if (state == State.TAGGED) dirty = true;
                    // Only real members: a real client throws when told to remove someone from a team they're not on.
                    if (state == State.NORMAL && team.equals(actual.get(entry))) { kept.add(entry); actual.remove(entry); }
                }
                if (kept.isEmpty()) return null;
                return kept.size() == op.players().size() ? op : new TeamOp<>(team, LEAVE, null, kept);
            }
            default -> { return op; }
        }
    }

    /** A tab entry the client is told about (player info "add"). */
    public synchronized void onListed(UUID subject) { listed.add(subject); }

    /** Tab entries the client is told to forget (player info "remove"). */
    public synchronized void onUnlisted(Collection<UUID> subjects) {
        for (UUID id : subjects) {
            listed.remove(id);
            intendedDisplay.remove(id);
            sentDisplay.remove(id);
            decorated.remove(id);
        }
    }

    /** A display name the server or another plugin sends for this subject. Returns the display name to send instead. */
    public synchronized C onDisplay(UUID subject, C display) {
        intendedDisplay.put(subject, display);
        if (env.tabDecorated(subject)) {
            C out = env.decorate(display, subject);
            decorated.add(subject);
            sentDisplay.put(subject, out);
            return out;
        }
        decorated.remove(subject);
        sentDisplay.put(subject, display);
        return display;
    }

    /**
     * Brings the client in line with the current vanish states, permissions and settings. Returns the packets to send,
     * in order ({@link TeamOp} and {@link DisplayOp}).
     */
    public synchronized List<Object> refresh(Collection<Subject> online) {
        List<Object> out = new ArrayList<>();
        Set<String> onlineNames = new HashSet<>();
        for (Subject s : online) onlineNames.add(s.name());
        // Private teams of players who left.
        for (String entry : List.copyOf(privateTeams.keySet())) if (!onlineNames.contains(entry)) dropPrivate(entry, out);
        for (Subject s : online) {
            String entry = s.name();
            State state = env.stateOf(entry);
            if (state == State.TAGGED) {
                P wanted = env.tag(params.get(intended.get(entry)));
                String own = privateTeams.get(entry);
                if (own == null) {
                    own = env.privateTeam(entry);
                    privateTeams.put(entry, own);
                    privateNames.add(own);
                    clientTeams.add(own);
                    privateParams.put(entry, wanted);
                    actual.put(entry, own);
                    out.add(new TeamOp<>(own, ADD, wanted, List.of(entry)));
                } else {
                    if (!env.sameParams(privateParams.get(entry), wanted)) {
                        privateParams.put(entry, wanted);
                        out.add(new TeamOp<>(own, CHANGE, wanted, List.of()));
                    }
                    if (!own.equals(actual.get(entry))) {
                        actual.put(entry, own);
                        out.add(new TeamOp<>(own, JOIN, null, List.of(entry)));
                    }
                }
            } else {
                if (privateTeams.containsKey(entry)) dropPrivate(entry, out);
                if (state == State.NORMAL) {
                    String want = intended.get(entry);
                    if (want != null && !clientTeams.contains(want)) want = null;
                    String has = actual.get(entry);
                    if (!Objects.equals(want, has)) {
                        if (want != null) {
                            actual.put(entry, want);
                            out.add(new TeamOp<>(want, JOIN, null, List.of(entry)));
                        } else {
                            actual.remove(entry);
                            out.add(new TeamOp<>(has, LEAVE, null, List.of(entry)));
                        }
                    }
                }
            }
            // Tab name.
            UUID id = s.id();
            if (!listed.contains(id)) continue;
            boolean decorate = env.tabDecorated(id);
            C base = intendedDisplay.get(id);
            C want = decorate ? env.decorate(base, id) : base;
            boolean changed = decorate != decorated.contains(id) || !Objects.equals(want, sentDisplay.get(id));
            if (changed) {
                if (decorate) decorated.add(id); else decorated.remove(id);
                sentDisplay.put(id, want);
                out.add(new DisplayOp<>(id, want));
            }
        }
        return out;
    }

    /** Undoes every change on the client (used when Vanish++ disables). */
    public synchronized List<Object> clear() {
        List<Object> out = new ArrayList<>();
        for (String entry : List.copyOf(privateTeams.keySet())) {
            dropPrivate(entry, out);
            String want = intended.get(entry);
            if (want != null && clientTeams.contains(want)) {
                actual.put(entry, want);
                out.add(new TeamOp<>(want, JOIN, null, List.of(entry)));
            }
        }
        for (UUID id : List.copyOf(decorated)) {
            decorated.remove(id);
            sentDisplay.put(id, intendedDisplay.get(id));
            out.add(new DisplayOp<>(id, intendedDisplay.get(id)));
        }
        return out;
    }

    private void dropPrivate(String entry, List<Object> out) {
        String own = privateTeams.remove(entry);
        privateParams.remove(entry);
        privateNames.remove(own);
        clientTeams.remove(own);
        if (own.equals(actual.get(entry))) actual.remove(entry);
        out.add(new TeamOp<P>(own, REMOVE, null, List.of()));
    }

    // Read-only views for tests.
    synchronized String actualTeam(String entry) { return actual.get(entry); }
    synchronized String intendedTeam(String entry) { return intended.get(entry); }
}
