package net.thecommandcraft.vanishpp.utils;

import org.bukkit.entity.Entity;

public interface VanishScheduler {

    void runGlobal(Runnable runnable);

    void runLaterGlobal(Runnable runnable, long ticks);

    void runTimerGlobal(Runnable runnable, long delay, long period);

    default Runnable startLater(Runnable runnable, long ticks) {
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        runLaterGlobal(() -> { if (active.getAndSet(false)) runnable.run(); }, ticks);
        return () -> active.set(false);
    }

    default Runnable startTimer(Runnable runnable, long delay, long period) {
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        runTimerGlobal(() -> { if (active.get()) runnable.run(); }, delay, period);
        return () -> active.set(false);
    }

    void runEntity(Entity entity, Runnable runnable, Runnable retired);

    /** Like runEntity, but runs right away when the current thread already owns the entity. */
    void runEntityNow(Entity entity, Runnable runnable);

    void runAsync(Runnable runnable);

    void cancelAllTasks();
}
