package net.thecommandcraft.vanishpp;

import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Bukkit;
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
        // Process existing entities
        for (var world : Bukkit.getWorlds()) {
            for (var entity : world.getEntities()) {
                if (entity instanceof Mob mob) {
                    injectSafeAi(mob);
                }
            }
        }
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            // Delay slightly to ensure vanilla goals are initialized
            Bukkit.getScheduler().runTask(plugin, () -> injectSafeAi(mob));
        }
    }

    private void injectSafeAi(Mob mob) {
        if (!mob.isValid()) return;

        // Remove the vanilla goal that ignores invisibility at close range
        if (Bukkit.getMobGoals().hasGoal(mob, VanillaGoal.LOOK_AT_PLAYER)) {
            Bukkit.getMobGoals().removeGoal(mob, VanillaGoal.LOOK_AT_PLAYER);

            // Add our smart goal that checks for vanish metadata/status
            // Priority 2 (matches vanilla look priority usually)
            Bukkit.getMobGoals().addGoal(mob, 2, new SafeLookAtPlayerGoal(plugin, mob));
        }
    }
}