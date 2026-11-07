package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

public class BukkitSchedulerBridge implements VanishScheduler {

    private final Vanishpp plugin;

    public BukkitSchedulerBridge(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runGlobal(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public void runLaterGlobal(Runnable runnable, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, runnable, ticks);
    }

    @Override
    public void runTimerGlobal(Runnable runnable, long delay, long period) {
        Bukkit.getScheduler().runTaskTimer(plugin, runnable, delay, period);
    }

    @Override
    public Runnable startLater(Runnable runnable, long ticks) {
        return Bukkit.getScheduler().runTaskLater(plugin, runnable, Math.max(1L, ticks))::cancel;
    }

    @Override
    public Runnable startTimer(Runnable runnable, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, runnable, delay, period)::cancel;
    }

    @Override
    public void runEntity(Entity entity, Runnable runnable, Runnable retired) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public void runEntityNow(Entity entity, Runnable runnable) {
        if (Bukkit.isPrimaryThread()) runnable.run();
        else runEntity(entity, runnable, null);
    }

    @Override
    public void runAsync(Runnable runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    @Override
    public void cancelAllTasks() {
        Bukkit.getScheduler().cancelTasks(plugin);
    }
}
