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
        // Periodic sweep clears any mob targets pointing at vanished players whose
        // mob_targeting rule is OFF. Belt-and-suspenders on top of EntityTargetEvent
        // and setInvisible(true). No custom MobGoals are injected — doing so breaks
        // vanilla combat goals (ZombieAttackGoal etc.) for non-vanished players.
        plugin.getVanishScheduler().runTimerGlobal(this::sweepMobTargets, 1L, 5L);
    }

    private void sweepMobTargets() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.isVanished(p)) continue;
            if (plugin.getRuleManager().getRule(p, RuleManager.MOB_TARGETING)) continue;

            for (Entity entity : p.getNearbyEntities(100, 100, 100)) {
                if (!(entity instanceof Mob mob)) continue;
                if (!p.equals(mob.getTarget())) continue;
                mob.setTarget(null);
                try { mob.getPathfinder().stopPathfinding(); } catch (Throwable ignored) {}
            }
        }
    }
}
