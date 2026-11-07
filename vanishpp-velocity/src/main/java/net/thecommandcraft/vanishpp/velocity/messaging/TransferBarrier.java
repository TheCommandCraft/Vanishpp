package net.thecommandcraft.vanishpp.velocity.messaging;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.thecommandcraft.vanishpp.common.protocol.*;
import net.thecommandcraft.vanishpp.velocity.VanishppVelocity;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Keeps the source carrier attached until its accepted state has reached the proxy. */
public final class TransferBarrier implements AutoCloseable {
    private static final class Pending {
        final String id = UUID.randomUUID().toString();
        final ServerPreConnectEvent event;
        final Continuation continuation;
        final ServerConnection source;
        ScheduledTask timeout;
        Pending(ServerPreConnectEvent event, Continuation continuation, ServerConnection source) {
            this.event = event; this.continuation = continuation; this.source = source;
        }
    }
    private final VanishppVelocity plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private boolean closed;

    public TransferBarrier(VanishppVelocity plugin) { this.plugin = plugin; }

    public synchronized void begin(ServerPreConnectEvent event, Continuation continuation) {
        ServerConnection source = event.getPlayer().getCurrentServer().orElse(null);
        if (closed || !event.getResult().isAllowed() || source == null
                || event.getResult().getServer().filter(source.getServer()::equals).isPresent()) {
            continuation.resume(); return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        Pending previous = pending.remove(uuid);
        if (previous != null) finish(uuid, previous, false);
        Pending transfer = new Pending(event, continuation, source);
        pending.put(uuid, transfer);
        transfer.timeout = plugin.getProxy().getScheduler().buildTask(plugin, () -> timeout(uuid, transfer))
                .delay(5, TimeUnit.SECONDS).schedule();
        send(uuid, transfer, VppMessage.TRANSFER_PREPARE);
    }

    public synchronized void complete(ServerConnection source, JsonObject json, BooleanSupplier acceptDecision) {
        if (!json.has("uuid") || !json.has("requestId")) return;
        UUID uuid = UUID.fromString(json.get("uuid").getAsString());
        Pending transfer = pending.get(uuid);
        if (transfer == null || !transfer.id.equals(json.get("requestId").getAsString()) || !transfer.source.equals(source)
                || transfer.event.getPlayer().getCurrentServer().filter(source::equals).isEmpty()) return;
        if (!transfer.event.getResult().isAllowed()) {
            pending.remove(uuid); finish(uuid, transfer, false); return;
        }
        // Persistence failure leaves the barrier pending; its timeout safely denies the switch.
        if (!acceptDecision.getAsBoolean()) return;
        pending.remove(uuid);
        finish(uuid, transfer, true);
    }

    private synchronized void timeout(UUID uuid, Pending transfer) {
        if (pending.remove(uuid, transfer)) finish(uuid, transfer, false);
    }

    private void finish(UUID uuid, Pending transfer, boolean accepted) {
        if (transfer.timeout != null) transfer.timeout.cancel();
        if (!accepted) {
            transfer.event.setResult(ServerPreConnectEvent.ServerResult.denied());
            send(uuid, transfer, VppMessage.TRANSFER_CANCEL);
        }
        transfer.continuation.resume();
    }

    private void send(UUID uuid, Pending transfer, VppMessage type) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid.toString()); json.addProperty("requestId", transfer.id);
        transfer.source.sendPluginMessage(MinecraftChannelIdentifier.from(VppChannel.CHANNEL), VppPacket.encode(type, json.toString()));
    }

    @Override public synchronized void close() {
        closed = true;
        pending.forEach((uuid, transfer) -> finish(uuid, transfer, false));
        pending.clear();
    }
}
