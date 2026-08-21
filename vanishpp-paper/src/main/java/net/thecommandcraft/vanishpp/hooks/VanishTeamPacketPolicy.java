package net.thecommandcraft.vanishpp.hooks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic for scrubbing {@code SCOREBOARD_TEAM} packets sent for the internal
 * {@code Vanishpp_Vanished} team, extracted out of {@link ProtocolLibManager} so it can be
 * unit-tested without a real ProtocolLib {@code PacketEvent}/{@code ProtocolManager}.
 *
 * <p>The client-side crash this guards against ({@code IllegalStateException: Player is
 * either on another team or not on any team}) happens when a client receives a
 * REMOVE_PLAYER packet for a team entry it was never told about via a prior ADD_PLAYER
 * packet. Deciding whether to forward a REMOVE purely from the observer's <em>current</em>
 * {@code vanishpp.see} permission is wrong, because that permission can be granted or
 * revoked live (e.g. via LuckPerms) without a relog — the observer's client only knows
 * what it was actually sent in the past, not what the observer's permission happens to be
 * right now. Every decision here is instead keyed off a per-observer {@code known} set:
 * the set of team member names that specific observer's client has actually been told
 * about via a prior CREATE/ADD it received.
 */
final class VanishTeamPacketPolicy {

    /** Vanilla {@code ClientboundSetPlayerTeamPacket} action codes this policy cares about. */
    static final int ACTION_CREATE = 0;
    static final int ACTION_ADD_PLAYERS = 3;
    static final int ACTION_REMOVE_PLAYERS = 4;

    /** Result of applying the policy to one packet for one observer. */
    record Decision(boolean cancel, List<String> names) {
    }

    private VanishTeamPacketPolicy() {
    }

    /**
     * @param action        the packet's action code (0/3/4 — others are not routed here)
     * @param isStaff       whether the observer currently has {@code vanishpp.see}
     * @param names         the player names carried by the packet (never null/empty when called)
     * @param known         mutable set of names this observer's client is already aware of;
     *                      updated in place to reflect what this decision sends them
     * @param currentlyVanished predicate: is the named player currently vanished? (only
     *                      consulted for the non-staff CREATE case, matching prior behavior)
     */
    static Decision decide(int action, boolean isStaff, Collection<String> names, Set<String> known,
                           java.util.function.Predicate<String> currentlyVanished) {
        switch (action) {
            case ACTION_CREATE -> {
                if (isStaff) {
                    known.addAll(names);
                    return new Decision(false, List.copyOf(names));
                }
                // Never cancel CREATE outright — the client needs to learn the team exists
                // at all (even with zero members) so a later ADD/REMOVE for it isn't
                // rejected as referring to an unknown team. Just filter the member list.
                List<String> scrubbed = new ArrayList<>();
                for (String name : names) {
                    if (!currentlyVanished.test(name)) scrubbed.add(name);
                }
                return new Decision(false, scrubbed);
            }
            case ACTION_ADD_PLAYERS -> {
                if (!isStaff) {
                    // Non-staff must never learn a vanished player is on this team at all.
                    return new Decision(true, List.of());
                }
                known.addAll(names);
                return new Decision(false, List.copyOf(names));
            }
            case ACTION_REMOVE_PLAYERS -> {
                // Regardless of the observer's CURRENT permission, only forward removal of
                // names this specific client actually knows about (received via a prior
                // CREATE/ADD). This is what makes both directions safe: an observer promoted
                // to staff mid-vanish never learned the name, so it's suppressed here (no
                // crash); an observer demoted mid-vanish did learn it earlier, so it's still
                // correctly removed even though they're no longer "staff" at send time.
                List<String> safe = new ArrayList<>();
                for (String name : names) {
                    if (known.remove(name)) safe.add(name);
                }
                return new Decision(safe.isEmpty(), safe);
            }
            default -> {
                return new Decision(false, List.copyOf(names));
            }
        }
    }
}
