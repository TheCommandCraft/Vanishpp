package net.thecommandcraft.vanishpp.hooks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the client crash fixed by tracking per-observer knowledge of
 * {@code Vanishpp_Vanished} team membership, instead of deciding purely from the
 * observer's current {@code vanishpp.see} permission at packet-send time.
 *
 * <p>The crash: {@code IllegalStateException: Player is either on another team or not on
 * any team. Cannot remove from team 'Vanishpp_Vanished'} — a client crashes if it
 * receives a REMOVE_PLAYER packet for a team entry it was never told about via a prior
 * ADD_PLAYER/CREATE.
 */
class VanishTeamPacketPolicyTest {

    private static final Predicate<String> NONE_VANISHED = name -> false;

    private Set<String> known() {
        return ConcurrentHashMap.newKeySet();
    }

    @Test
    void nonStaffNeverLearnsOfAddPacket() {
        Set<String> known = known();
        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_ADD_PLAYERS, false, List.of("Victim"), known, NONE_VANISHED);

        assertTrue(d.cancel(), "non-staff must never receive the ADD packet");
        assertTrue(known.isEmpty(), "non-staff observer must not be marked as knowing the entry");
    }

    @Test
    void nonStaffNeverReceivesRemovePacketEither() {
        Set<String> known = known(); // never learned anything
        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_REMOVE_PLAYERS, false, List.of("Victim"), known, NONE_VANISHED);

        assertTrue(d.cancel(), "an observer that never learned the entry must never get a REMOVE for it");
    }

    @Test
    void staffAddIsForwardedAndRemembered() {
        Set<String> known = known();
        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_ADD_PLAYERS, true, List.of("Victim"), known, NONE_VANISHED);

        assertFalse(d.cancel());
        assertEquals(List.of("Victim"), d.names());
        assertTrue(known.contains("Victim"), "staff observer must be remembered as knowing the entry");
    }

    @Test
    void staffRemoveOfKnownNameIsForwardedAndForgotten() {
        Set<String> known = known();
        known.add("Victim"); // received a prior ADD while staff

        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_REMOVE_PLAYERS, true, List.of("Victim"), known, NONE_VANISHED);

        assertFalse(d.cancel());
        assertEquals(List.of("Victim"), d.names());
        assertFalse(known.contains("Victim"), "consumed entry must be forgotten after removal");
    }

    /**
     * The actual regression this fix targets: an observer granted {@code vanishpp.see}
     * (e.g. live via LuckPerms, no relog) AFTER a vanish already happened never received
     * the matching ADD, so their client has no record of the entry. The REMOVE that
     * follows an eventual unvanish must be suppressed for them specifically — even
     * though they are staff *now* — or their client crashes exactly as in the
     * "disconnect-2026-07-27_11.21.02-client.txt" report.
     */
    @Test
    void promotedMidVanishNeverLearnedEntrySoRemoveIsSuppressed() {
        Set<String> known = known(); // ADD was scrubbed earlier while this observer was non-staff

        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_REMOVE_PLAYERS, true /* now staff */, List.of("Victim"),
                known, NONE_VANISHED);

        assertTrue(d.cancel(), "promoted observer never learned the entry — REMOVE must not reach their client");
    }

    /**
     * Inverse direction: an observer demoted mid-vanish (learned the entry while staff,
     * then lost vanishpp.see before the unvanish) must still receive the REMOVE so their
     * client's state doesn't go stale — this used to be wrongly suppressed because the
     * old logic gated REMOVE on current permission instead of prior knowledge.
     */
    @Test
    void demotedMidVanishStillReceivesRemoveForKnownEntry() {
        Set<String> known = known();
        known.add("Victim"); // learned while staff

        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_REMOVE_PLAYERS, false /* now demoted */, List.of("Victim"),
                known, NONE_VANISHED);

        assertFalse(d.cancel(), "an observer who legitimately knows the entry must still be told it's removed");
        assertEquals(List.of("Victim"), d.names());
    }

    @Test
    void createNeverCancelsOutrightEvenWhenFullyScrubbedForNonStaff() {
        // Client must still learn the team exists (even empty) so a later ADD/REMOVE for
        // it isn't rejected as referring to an unknown team.
        Set<String> known = known();
        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_CREATE, false, List.of("Victim"), known, name -> true);

        assertFalse(d.cancel());
        assertTrue(d.names().isEmpty());
    }

    @Test
    void createForStaffKeepsAllMembersAndRemembersThem() {
        Set<String> known = known();
        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_CREATE, true, List.of("A", "B"), known, name -> true);

        assertFalse(d.cancel());
        assertEquals(List.of("A", "B"), d.names());
        assertTrue(known.containsAll(List.of("A", "B")));
    }

    @Test
    void removeIsPartiallyFilteredWhenOnlySomeNamesAreKnown() {
        Set<String> known = known();
        known.add("KnownVictim");

        VanishTeamPacketPolicy.Decision d = VanishTeamPacketPolicy.decide(
                VanishTeamPacketPolicy.ACTION_REMOVE_PLAYERS, true,
                List.of("KnownVictim", "UnknownVictim"), known, NONE_VANISHED);

        assertFalse(d.cancel());
        assertEquals(List.of("KnownVictim"), d.names());
    }
}
