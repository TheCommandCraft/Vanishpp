package net.thecommandcraft.vanishpp.listeners;

import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
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
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            Bukkit.getScheduler().runTask(plugin, () -> injectSafeAi(mob));
        }
    }

    private void injectSafeAi(Mob mob) {
        if (!mob.isValid()) return;
        if (Bukkit.getMobGoals().hasGoal(mob, VanillaGoal.LOOK_AT_PLAYER)) {
            Bukkit.getMobGoals().removeGoal(mob, VanillaGoal.LOOK_AT_PLAYER);
            Bukkit.getMobGoals().addGoal(mob, 2, new SafeLookAtPlayerGoal(plugin, mob));
        }
    }
}