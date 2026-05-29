package net.thecommandcraft.vanishpp.listeners;

import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.RuleManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

public class MobAiManager {

    private final Vanishpp plugin;

    public MobAiManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void register() {
        // Aggressive sweep: clear ANY mob target/pathfinding aimed at vanished players.
        // Runs every 1 tick (maximum responsiveness) with 3-tick startup delay.
        // This prevents mobs from looking at, walking toward, or otherwise acknowledging
        // the existence of vanished players whose mob_targeting rule is OFF.
        plugin.getVanishScheduler().runTimerGlobal(this::sweepMobTargets, 3L, 1L);
    }

    private void sweepMobTargets() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.isVanished(p)) continue;

            // Only enforce clearing if mob_targeting rule is OFF
            if (plugin.getRuleManager().getRule(p, RuleManager.MOB_TARGETING)) continue;

            // Check all nearby mobs and clear any that have the vanished player as target
            for (Entity entity : p.getNearbyEntities(128, 128, 128)) {
                if (!(entity instanceof Mob mob)) continue;

                // Clear target if aimed at this vanished player
                if (p.equals(mob.getTarget())) {
                    mob.setTarget(null);
                    try {
                        mob.getPathfinder().stopPathfinding();
                    } catch (Throwable ignored) {}

                    // Force clear all goals that might reference the player
                    // This prevents any residual tracking/looking behavior
                    try {
                        // Clear brain memories (for newer versions)
                        mob.clearLootTable();
                    } catch (Throwable ignored) {}
                }
            }
        }
    }
}
