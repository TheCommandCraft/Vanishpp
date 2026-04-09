package net.thecommandcraft.vanishpp.hooks;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.*;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolLibManager {

    private final Vanishpp plugin;
    private ProtocolManager protocolManager;
    private final ConcurrentHashMap<Integer, UUID> entityIdCache = new ConcurrentHashMap<>();

    public ProtocolLibManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void cachePlayer(Player p) {
        entityIdCache.put(p.getEntityId(), p.getUniqueId());
    }

    public void uncachePlayer(Player p) {
        entityIdCache.remove(p.getEntityId());
    }

    private boolean isVanishedEntity(int entityId) {
        UUID uuid = entityIdCache.get(entityId);
        return uuid != null && plugin.isVanished(uuid);
    }

    public void load() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null)
            return;

        this.protocolManager = ProtocolLibrary.getProtocolManager();
        plugin.getLogger().info("Hooked into ProtocolLib.");

        for (Player p : Bukkit.getOnlinePlayers()) {
            cachePlayer(p);
        }

        registerSilentChestListeners();

        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.TAB_COMPLETE) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (event.isCancelled()) return;
                        if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(), "vanishpp.see")) return;

                        try {
                            PacketContainer packet = event.getPacket();
                            if (packet.getStringArrays().size() > 0) {
                                String[] suggestions = packet.getStringArrays().read(0);
                                List<String> filtered = new ArrayList<>();
                                boolean changed = false;
                                Set<String> vanishedNames = getVanishedNames();

                                for (String s : suggestions) {
                                    if (s == null) continue;
                                    if (vanishedNames.contains(s)) changed = true;
                                    else filtered.add(s);
                                }
                                if (changed)
                                    packet.getStringArrays().write(0, filtered.toArray(new String[0]));
                            }
                        } catch (Exception ignored) {}
                    }
                });

        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_METADATA,
                PacketType.Play.Server.ENTITY_EQUIPMENT,
                PacketType.Play.Server.ANIMATION,
                PacketType.Play.Server.ENTITY_EFFECT,
                PacketType.Play.Server.ENTITY_VELOCITY,
                PacketType.Play.Server.REL_ENTITY_MOVE,
                PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
                PacketType.Play.Server.ENTITY_LOOK,
                PacketType.Play.Server.ENTITY_TELEPORT,
                PacketType.Play.Server.ENTITY_HEAD_ROTATION,
                PacketType.Play.Server.ENTITY_STATUS,
                PacketType.Play.Server.COLLECT,
                PacketType.Play.Server.MOUNT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                Player observer = event.getPlayer();
                boolean canSee = ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer, "vanishpp.see");

                try {
                    PacketContainer packet = event.getPacket();
                    PacketType type = event.getPacketType();
                    int entityId = packet.getIntegers().read(0);

                    if (isVanishedEntity(entityId)) {
                        if (!canSee) {
                            event.setCancelled(true);
                            return;
                        }
                        if (type == PacketType.Play.Server.ENTITY_METADATA) {
                            modifyMetadataForStaff(packet);
                        }
                    }

                    if (!canSee) {
                        if (type == PacketType.Play.Server.COLLECT) {
                            int collectorId = packet.getIntegers().read(1);
                            if (isVanishedEntity(collectorId)) {
                                event.setCancelled(true);
                            }
                        } else if (type == PacketType.Play.Server.MOUNT) {
                            int[] passengers = packet.getIntegerArrays().read(0);
                            if (passengers != null) {
                                boolean hasVanished = false;
                                List<Integer> filtered = new ArrayList<>();
                                for (int id : passengers) {
                                    if (isVanishedEntity(id)) {
                                        hasVanished = true;
                                    } else {
                                        filtered.add(id);
                                    }
                                }
                                if (hasVanished) {
                                    if (filtered.isEmpty())
                                        event.setCancelled(true);
                                    else {
                                        int[] newArray = filtered.stream().mapToInt(i -> i).toArray();
                                        packet.getIntegerArrays().write(0, newArray);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            private void modifyMetadataForStaff(PacketContainer packet) {
                try {
                    List<WrappedDataValue> values = new ArrayList<>(packet.getDataValueCollectionModifier().read(0));
                    boolean modified = false;
                    boolean staffGlow = ProtocolLibManager.this.plugin.getConfigManager().staffGlowEnabled;
                    for (int i = 0; i < values.size(); i++) {
                        WrappedDataValue value = values.get(i);
                        if (value.getIndex() == 0) {
                            byte b = (byte) value.getValue();
                            b = (byte) (b & ~0x20);
                            if (staffGlow) b = (byte) (b | 0x40);
                            values.set(i, new WrappedDataValue(value.getIndex(), value.getSerializer(), b));
                            modified = true;
                        } else if (value.getIndex() == 4) {
                            if ((boolean) value.getValue()) {
                                values.set(i, new WrappedDataValue(value.getIndex(), value.getSerializer(), false));
                                modified = true;
                            }
                        }
                    }
                    if (modified)
                        packet.getDataValueCollectionModifier().write(0, values);
                } catch (Exception ignored) {}
            }
        });

        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.SPAWN_ENTITY) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(), "vanishpp.see")) return;

                try {
                    int entityId = event.getPacket().getIntegers().read(0);
                    if (isVanishedEntity(entityId)) {
                        event.setCancelled(true);
                    }
                } catch (Exception ignored) {}
            }
        });

        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SCOREBOARD_TEAM) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (event.isCancelled()) return;
                        Player observer = event.getPlayer();
                        if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer, "vanishpp.see")) return;

                        try {
                            PacketContainer packet = event.getPacket();
                            int action = packet.getIntegers().read(0);

                            if (action == 0 || action == 3 || action == 4) {
                                Collection<String> players = packet.getSpecificModifier(Collection.class).read(0);
                                if (players != null) {
                                    List<String> scrubbed = new ArrayList<>();
                                    boolean changed = false;
                                    for (String name : players) {
                                        Player p = Bukkit.getPlayer(name);
                                        if (p != null && ProtocolLibManager.this.plugin.isVanished(p.getUniqueId())) {
                                            changed = true;
                                        } else {
                                            scrubbed.add(name);
                                        }
                                    }
                                    if (changed) {
                                        if (scrubbed.isEmpty() && action != 0)
                                            event.setCancelled(true);
                                        else
                                            packet.getSpecificModifier(Collection.class).write(0, scrubbed);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                });

        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Status.Server.SERVER_INFO) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (!ProtocolLibManager.this.plugin.getConfigManager().adjustServerListCount) return;

                        try {
                            WrappedServerPing ping = event.getPacket().getServerPings().read(0);
                            if (ping == null) return;

                            List<WrappedGameProfile> players = new ArrayList<>();
                            for (WrappedGameProfile profile : ping.getPlayers()) {
                                if (!ProtocolLibManager.this.plugin.isVanished(profile.getUUID())) {
                                    players.add(profile);
                                }
                            }
                            ping.setPlayers(players);

                            int onlineCount = Bukkit.getOnlinePlayers().size();
                            int vanishedCount = ProtocolLibManager.this.plugin.getRawVanishedPlayers().size();
                            ping.setPlayersOnline(Math.max(0, onlineCount - vanishedCount));
                        } catch (Exception ignored) {}
                    }
                });
    }

    private void registerSilentChestListeners() {
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.BLOCK_ACTION) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                if (ProtocolLibManager.this.plugin.silentlyOpenedBlocks.isEmpty()) return;
                Player observer = event.getPlayer();
                try {
                    PacketContainer packet = event.getPacket();
                    BlockPosition pos = packet.getBlockPositionModifier().read(0);
                    String blockKey = pos.getX() + "," + pos.getY() + "," + pos.getZ();
                    UUID openerId = ProtocolLibManager.this.plugin.silentlyOpenedBlocks.get(blockKey);
                    if (openerId == null) return;
                    if (observer.getUniqueId().equals(openerId)) return;
                    event.setCancelled(true);
                } catch (Exception e) {
                    plugin.getLogger().warning("[SilentChest] BLOCK_ACTION suppress failed: " + e);
                }
            }
        });

        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.NAMED_SOUND_EFFECT,
                PacketType.Play.Server.CUSTOM_SOUND_EFFECT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                if (ProtocolLibManager.this.plugin.silentlyOpenedBlocks.isEmpty()) return;
                Player observer = event.getPlayer();
                if (ProtocolLibManager.this.plugin.isVanished(observer)) return;
                if (matchesSilentBlock(event.getPacket()))
                    event.setCancelled(true);
            }

            private boolean matchesSilentBlock(PacketContainer packet) {
                ConcurrentHashMap<String, UUID> silentBlocks = ProtocolLibManager.this.plugin.silentlyOpenedBlocks;
                for (int offset = 0; offset <= 3; offset++) {
                    try {
                        int bx = packet.getIntegers().read(offset) >> 3;
                        int by = packet.getIntegers().read(offset + 1) >> 3;
                        int bz = packet.getIntegers().read(offset + 2) >> 3;
                        if (silentBlocks.containsKey(bx + "," + by + "," + bz))
                            return true;
                    } catch (Exception ignored) {}
                }
                return false;
            }
        });
    }

    public void sendGlowMetadata(Player vanished) {
        if (!plugin.getConfigManager().staffGlowEnabled) return;
        try {
            for (Player observer : Bukkit.getOnlinePlayers()) {
                if (observer.equals(vanished)) continue;
                if (!plugin.getPermissionManager().canSee(observer, vanished)) continue;
                if (!observer.canSee(vanished)) continue;
                PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
                packet.getIntegers().write(0, vanished.getEntityId());
                List<WrappedDataValue> values = new ArrayList<>();
                values.add(new WrappedDataValue(0,
                        WrappedDataWatcher.Registry.get(Byte.class), (byte) 0x40));
                packet.getDataValueCollectionModifier().write(0, values);
                protocolManager.sendServerPacket(observer, packet);
            }
        } catch (Throwable ignored) {}
    }

    private Set<String> getVanishedNames() {
        Set<String> names = new HashSet<>();
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.getName() != null)
                names.add(p.getName());
        }
        return names;
    }
}