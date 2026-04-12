package net.thecommandcraft.vanishpp.hooks;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.entity.Player;

/**
 * Integrates with WorldGuard to support three custom region flags:
 * <ul>
 *   <li>{@code vanishpp-deny-vanish}  — prevents vanishing inside this region</li>
 *   <li>{@code vanishpp-force-vanish} — auto-vanishes players who enter this region</li>
 *   <li>{@code vanishpp-deny-unvanish} — prevents unvanishing inside this region</li>
 * </ul>
 *
 * <p>Flags must be registered <em>before</em> WorldGuard loads regions (i.e. during plugin enable,
 * which runs before world loads in a typical server startup). Vanish++ satisfies this because
 * {@code softdepend} on WorldGuard means WG loads first but flags are registered in our enable.
 */
public class WorldGuardHook {

    public static StateFlag DENY_VANISH;
    public static StateFlag FORCE_VANISH;
    public static StateFlag DENY_UNVANISH;

    private final Vanishpp plugin;

    public WorldGuardHook(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /** Register flags with WorldGuard's flag registry. Call during plugin enable. */
    public void load() {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();

        DENY_VANISH   = registerOrFetch(registry, "vanishpp-deny-vanish",   StateFlag.State.DENY);
        FORCE_VANISH  = registerOrFetch(registry, "vanishpp-force-vanish",  StateFlag.State.DENY);
        DENY_UNVANISH = registerOrFetch(registry, "vanishpp-deny-unvanish", StateFlag.State.DENY);
    }

    private StateFlag registerOrFetch(FlagRegistry registry, String name, StateFlag.State def) {
        try {
            StateFlag flag = new StateFlag(name, def == StateFlag.State.ALLOW);
            registry.register(flag);
            return flag;
        } catch (FlagConflictException e) {
            Flag<?> existing = registry.get(name);
            if (existing instanceof StateFlag sf) return sf;
            throw new RuntimeException("WorldGuard flag conflict for '" + name + "'", e);
        }
    }

    // ── Runtime checks ────────────────────────────────────────────────────────

    /** Returns true if the player is inside a deny-vanish region. */
    public boolean isDenyVanish(Player player) {
        return testFlag(player, DENY_VANISH);
    }

    /** Returns true if the player is inside a force-vanish region. */
    public boolean isForceVanish(Player player) {
        return testFlag(player, FORCE_VANISH);
    }

    /** Returns true if the player is inside a deny-unvanish region. */
    public boolean isDenyUnvanish(Player player) {
        return testFlag(player, DENY_UNVANISH);
    }

    private boolean testFlag(Player player, StateFlag flag) {
        if (flag == null) return false;
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            com.sk89q.worldedit.bukkit.BukkitAdapter adapter = null;
            com.sk89q.worldedit.util.Location loc =
                    com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getLocation());
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            return query.testState(loc, localPlayer, flag);
        } catch (Throwable e) {
            return false;
        }
    }
}
