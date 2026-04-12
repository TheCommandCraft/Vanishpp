package net.thecommandcraft.vanishpp.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a player's vanish state has changed (post-vanish or post-unvanish).
 * Unlike {@link VanishEvent} / {@link UnvanishEvent}, this event is <em>not</em> cancellable
 * — the state change has already happened.
 */
public class VanishStateChangeEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean vanished;

    public VanishStateChangeEvent(@NotNull Player who, boolean vanished) {
        super(who);
        this.vanished = vanished;
    }

    /** Returns {@code true} if the player is now vanished, {@code false} if now visible. */
    public boolean isVanished() { return vanished; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
