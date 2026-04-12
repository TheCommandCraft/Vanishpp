package net.thecommandcraft.vanishpp.api.events;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired just before a player is unvanished. Can be cancelled to keep the player vanished.
 */
public class UnvanishEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender executor;
    private boolean cancelled = false;

    public UnvanishEvent(@NotNull Player who, @NotNull CommandSender executor) {
        super(who);
        this.executor = executor;
    }

    /** The command sender who triggered this unvanish (may equal the player). */
    public CommandSender getExecutor() { return executor; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
