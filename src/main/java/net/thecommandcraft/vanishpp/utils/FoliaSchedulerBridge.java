package net.thecommandcraft.vanishpp.utils;

import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

public class FoliaSchedulerBridge implements VanishScheduler {

    private final Vanishpp plugin;

    public FoliaSchedulerBridge(Vanishpp plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runGlobal(Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }

    @Override
    public void runLaterGlobal(Runnable runnable, long ticks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), ticks);
    }

    @Override
    public void runTimerGlobal(Runnable runnable, long delay, long period) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> runnable.run(), delay, period);
    }

    @Override
    public void runEntity(Entity entity, Runnable runnable, Runnable retired) {
        entity.getScheduler().run(plugin, task -> runnable.run(), retired);
    }

    @Override
    public void runAsync(Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    @Override
    public void cancelAllTasks() {
        // Folia doesn't have a direct "cancel all" for a plugin in one place as easily
        // as Bukkit
        // but it handles task cleanup on plugin disable automatically.
    }
}
