package net.thecommandcraft.vanishpp.utils;

import org.bukkit.entity.Entity;

public interface VanishScheduler {

    void runGlobal(Runnable runnable);

    void runLaterGlobal(Runnable runnable, long ticks);

    void runTimerGlobal(Runnable runnable, long delay, long period);

    void runEntity(Entity entity, Runnable runnable, Runnable retired);

    void runAsync(Runnable runnable);

    void cancelAllTasks();
}
