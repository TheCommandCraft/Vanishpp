package net.thecommandcraft.vanishpp.decor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * The [V] nametag and [VANISHED] tab prefix staff see on vanished players, done purely in the packets each viewer
 * receives, with or without ProtocolLib. The server's scoreboards, teams and tab names are never changed, so team
 * rules, other plugins and their prefixes keep working: Vanish++ only puts its prefix in front of whatever the viewer
 * would have seen. Players who can't see a vanished player also learn nothing about them from team packets.
 *
 * <p>Uses Paper's connection hook ({@code ChannelInitializeListenerHolder}) and the server's own packet classes. On
 * servers without them (Spigot) the decorations stay off and nothing is changed.
 */
public final class StaffDecorations {
    static final String HANDLER = "vanishpp_decorations";
    private static final Key LISTENER = Key.key("vanishpp", "decorations");

    private final Vanishpp plugin;
    private final NmsPackets nms;
    private final Map<Channel, Handler> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile boolean active;
    private volatile String nametagRaw, tabRaw;
    private volatile Component nametag = Component.empty(), tab = Component.empty();
    private final AtomicBoolean warned = new AtomicBoolean();

    private StaffDecorations(Vanishpp plugin, NmsPackets nms) {
        this.plugin = plugin;
        this.nms = nms;
    }

    /** Starts the decorations, or returns null (with one log line) where the server doesn't support them. */
    public static StaffDecorations start(Vanishpp plugin) {
        try {
            StaffDecorations d = new StaffDecorations(plugin, new NmsPackets());
            d.install();
            return d;
        } catch (Throwable t) {
            plugin.getLogger().info("Staff nametag and tab prefixes are off: this server has no Paper connection hook ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + "). Vanishing works as usual.");
            return null;
        }
    }

    private void install() throws ReflectiveOperationException {
        ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
        Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", false, cl);
        Class<?> listener = Class.forName("io.papermc.paper.network.ChannelInitializeListener", false, cl);
        Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (p, m, args) -> {
            if (m.getName().equals("afterInitChannel")) { inject((Channel) args[0]); return null; }
            if (m.getName().equals("equals")) return p == args[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return "Vanish++ decorations";
            return null;
        });
        holder.getMethod("addListener", Key.class, listener).invoke(null, LISTENER, proxy);
        active = true;
        // Players already online (plugin reload): hook into their connections now.
        for (Player player : Bukkit.getOnlinePlayers()) {
            Channel channel = nms.channelOf(player);
            if (channel != null) inject(channel);
        }
    }

    private void inject(Channel channel) {
        if (!active || handlers.containsKey(channel)) return;
        Handler handler = new Handler();
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER) != null || !channel.isOpen()) return;
                if (channel.pipeline().get("packet_handler") != null) channel.pipeline().addBefore("packet_handler", HANDLER, handler);
                else channel.pipeline().addLast(HANDLER, handler);
                handlers.put(channel, handler);
                channel.closeFuture().addListener(f -> handlers.remove(channel));
            } catch (RuntimeException e) {
                warn("could not hook into a connection", e);
            }
        });
    }

    /** Undoes every change on every client and unhooks (plugin disable). */
    public void stop() {
        active = false;
        try {
            Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", false,
                    Bukkit.getServer().getClass().getClassLoader());
            holder.getMethod("removeListener", Key.class).invoke(null, LISTENER);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        for (Map.Entry<Channel, Handler> e : handlers.entrySet()) {
            Handler h = e.getValue();
            h.send(h.model.clear());
            e.getKey().eventLoop().execute(() -> {
                if (e.getKey().pipeline().get(HANDLER) != null) e.getKey().pipeline().remove(HANDLER);
            });
        }
        handlers.clear();
    }

    /** Re-applies decorations for every viewer: after vanish changes, joins, permission or config changes. */
    public void refreshAll() {
        if (!active) return;
        reloadPrefixes();
        List<DecorationModel.Subject> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(new DecorationModel.Subject(p.getName(), p.getUniqueId()));
        for (Handler h : handlers.values()) {
            if (h.viewer == null) continue;
            h.send(h.model.refresh(online));
        }
    }

    /** Runs {@link #refreshAll()} on the next server tick, once, however often it is asked for. */
    public void requestRefresh() {
        if (!active || !refreshQueued.compareAndSet(false, true)) return;
        plugin.getVanishScheduler().runGlobal(() -> {
            refreshQueued.set(false);
            refreshAll();
        });
    }

    private void reloadPrefixes() {
        var config = plugin.getConfigManager();
        String n = config.vanishNametagPrefix, t = config.vanishTabPrefix;
        if (!Objects.equals(n, nametagRaw)) {
            nametag = n == null || n.isEmpty() ? Component.empty() : plugin.getMessageManager().parse(n, null);
            nametagRaw = n;
        }
        if (!Objects.equals(t, tabRaw)) {
            tab = t == null || t.isEmpty() ? Component.empty() : plugin.getMessageManager().parse(t, null);
            tabRaw = t;
        }
    }

    private void warn(String what, Throwable t) {
        if (warned.compareAndSet(false, true))
            plugin.getLogger().log(Level.WARNING, "Staff nametag/tab prefixes: " + what + ". Packets pass unchanged.", t);
    }

    /** May this viewer see the vanished subject? Uses the published permission facts, never a live check off-thread. */
    private boolean canSee(UUID viewer, UUID subject) {
        return plugin.voiceAudience.canSee(viewer, subject);
    }

    /** One per connection. Edits outgoing team and tab packets for its viewer. */
    private final class Handler extends ChannelOutboundHandlerAdapter implements DecorationModel.Env<Object, Object> {
        volatile UUID viewer;
        volatile ChannelHandlerContext ctx;
        final DecorationModel<Object, Object> model = new DecorationModel<>(this);

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) { this.ctx = ctx; }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object out = msg;
            try {
                if (viewer == null) viewer = nms.playerOf(ctx.pipeline().get("packet_handler"));
                out = transform(msg);
            } catch (Throwable t) {
                warn("a packet could not be read", t);
            }
            if (out == null) { promise.trySuccess(); return; }
            super.write(ctx, out, promise);
            if (model.takeDirty()) requestRefresh();
        }

        private Object transform(Object msg) throws ReflectiveOperationException {
            if (nms.isTeam(msg)) {
                DecorationModel.TeamOp<Object> op = nms.readTeam(msg);
                DecorationModel.TeamOp<Object> out = model.onTeamPacket(op);
                return out == null ? null : out == op ? msg : nms.team(out);
            }
            if (nms.isInfoUpdate(msg)) {
                EnumSet<?> actions = nms.actions(msg);
                boolean display = nms.hasDisplayName(actions), add = nms.hasAddPlayer(actions);
                if (!display && !add) return msg;
                List<Object> entries = nms.entries(msg), changed = new ArrayList<>(entries.size());
                boolean any = false;
                for (Object entry : entries) {
                    UUID id = nms.entryId(entry);
                    if (add) model.onListed(id);
                    if (!display) { changed.add(entry); continue; }
                    Object shown = nms.entryDisplay(entry);
                    Object wanted = model.onDisplay(id, shown);
                    if (wanted != shown) { changed.add(nms.withDisplay(entry, wanted)); any = true; }
                    else changed.add(entry);
                }
                return any ? nms.info(actions, changed) : msg;
            }
            if (nms.isInfoRemove(msg)) {
                model.onUnlisted(nms.removedIds(msg));
                return msg;
            }
            if (nms.isBundle(msg)) {
                List<Object> parts = new ArrayList<>();
                boolean any = false;
                for (Object part : nms.subPackets(msg)) {
                    Object out = transform(part);
                    if (out != part) any = true;
                    if (out != null) parts.add(out);
                }
                return any ? nms.bundle(parts) : msg;
            }
            return msg;
        }

        /** Sends our own packets straight to this client, past this handler. */
        void send(List<Object> ops) {
            ChannelHandlerContext c = ctx;
            if (ops.isEmpty() || c == null) return;
            List<Object> packets = new ArrayList<>(ops.size());
            try {
                for (Object op : ops) {
                    if (op instanceof DecorationModel.TeamOp<?> t) {
                        @SuppressWarnings("unchecked") DecorationModel.TeamOp<Object> team = (DecorationModel.TeamOp<Object>) t;
                        packets.add(nms.team(team));
                    } else if (op instanceof DecorationModel.DisplayOp<?> d) {
                        packets.add(nms.displayUpdate(d.subject(), d.display()));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                warn("a packet could not be built", e);
                return;
            }
            c.executor().execute(() -> {
                for (Object p : packets) c.write(p, c.voidPromise());
                c.flush();
            });
        }

        // ── Env ──

        @Override
        public DecorationModel.State stateOf(String entry) {
            Player subject = Bukkit.getPlayerExact(entry);
            if (subject == null || !plugin.isVanished(subject.getUniqueId())) return DecorationModel.State.NORMAL;
            UUID v = viewer;
            if (v == null) return DecorationModel.State.HIDDEN;
            if (v.equals(subject.getUniqueId())) return DecorationModel.State.NORMAL;
            if (!canSee(v, subject.getUniqueId())) return DecorationModel.State.HIDDEN;
            return Component.empty().equals(nametag) ? DecorationModel.State.NORMAL : DecorationModel.State.TAGGED;
        }

        @Override
        public boolean tabDecorated(UUID subject) {
            UUID v = viewer;
            if (v == null || Component.empty().equals(tab) || !plugin.isVanished(subject)) return false;
            return v.equals(subject) || canSee(v, subject);
        }

        @Override
        public String privateTeam(String entry) {
            Player p = Bukkit.getPlayerExact(entry);
            return "vanishpp." + Integer.toString(p == null ? entry.hashCode() & 0xffff : p.getEntityId(), 36);
        }

        @Override
        public Object tag(Object real) {
            try { return nms.tag(real, nametag); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }

        @Override
        public Object decorate(Object display, UUID subject) {
            try {
                Component base;
                if (display != null) base = nms.adventure(display);
                else {
                    Player p = Bukkit.getPlayer(subject);
                    base = Component.text(p == null ? "" : p.getName());
                }
                return nms.vanilla(Component.text().append(tab).append(base).build());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean sameParams(Object a, Object b) { return nms.sameParams(a, b); }
    }

    /** For tests: whether a connection hook is installed on this channel. */
    boolean hooked(Channel channel) { return handlers.containsKey(channel); }

}
