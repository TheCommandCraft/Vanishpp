package net.thecommandcraft.vanishpp.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pure decision logic for scrubbing {@code PLAYER_INFO}/{@code PLAYER_INFO_UPDATE} packets,
 * extracted out of {@link ProtocolLibManager} so it can be unit-tested without a real
 * ProtocolLib {@code PacketEvent}/{@code PlayerInfoData}.
 *
 * <p>This is a backstop against Bukkit's {@code hidePlayer()} being a one-shot removal at the
 * moment visibility changes: it does not stop a later {@code PLAYER_INFO} packet - from
 * another plugin re-syncing its own tab entries, or from any packet path that bypasses
 * Bukkit's per-viewer entity tracking entirely - from re-including a vanished player's
 * tab-list entry for a non-seer observer.
 */
final class VanishPlayerInfoPolicy {

    /** Result of filtering one packet's entry list for one observer. */
    record FilterResult<T>(List<T> kept, boolean changed) {
        /** Non-staff clients must never learn a vanished player's entry exists at all - if
         *  every entry was removed, the whole packet should be cancelled rather than sent
         *  with an empty list. */
        boolean cancel() {
            return changed && kept.isEmpty();
        }
    }

    private VanishPlayerInfoPolicy() {
    }

    /**
     * Decision for what to do when a {@code PLAYER_INFO} packet can't be fully parsed/handled.
     *
     * <p>This is deliberately fail-open (send the packet untouched) rather than fail-closed
     * (cancel it), because cancelling a {@code PLAYER_INFO} packet destroys every player's
     * skin and tab entry for the observer — on servers where ProtocolLib cannot parse the
     * 1.21.x packet shape (e.g. Purpur 1.21.11, which ProtocolLib logs as untested) this throws
     * on essentially every join, so blanket fail-closed would blank everyone's display. Bukkit's
     * own {@code hidePlayer()}/{@code showPlayer()} is the real primary vanish layer; this scrub
     * is only a backstop, so leaving the packet alone on a parse error is the safer failure
     * direction.
     *
     * @param removedVanished whether we had already identified (and were about to remove) at least
     *                        one vanished entry before the failure — i.e. the throw happened after
     *                        parsing rather than before we knew anything. This is tracked only for
     *                        logging/telemetry so a future per-observer "known" tracking (mirroring
     *                        {@link VanishTeamPacketPolicy}) can be added at the right spot; the
     *                        decision itself is fail-open either way.
     */
    static boolean failOpenOnParseFailure(boolean removedVanished) {
        // Fail open regardless of how far parsing got. Even when a vanished entry was already
        // identified, cancelling the whole packet is strictly worse than the leak it guards
        // against (it blanks every non-seer's tab list and skin). The flag is accepted so the
        // call site can log that a vanished entry may have slipped through on this path.
        return true;
    }

    /**
     * @param entries           the packet's entries, in order (never null)
     * @param idExtractor       extracts the profile UUID from one entry (may return null for
     *                          an entry with no identifiable profile - such entries are kept)
     * @param currentlyVanished predicate: is this UUID currently vanished?
     */
    static <T> FilterResult<T> filter(List<T> entries, Function<T, UUID> idExtractor,
                                       Predicate<UUID> currentlyVanished) {
        List<T> kept = new ArrayList<>();
        boolean changed = false;
        for (T entry : entries) {
            UUID id = idExtractor.apply(entry);
            if (id != null && currentlyVanished.test(id)) {
                changed = true;
            } else {
                kept.add(entry);
            }
        }
        return new FilterResult<>(kept, changed);
    }
}
