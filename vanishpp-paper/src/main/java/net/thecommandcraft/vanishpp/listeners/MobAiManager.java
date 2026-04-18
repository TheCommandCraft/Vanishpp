package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.utils.SafeLookAtPlayerGoal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

public class MobAiManager implements Listener {

    private final Vanishpp plugin;

    public MobAiManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (var world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob mob) {
                    injectSafeAi(mob);
                }
            }
        }
        // Periodic sweep: every 5 ticks (0.25s) clear any mob targets pointing at
        // vanished players who have mob_targeting OFF. Aggressive safety net to ensure
        // mobs cannot acquire/maintain targets on vanished players.
        plugin.getVanishScheduler().runTimerGlobal(this::sweepMobTargets, 1L, 5L);
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            plugin.getVanishScheduler().runGlobal(() -> injectSafeAi(mob));
        }
    }

    private void sweepMobTargets() {
        for (var world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob mob)) continue;
                if (!(mob.getTarget() instanceof Player p)) continue;
                if (!plugin.isVanished(p)) continue;
                if (plugin.getRuleManager().getRule(p, RuleManager.MOB_TARGETING)) continue;

                // Forcefully clear target - no mercy
                mob.setTarget(null);
                try {
                    mob.getPathfinder().stopPathfinding();
                } catch (Throwable ignored) {}
            }
        }
    }

    private void injectSafeAi(Mob mob) {
        // SafeLookAtPlayerGoal injection disabled - rely on invisibility + EntityTargetEvent blocking
        // which are more reliable than goal manipulation
    }
}