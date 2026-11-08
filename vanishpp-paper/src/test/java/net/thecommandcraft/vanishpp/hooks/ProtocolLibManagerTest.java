package net.thecommandcraft.vanishpp.hooks;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure sleep-status display-rewrite logic in {@link ProtocolLibManager}.
 *
 * <p>Issue #2: a vanished player is counted in both the numerator (via setSleepingIgnored /
 * fauxSleeping) and the denominator (via activePlayers) of the vanilla {@code sleep.players_sleeping}
 * action-bar message. To show the correct count for a world that includes vanished players, both
 * numbers are reduced by the vanished count.
 */
class ProtocolLibManagerTest {

    @Test
    void noVanishedPlayers_leavesMessageUntouched() {
        // 3 online (A,B,C), B sleeping, nobody vanished -> vanilla "1/3" is already correct.
        assertNull(ProtocolLibManager.rewriteSleepingArgs(1, 3, 0),
                "no vanished players means nothing to rewrite");
    }

    @Test
    void oneVanishedSleepingPlayer_showsRealCount() {
        // 3 online (A vanished+sleeping-ignored, B sleeping, C awake): server reports 2/3.
        // The vanished player is counted in both numerator and denominator -> correct view is 1/2.
        int[] r = ProtocolLibManager.rewriteSleepingArgs(2, 3, 1);
        assertNotNull(r);
        assertArrayEquals(new int[]{1, 2}, r, "subtract the vanished player from both counts");
    }

    @Test
    void numeratorClampsToOne_neverDropsBelowOne() {
        // One real sleeper plus several vanished sleepers must never show 0/...
        int[] r = ProtocolLibManager.rewriteSleepingArgs(3, 4, 3);
        assertNotNull(r);
        assertArrayEquals(new int[]{1, 1}, r, "numerator clamps at 1");
    }

    @Test
    void denominatorClampsToOne_neverDropsBelowOne() {
        // Many vanished players can't drive the denominator below 1.
        int[] r = ProtocolLibManager.rewriteSleepingArgs(2, 5, 4);
        assertNotNull(r);
        assertArrayEquals(new int[]{1, 1}, r, "denominator clamps at 1");
    }

    @Test
    void enoughSleeping_stillCorrects() {
        // 4 online (one vanished), all 4 reported sleeping -> server shows 4/4. The vanished player
        // shouldn't count at all -> corrected view is 3/3 (three real players asleep out of three).
        int[] r = ProtocolLibManager.rewriteSleepingArgs(4, 4, 1);
        assertNotNull(r);
        assertArrayEquals(new int[]{3, 3}, r, "drop the vanished player from both counts");
    }
}