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
        // Periodic sweep: every 20 ticks clear any mob targets that are pointing at a
        // vanished player who has mob_targeting OFF. This is the last-resort safety net
        // for mobs whose AI re-acquires targets between event firings.
        plugin.getVanishScheduler().runTimerGlobal(this::sweepMobTargets, 1L, 20L);
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
                mob.setTarget(null);
                try { mob.getPathfinder().stopPathfinding(); } catch (Throwable ignored) {}
            }
        }
    }

    private void injectSafeAi(Mob mob) {
        if (!mob.isValid())
            return;

        // Replace LOOK_AT_PLAYER goal with our safe version
        if (Bukkit.getMobGoals().hasGoal(mob, VanillaGoal.LOOK_AT_PLAYER)) {
            Bukkit.getMobGoals().removeGoal(mob, VanillaGoal.LOOK_AT_PLAYER);
            Bukkit.getMobGoals().addGoal(mob, 2, new SafeLookAtPlayerGoal(plugin, mob));
        }
    }
}