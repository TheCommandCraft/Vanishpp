package net.thecommandcraft.vanishpp.api;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.storage.VanishStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Public API for Vanish++.
 *
 * <p>Obtain an instance via {@link #get()}.  Example usage from another plugin:
 * <pre>{@code
 *   if (Bukkit.getPluginManager().isPluginEnabled("Vanishpp")) {
 *       VanishAPI api = VanishAPI.get();
 *       boolean hidden = api.isVanished(player.getUniqueId());
 *   }
 * }</pre>
 */
public final class VanishAPI {

    private static VanishAPI instance;
    private final Vanishpp plugin;

    VanishAPI(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns the singleton {@link VanishAPI} instance.
     *
     * @throws IllegalStateException if Vanish++ is not loaded
     */
    @NotNull
    public static VanishAPI get() {
        if (instance == null) {
            Vanishpp p = (Vanishpp) Bukkit.getPluginManager().getPlugin("Vanishpp");
            if (p == null) throw new IllegalStateException("Vanish++ is not loaded");
            instance = new VanishAPI(p);
        }
        return instance;
    }

    /** Called internally on plugin enable to prime the singleton. */
    public static void init(Vanishpp plugin) {
        instance = new VanishAPI(plugin);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Returns {@code true} if the given player is currently vanished. */
    public boolean isVanished(@NotNull UUID uuid) {
        return plugin.isVanished(uuid);
    }

    /** Returns {@code true} if the given player is currently vanished. */
    public boolean isVanished(@NotNull Player player) {
        return plugin.isVanished(player);
    }

    /**
     * Returns the vanish level of a player (used for layered-permissions mode).
     * Returns 1 if the player has no stored level.
     * With SQL storage, call this storage query asynchronously.
     */
    public int getVanishLevel(@NotNull UUID uuid) {
        return plugin.getStorageProvider().getVanishLevel(uuid);
    }

    /**
     * Returns a snapshot of all current rule values for a player.
     * Keys are the rule constants defined in {@code RuleManager}.
     * With SQL storage, call this storage query asynchronously.
     */
    @NotNull
    public Map<String, Object> getRules(@NotNull UUID uuid) {
        return plugin.getStorageProvider().getRules(uuid);
    }

    /**
     * Returns the current vanish reason for the player's active session,
     * or {@code null} if no reason was provided.
     */
    @Nullable
    public String getVanishReason(@NotNull UUID uuid) {
        return plugin.getVanishReason(uuid);
    }

    /**
     * Retained for compatibility. Timed vanish is not implemented; always returns {@code -1}.
     */
    public long getTimedVanishRemaining(@NotNull UUID uuid) {
        return plugin.getTimedRemaining(uuid);
    }

    /**
     * Returns a stats snapshot for a player.
     * Values are read from storage and may not reflect a currently-active session.
     * With SQL storage, call this storage query asynchronously.
     */
    @NotNull
    public VanishStats getStats(@NotNull UUID uuid) {
        return plugin.getStorageProvider().getStats(uuid);
    }

    /**
     * Returns {@code true} if the observer can currently see the subject
     * (either subject is not vanished, or observer has see-level permission).
     */
    public boolean canSee(@NotNull Player observer, @NotNull Player subject) {
        return plugin.getPermissionManager().canSee(observer, subject);
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Vanishes the given player as if they ran {@code /vanish}.
     * Fires {@link net.thecommandcraft.vanishpp.api.events.VanishEvent} — can be cancelled by other plugins.
     *
     * <p>Dispatched to the player's owning scheduler. Off-owner calls may return before the change completes.
     * Cancellable events guard local requests; proxy/Redis reconciliation and persisted login restoration
     * are authoritative and cannot be vetoed. Post-events fire only for actual state changes.
     */
    public void vanish(@NotNull Player player) {
        plugin.vanishPlayer(player, player, null);
    }

    /**
     * Vanishes the given player with a reason.
     * Dispatched to the player's owning scheduler, with the same event contract as {@link #vanish(Player)}.
     */
    public void vanish(@NotNull Player player, @Nullable String reason) {
        plugin.vanishPlayer(player, player, reason);
    }

    /**
     * Unvanishes the given player as if they ran {@code /vanish} again.
     * Fires {@link net.thecommandcraft.vanishpp.api.events.UnvanishEvent} — can be cancelled by other plugins.
     *
     * <p>Dispatched to the player's owning scheduler, with the same event contract as {@link #vanish(Player)}.
     */
    public void unvanish(@NotNull Player player) {
        plugin.unvanishPlayer(player, player);
    }

    /**
     * Sets the vanish reason for a player's current session.
     * Has no effect if the player is not currently vanished.
     */
    public void setVanishReason(@NotNull UUID uuid, @Nullable String reason) {
        plugin.setVanishReason(uuid, reason);
    }
}
