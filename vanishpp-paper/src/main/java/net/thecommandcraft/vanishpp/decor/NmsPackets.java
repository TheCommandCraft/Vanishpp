package net.thecommandcraft.vanishpp.decor;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.*;
import java.util.*;

/**
 * Reads and builds the team and tab-list packets through reflection on the server's own (Mojang-mapped) classes, so
 * Vanish++ needs no packet library for them. Paper 1.20.6 and newer use these names. On anything else construction
 * throws and the decorations stay off.
 */
final class NmsPackets {
    private final Class<?> teamPacket, bundlePacket, infoUpdate, infoRemove, entryClass, componentClass;
    private final Constructor<?> teamCtor, paramsCtor, scoreboardCtor, infoCtor, entryCtor, bundleCtor;
    private final Field method;
    private final Method teamName, teamPlayers, teamParams, addPlayerTeam;
    // Team parameters: a class with getters up to 1.21.x, a record from 26.x (copied with only the prefix swapped).
    private final boolean paramsRecord;
    private final RecordComponent[] paramParts;
    private final Constructor<?> paramsCanonical;
    private final Method pDisplay, pOptions, pColor, pVisibility, pCollision, pPrefix, pSuffix;
    private final Method tDisplay, tPrefix, tSuffix, tVisibility, tCollision, tColor, tOptions;
    private final Method infoActions, infoEntries, removeIds, subPackets;
    private final Method asVanilla, asAdventure;
    private final RecordComponent[] entryParts;
    private final Object addPlayer, updateDisplayName;
    private final Field playerConnection, commonConnection, channel;
    private final Method connectionPlayer, entityUuid;

    NmsPackets() throws ReflectiveOperationException {
        ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
        String game = "net.minecraft.network.protocol.game.";
        teamPacket = Class.forName(game + "ClientboundSetPlayerTeamPacket", false, cl);
        Class<?> params = Class.forName(game + "ClientboundSetPlayerTeamPacket$Parameters", false, cl);
        Class<?> playerTeam = Class.forName("net.minecraft.world.scores.PlayerTeam", false, cl);
        Class<?> scoreboard = Class.forName("net.minecraft.world.scores.Scoreboard", false, cl);
        componentClass = Class.forName("net.minecraft.network.chat.Component", false, cl);
        bundlePacket = Class.forName(game + "ClientboundBundlePacket", false, cl);
        infoUpdate = Class.forName(game + "ClientboundPlayerInfoUpdatePacket", false, cl);
        infoRemove = Class.forName(game + "ClientboundPlayerInfoRemovePacket", false, cl);
        entryClass = Class.forName(game + "ClientboundPlayerInfoUpdatePacket$Entry", false, cl);
        Class<?> action = Class.forName(game + "ClientboundPlayerInfoUpdatePacket$Action", false, cl);

        teamCtor = teamPacket.getDeclaredConstructor(String.class, int.class, Optional.class, Collection.class);
        teamCtor.setAccessible(true);
        method = teamPacket.getDeclaredField("method");
        method.setAccessible(true);
        teamName = teamPacket.getMethod("getName");
        teamPlayers = teamPacket.getMethod("getPlayers");
        teamParams = teamPacket.getMethod("getParameters");
        paramsCtor = params.getConstructor(playerTeam);
        scoreboardCtor = scoreboard.getConstructor();
        addPlayerTeam = scoreboard.getMethod("addPlayerTeam", String.class);
        tPrefix = playerTeam.getMethod("setPlayerPrefix", componentClass);
        paramsRecord = params.isRecord();
        if (paramsRecord) {
            paramParts = params.getRecordComponents();
            paramsCanonical = params.getDeclaredConstructor(Arrays.stream(paramParts).map(RecordComponent::getType).toArray(Class<?>[]::new));
            paramsCanonical.setAccessible(true);
            pPrefix = params.getMethod("playerPrefix");
            pDisplay = pOptions = pColor = pVisibility = pCollision = pSuffix = null;
            tDisplay = tSuffix = tVisibility = tCollision = tColor = tOptions = null;
        } else {
            paramParts = null;
            paramsCanonical = null;
            pDisplay = params.getMethod("getDisplayName");
            pOptions = params.getMethod("getOptions");
            pColor = params.getMethod("getColor");
            pVisibility = params.getMethod("getNametagVisibility");
            pCollision = params.getMethod("getCollisionRule");
            pPrefix = params.getMethod("getPlayerPrefix");
            pSuffix = params.getMethod("getPlayerSuffix");
            tDisplay = playerTeam.getMethod("setDisplayName", componentClass);
            tSuffix = playerTeam.getMethod("setPlayerSuffix", componentClass);
            tVisibility = single(playerTeam, "setNameTagVisibility");
            tCollision = single(playerTeam, "setCollisionRule");
            tColor = single(playerTeam, "setColor");
            tOptions = single(playerTeam, "unpackOptions"); // int up to 1.21.x
        }

        infoCtor = infoUpdate.getConstructor(EnumSet.class, List.class);
        infoActions = infoUpdate.getMethod("actions");
        infoEntries = infoUpdate.getMethod("entries");
        entryParts = entryClass.getRecordComponents();
        Class<?>[] types = Arrays.stream(entryParts).map(RecordComponent::getType).toArray(Class<?>[]::new);
        entryCtor = entryClass.getDeclaredConstructor(types);
        entryCtor.setAccessible(true);
        addPlayer = enumConstant(action, "ADD_PLAYER");
        updateDisplayName = enumConstant(action, "UPDATE_DISPLAY_NAME");
        removeIds = infoRemove.getMethod("profileIds");
        subPackets = bundlePacket.getMethod("subPackets");
        bundleCtor = bundlePacket.getConstructor(Iterable.class);

        Class<?> adventure = Class.forName("io.papermc.paper.adventure.PaperAdventure", false, cl);
        asVanilla = adventure.getMethod("asVanilla", Component.class);
        asAdventure = adventure.getMethod("asAdventure", componentClass);

        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, cl);
        Class<?> connection = Class.forName("net.minecraft.network.Connection", false, cl);
        playerConnection = serverPlayer.getField("connection");
        commonConnection = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl", false, cl).getDeclaredField("connection");
        commonConnection.setAccessible(true);
        channel = connection.getField("channel");
        connectionPlayer = connection.getMethod("getPlayer");
        entityUuid = Class.forName("net.minecraft.world.entity.Entity", false, cl).getMethod("getUUID");
    }

    private static Method single(Class<?> owner, String name) throws NoSuchMethodException {
        for (Method m : owner.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Object enumConstant(Class<?> type, String name) throws NoSuchFieldException {
        for (Object c : type.getEnumConstants()) if (((Enum<?>) c).name().equals(name)) return c;
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    /** Converts a value read from one version's parameters to the type a setter wants (1.20.6 stored names as strings). */
    private static Object coerce(Object value, Class<?> target) {
        if (value == null || target.isInstance(value) || !target.isEnum()) return value;
        String wanted = value.toString();
        for (Object c : target.getEnumConstants()) {
            if (((Enum<?>) c).name().equalsIgnoreCase(wanted)) return c;
            try { if (wanted.equals(c.getClass().getField("name").get(c))) return c; } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    // ── Team packets ──

    boolean isTeam(Object packet) { return teamPacket.isInstance(packet); }
    boolean isBundle(Object packet) { return bundlePacket.isInstance(packet); }
    boolean isInfoUpdate(Object packet) { return infoUpdate.isInstance(packet); }
    boolean isInfoRemove(Object packet) { return infoRemove.isInstance(packet); }

    @SuppressWarnings("unchecked")
    DecorationModel.TeamOp<Object> readTeam(Object packet) throws ReflectiveOperationException {
        Optional<Object> params = (Optional<Object>) teamParams.invoke(packet);
        Collection<String> players = (Collection<String>) teamPlayers.invoke(packet);
        return new DecorationModel.TeamOp<>((String) teamName.invoke(packet), method.getInt(packet), params.orElse(null),
                players == null ? List.of() : new ArrayList<>(players));
    }

    Object team(DecorationModel.TeamOp<Object> op) throws ReflectiveOperationException {
        return teamCtor.newInstance(op.team(), op.method(), Optional.ofNullable(op.params()), op.players());
    }

    /** The real team's parameters with {@code tag} in front of the prefix (or a plain tag when there is no team). */
    Object tag(Object real, Component tag) throws ReflectiveOperationException {
        if (real != null && paramsRecord) {
            Object old = pPrefix.invoke(real);
            Object prefix = vanilla(old == null ? tag : Component.text().append(tag).append(adventure(old)).build());
            Object[] values = new Object[paramParts.length];
            for (int i = 0; i < paramParts.length; i++)
                values[i] = paramParts[i].getName().equals("playerPrefix") ? prefix : paramParts[i].getAccessor().invoke(real);
            return paramsCanonical.newInstance(values);
        }
        Object team = addPlayerTeam.invoke(scoreboardCtor.newInstance(), "vanishpp");
        Component prefix = tag;
        if (real != null) {
            tDisplay.invoke(team, pDisplay.invoke(real));
            tSuffix.invoke(team, pSuffix.invoke(real));
            tVisibility.invoke(team, coerce(pVisibility.invoke(real), tVisibility.getParameterTypes()[0]));
            tCollision.invoke(team, coerce(pCollision.invoke(real), tCollision.getParameterTypes()[0]));
            tColor.invoke(team, pColor.invoke(real));
            Number options = (Number) pOptions.invoke(real);
            tOptions.invoke(team, tOptions.getParameterTypes()[0] == byte.class ? (Object) options.byteValue() : (Object) options.intValue());
            Object old = pPrefix.invoke(real);
            if (old != null) prefix = Component.text().append(tag).append(adventure(old)).build();
        }
        tPrefix.invoke(team, vanilla(prefix));
        return paramsCtor.newInstance(team);
    }

    boolean sameParams(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (paramsRecord) return a.equals(b);
        try {
            for (Method m : new Method[]{pDisplay, pOptions, pColor, pVisibility, pCollision, pPrefix, pSuffix})
                if (!Objects.equals(m.invoke(a), m.invoke(b))) return false;
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    // ── Tab-list packets ──

    @SuppressWarnings("unchecked")
    EnumSet<?> actions(Object packet) throws ReflectiveOperationException { return (EnumSet<?>) infoActions.invoke(packet); }
    boolean hasDisplayName(EnumSet<?> actions) { return actions.contains(updateDisplayName); }
    boolean hasAddPlayer(EnumSet<?> actions) { return actions.contains(addPlayer); }

    @SuppressWarnings("unchecked")
    List<Object> entries(Object packet) throws ReflectiveOperationException { return (List<Object>) infoEntries.invoke(packet); }

    UUID entryId(Object entry) throws ReflectiveOperationException {
        for (RecordComponent part : entryParts) if (part.getType() == UUID.class) return (UUID) part.getAccessor().invoke(entry);
        throw new NoSuchMethodException("Entry has no UUID");
    }

    Object entryDisplay(Object entry) throws ReflectiveOperationException {
        for (RecordComponent part : entryParts) if (part.getName().equals("displayName")) return part.getAccessor().invoke(entry);
        throw new NoSuchMethodException("Entry has no displayName");
    }

    /** A copy of the entry with another display name. */
    Object withDisplay(Object entry, Object display) throws ReflectiveOperationException {
        Object[] values = new Object[entryParts.length];
        for (int i = 0; i < entryParts.length; i++)
            values[i] = entryParts[i].getName().equals("displayName") ? display : entryParts[i].getAccessor().invoke(entry);
        return entryCtor.newInstance(values);
    }

    Object info(EnumSet<?> actions, List<Object> entries) throws ReflectiveOperationException {
        return infoCtor.newInstance(actions, entries);
    }

    /** A packet that only changes one player's tab name; the client reads nothing else for this action. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object displayUpdate(UUID subject, Object display) throws ReflectiveOperationException {
        Object[] values = new Object[entryParts.length];
        for (int i = 0; i < entryParts.length; i++) {
            Class<?> type = entryParts[i].getType();
            if (type == UUID.class) values[i] = subject;
            else if (entryParts[i].getName().equals("displayName")) values[i] = display;
            else if (type == boolean.class) values[i] = false;
            else if (type == int.class) values[i] = 0;
            else values[i] = null;
        }
        EnumSet actions = EnumSet.of((Enum) updateDisplayName);
        return infoCtor.newInstance(actions, List.of(entryCtor.newInstance(values)));
    }

    @SuppressWarnings("unchecked")
    List<UUID> removedIds(Object packet) throws ReflectiveOperationException { return (List<UUID>) removeIds.invoke(packet); }

    @SuppressWarnings("unchecked")
    Iterable<Object> subPackets(Object bundle) throws ReflectiveOperationException { return (Iterable<Object>) subPackets.invoke(bundle); }

    Object bundle(List<Object> packets) throws ReflectiveOperationException { return bundleCtor.newInstance(packets); }

    // ── Components and connections ──

    Object vanilla(Component component) throws ReflectiveOperationException { return asVanilla.invoke(null, component); }
    Component adventure(Object component) throws ReflectiveOperationException { return (Component) asAdventure.invoke(null, component); }

    /** The netty channel of an online player. */
    io.netty.channel.Channel channelOf(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = playerConnection.get(handle);
        if (listener == null) return null;
        return (io.netty.channel.Channel) channel.get(commonConnection.get(listener));
    }

    /** The player a connection handler belongs to, or null before the login finished. */
    UUID playerOf(Object connection) {
        try {
            Object player = connectionPlayer.invoke(connection);
            return player == null ? null : (UUID) entityUuid.invoke(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
