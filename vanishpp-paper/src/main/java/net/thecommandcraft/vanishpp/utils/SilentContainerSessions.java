package net.thecommandcraft.vanishpp.utils;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.Material;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SilentContainerSessions {
    public record Session(UUID id, UUID viewer, Set<String> blocks) {}
    private final Map<UUID, Session> current = new HashMap<>();
    private final Map<UUID, Session> retained = new HashMap<>();
    private final Map<String, BlockData> barrels = new HashMap<>();
    private final Set<String> blocks = ConcurrentHashMap.newKeySet();

    public Set<String> blocks() { return Collections.unmodifiableSet(blocks); }

    public static String key(UUID world, int x, int y, int z) { return world + ":" + x + "," + y + "," + z; }
    public static String key(Block block) { return key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }

    public synchronized Session open(UUID viewer, Set<String> keys, Block block) {
        Session session = new Session(UUID.randomUUID(), viewer, Set.copyOf(keys));
        current.put(viewer, session);
        retained.put(session.id(), session);
        blocks.addAll(keys);
        if (block.getType() == Material.BARREL) {
            BlockData closed = block.getBlockData().clone();
            if (closed instanceof Openable openable) openable.setOpen(false);
            barrels.put(key(block), closed);
        }
        return session;
    }

    public synchronized Session close(UUID viewer) { return current.remove(viewer); }

    public synchronized void retire(Session session) {
        if (session == null || retained.remove(session.id()) == null) return;
        current.remove(session.viewer(), session);
        for (String key : session.blocks()) {
            if (retained.values().stream().noneMatch(other -> other.blocks().contains(key))) {
                blocks.remove(key);
                barrels.remove(key);
            }
        }
    }

    public synchronized boolean suppress(UUID viewer, UUID world, int x, int y, int z) {
        String key = key(world, x, y, z);
        return blocks.contains(key) && retained.values().stream()
                .noneMatch(session -> session.viewer().equals(viewer) && session.blocks().contains(key));
    }

    public synchronized BlockData closedBarrel(UUID world, int x, int y, int z) {
        BlockData data = barrels.get(key(world, x, y, z));
        return data == null ? null : data.clone();
    }
}
