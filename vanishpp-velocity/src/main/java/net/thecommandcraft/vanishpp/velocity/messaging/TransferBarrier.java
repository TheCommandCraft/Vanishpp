package net.thecommandcraft.vanishpp.velocity.messaging;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.thecommandcraft.vanishpp.common.protocol.*;
import net.thecommandcraft.vanishpp.velocity.VanishppVelocity;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Keeps the source fenced through the actual connection outcome, including later event handlers. */
public final class TransferBarrier implements AutoCloseable {
    private static final class Pending {
        final String id = UUID.randomUUID().toString();
        final ServerPreConnectEvent event;
        final Continuation continuation;
        final ServerConnection source;
        final AtomicBoolean resumed = new AtomicBoolean();
        ScheduledTask timeout;
        ScheduledTask outcomeCheck;
        boolean saving;
        boolean accepted;
        Pending(ServerPreConnectEvent event, Continuation continuation, ServerConnection source) {
            this.event = event; this.continuation = continuation; this.source = source;
        }
        void resume() { if (resumed.compareAndSet(false, true)) continuation.resume(); }
        void cancelTasks() {
            if (timeout != null) timeout.cancel();
            if (outcomeCheck != null) outcomeCheck.cancel();
        }
    }
    private final VanishppVelocity plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private boolean closed;

    public TransferBarrier(VanishppVelocity plugin) { this.plugin = plugin; }

    public synchronized void begin(ServerPreConnectEvent event, Continuation continuation) {
        ServerConnection source = event.getPlayer().getCurrentServer().orElse(null);
        if (source == null && event.getPreviousServer() != null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            continuation.resume(); return;
        }
        if (closed || !event.getResult().isAllowed() || source == null
                || event.getResult().getServer().filter(source.getServer()::equals).isPresent()) {
            continuation.resume(); return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        Pending previous = pending.remove(uuid);
        if (previous != null) deny(uuid, previous);
        Pending transfer = new Pending(event, continuation, source);
        pending.put(uuid, transfer);
        transfer.timeout = plugin.getProxy().getScheduler().buildTask(plugin, () -> timeout(uuid, transfer))
                .delay(5, TimeUnit.SECONDS).schedule();
        send(uuid, transfer, VppMessage.TRANSFER_PREPARE);
    }

    public void complete(ServerConnection source, JsonObject json, BooleanSupplier acceptDecision) {
        if (!json.has("uuid") || !json.has("requestId")) return;
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());
        Pending transfer;
        synchronized (this) {
            transfer = pending.get(uuid);
            if (transfer == null || transfer.saving || transfer.accepted
                    || !transfer.id.equals(json.get("requestId").getAsString()) || !transfer.source.equals(source)
                    || transfer.event.getPlayer().getCurrentServer().filter(source::equals).isEmpty()) return;
            transfer.saving = true;
        }
        // Storage may block. The independent denial timer must still be able to invalidate this attempt.
        boolean saved;
        try { saved = acceptDecision.getAsBoolean(); }
        catch (RuntimeException failure) {
            plugin.getLogger().warn("Transfer state could not be saved for {}", uuid, failure);
            saved = false;
        }
        synchronized (this) {
            if (pending.get(uuid) != transfer) return;
            transfer.saving = false;
            if (!saved) return;
            if (!transfer.event.getResult().isAllowed()) {
                pending.remove(uuid); deny(uuid, transfer); return;
            }
            transfer.accepted = true;
            if (transfer.timeout != null) transfer.timeout.cancel();
            transfer.outcomeCheck = plugin.getProxy().getScheduler().buildTask(plugin, () -> checkOutcome(uuid, transfer))
                    .delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
        }
        transfer.resume();
    }

    private void timeout(UUID uuid, Pending transfer) {
        synchronized (this) {
            if (transfer.accepted || !pending.remove(uuid, transfer)) return;
        }
        deny(uuid, transfer);
    }

    private void checkOutcome(UUID uuid, Pending transfer) {
        synchronized (this) {
            if (pending.get(uuid) != transfer) return;
            if (!transfer.event.getResult().isAllowed()) {
                pending.remove(uuid); deny(uuid, transfer);
            } else if (!transfer.event.getPlayer().isActive()) {
                pending.remove(uuid); transfer.cancelTasks();
            }
        }
    }

    public synchronized void connected(UUID uuid) {
        Pending transfer = pending.remove(uuid);
        if (transfer != null) transfer.cancelTasks(); // Source detach/quit releases the Paper fence.
    }

    public synchronized void failed(UUID uuid, RegisteredServer destination) {
        Pending transfer = pending.get(uuid);
        if (transfer != null && transfer.event.getResult().getServer().filter(destination::equals).isPresent()) {
            pending.remove(uuid); deny(uuid, transfer);
        }
    }

    private void deny(UUID uuid, Pending transfer) {
        transfer.cancelTasks();
        transfer.event.setResult(ServerPreConnectEvent.ServerResult.denied());
        send(uuid, transfer, VppMessage.TRANSFER_CANCEL);
        transfer.resume();
    }

    private void send(UUID uuid, Pending transfer, VppMessage type) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid.toString()); json.addProperty("requestId", transfer.id);
        try {
            transfer.source.sendPluginMessage(MinecraftChannelIdentifier.from(VppChannel.CHANNEL), VppPacket.encode(type, json.toString()));
        } catch (RuntimeException disconnected) { plugin.getLogger().debug("Source connection ended for {}", uuid); }
    }

    @Override public synchronized void close() {
        closed = true;
        pending.forEach((uuid, transfer) -> {
            if (transfer.accepted) transfer.cancelTasks();
            else deny(uuid, transfer);
        });
        pending.clear();
    }
}
