package net.thecommandcraft.vanishpp.common.state;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/** Orders accepted writes and lets lifecycle shutdown drain an unstarted worker. */
public final class OrderedWrites implements AutoCloseable {
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final Consumer<Runnable> executor;
    private final Consumer<RuntimeException> errors;
    private boolean scheduled;
    private Thread owner;
    private boolean closed;

    public OrderedWrites(Consumer<Runnable> executor, Consumer<RuntimeException> errors) {
        this.executor = executor;
        this.errors = errors;
    }

    public void submit(Runnable write) {
        boolean start;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Storage writes are closed");
            pending.add(write);
            start = !scheduled;
            scheduled = true;
        }
        if (start) executor.accept(this::drain);
    }

    @Override
    public void close() {
        synchronized (this) { closed = true; }
        drain();
    }

    public void drain() {
        synchronized (this) {
            if (owner == Thread.currentThread()) return;
            while (owner != null) {
                try { wait(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while draining storage writes", e);
                }
            }
            owner = Thread.currentThread();
        }
        try {
            while (true) {
                Runnable write;
                synchronized (this) {
                    write = pending.poll();
                    if (write == null) return;
                }
                try { write.run(); }
                catch (RuntimeException e) { errors.accept(e); }
            }
        } finally {
            boolean restart;
            synchronized (this) {
                owner = null;
                scheduled = !pending.isEmpty();
                restart = scheduled;
                notifyAll();
            }
            if (restart) executor.accept(this::drain);
        }
    }
}
