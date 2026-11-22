package net.thecommandcraft.vanishpp.decor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static net.thecommandcraft.vanishpp.decor.DecorationModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** The per-viewer rules, with team parameters and components as plain strings. */
class DecorationModelTest {
    private final Map<String, State> states = new HashMap<>();
    private final Set<UUID> tabDecorated = new HashSet<>();
    private DecorationModel<String, String> model;
    private final UUID vId = UUID.randomUUID();
    private final List<Subject> online = List.of(new Subject("V", vId));

    @BeforeEach
    void setUp() {
        model = new DecorationModel<>(new Env<>() {
            public State stateOf(String entry) { return states.getOrDefault(entry, State.NORMAL); }
            public boolean tabDecorated(UUID subject) { return tabDecorated.contains(subject); }
            public String privateTeam(String entry) { return "vanishpp." + entry; }
            public String tag(String real) { return "[V]" + (real == null ? "" : real); }
            public String decorate(String display, UUID subject) { return "[VANISHED]" + (display == null ? "V" : display); }
            public boolean sameParams(String a, String b) { return Objects.equals(a, b); }
        });
    }

    private TeamOp<String> op(String team, int method, String params, String... players) {
        return new TeamOp<>(team, method, params, List.of(players));
    }

    @Test
    void hiddenPlayersAreStrippedAndOnlyRealMembersAreRemoved() {
        model.onTeamPacket(op("red", ADD, "R-", "V", "A"));
        states.put("V", State.HIDDEN);
        assertNull(model.onTeamPacket(op("blue", JOIN, null, "V")), "a hidden player's team change must not reach the client");
        TeamOp<String> mixed = model.onTeamPacket(op("blue", JOIN, null, "V", "A"));
        assertEquals(List.of("A"), mixed.players(), "other names in the same packet still pass");
        assertNull(model.onTeamPacket(op("blue", LEAVE, null, "V")), "nothing about a hidden player");
        // V is no longer on blue on the server, A is; a leave for V must never reach a client that has V on red.
        states.put("V", State.NORMAL);
        assertNull(model.onTeamPacket(op("blue", LEAVE, null, "V")), "a leave for someone the client doesn't have on that team would crash it");
        assertEquals("red", model.actualTeam("V"));
    }

    @Test
    void afterBeingHiddenTheClientGetsTheRealTeamBack() {
        model.onTeamPacket(op("red", ADD, "R-", "V"));
        model.onTeamPacket(op("blue", ADD, "B-"));
        states.put("V", State.HIDDEN);
        model.onTeamPacket(op("blue", JOIN, null, "V"));
        assertTrue(model.refresh(online).isEmpty(), "nothing is sent while the player is hidden");
        states.put("V", State.NORMAL);
        assertEquals(List.of(op("blue", JOIN, null, "V")), model.refresh(online));
        states.put("V", State.HIDDEN);
        model.onTeamPacket(op("blue", LEAVE, null, "V"));
        states.put("V", State.NORMAL);
        assertEquals(List.of(op("blue", LEAVE, null, "V")), model.refresh(online), "a team left while hidden is left on the client too");
        assertTrue(model.refresh(online).isEmpty(), "and only once");
    }

    @Test
    void taggedPlayersGetAPrivateCopyOfTheirTeamThatFollowsChanges() {
        model.onTeamPacket(op("red", ADD, "R-", "V"));
        states.put("V", State.TAGGED);
        assertEquals(List.of(op("vanishpp.V", ADD, "[V]R-", "V")), model.refresh(online), "[V] goes in front of the real prefix");
        assertTrue(model.refresh(online).isEmpty());
        assertNull(model.onTeamPacket(op("blue", JOIN, null, "V")), "moving the player back would lose the [V]");
        assertTrue(model.takeDirty(), "the real team changed, so the copy must be refreshed");
        assertEquals(List.of(op("vanishpp.V", CHANGE, "[V]")), model.refresh(online), "blue's parameters are not known yet");
        assertEquals("blue", model.onTeamPacket(op("blue", CHANGE, "B-")).team());
        assertTrue(model.takeDirty());
        assertEquals(List.of(op("vanishpp.V", CHANGE, "[V]B-")), model.refresh(online));
        assertNull(model.onTeamPacket(op("blue", LEAVE, null, "V")));
        assertEquals(List.of(op("vanishpp.V", CHANGE, "[V]")), model.refresh(online), "no real team: just the tag");
    }

    @Test
    void untaggingRemovesTheCopyAndRestoresTheRealTeam() {
        model.onTeamPacket(op("red", ADD, "R-", "V"));
        states.put("V", State.TAGGED);
        model.refresh(online);
        states.put("V", State.NORMAL);
        assertEquals(List.of(op("vanishpp.V", REMOVE, null), op("red", JOIN, null, "V")), model.refresh(online));
        states.put("V", State.TAGGED);
        model.refresh(online);
        states.put("V", State.HIDDEN);
        assertEquals(List.of(op("vanishpp.V", REMOVE, null)), model.refresh(online),
                "losing the permission removes the tag but tells nothing about the real team");
    }

    @Test
    void privateTeamsOfPlayersWhoLeftAreRemoved() {
        states.put("V", State.TAGGED);
        model.refresh(online);
        assertEquals(List.of(op("vanishpp.V", REMOVE, null)), model.refresh(List.of()));
    }

    @Test
    void tabNamesGetThePrefixInFrontOfWhateverTheServerSends() {
        model.onListed(vId);
        assertEquals("Custom", model.onDisplay(vId, "Custom"));
        tabDecorated.add(vId);
        assertEquals(List.of(new DisplayOp<>(vId, "[VANISHED]Custom")), model.refresh(online));
        assertEquals("[VANISHED]Other", model.onDisplay(vId, "Other"), "a new name from another plugin keeps the prefix");
        assertTrue(model.refresh(online).isEmpty());
        tabDecorated.remove(vId);
        assertEquals(List.of(new DisplayOp<>(vId, "Other")), model.refresh(online), "the plugin's name comes back unchanged");
    }

    @Test
    void tabNamesAreOnlySentForPlayersTheClientHasInItsList() {
        tabDecorated.add(vId);
        assertTrue(model.refresh(online).isEmpty());
        model.onListed(vId);
        assertEquals(List.of(new DisplayOp<>(vId, "[VANISHED]V")), model.refresh(online));
        model.onUnlisted(List.of(vId));
        assertTrue(model.refresh(online).isEmpty());
    }

    @Test
    void clearUndoesEverything() {
        model.onTeamPacket(op("red", ADD, "R-", "V"));
        model.onListed(vId);
        model.onDisplay(vId, "Custom");
        states.put("V", State.TAGGED);
        tabDecorated.add(vId);
        model.refresh(online);
        assertEquals(List.of(op("vanishpp.V", REMOVE, null), op("red", JOIN, null, "V"), new DisplayOp<>(vId, "Custom")), model.clear());
    }
}
