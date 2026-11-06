package net.thecommandcraft.vanishpp.utils;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Best-effort conversation state for messaging plugins without a recipient event. */
public final class MessageRecipients {
    private final Map<UUID, UUID> replies = new ConcurrentHashMap<>();

    public void record(UUID sender, UUID recipient) {
        replies.put(sender, recipient);
        replies.put(recipient, sender);
    }

    public UUID reply(UUID sender) { return replies.get(sender); }

    public void forget(UUID player) {
        replies.remove(player);
        replies.values().removeIf(player::equals);
    }
}
