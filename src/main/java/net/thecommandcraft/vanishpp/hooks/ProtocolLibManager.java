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
import org.bukkit.entity.Entity;
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
        registerSilentChestListeners();

        // 1. Tab Scrubbing (Hiding vanished players from non-staff)
        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.TAB_COMPLETE) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (event.isCancelled())
                            return;
                        if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(),
                                "vanishpp.see"))
                            return;

                        try {
                            PacketContainer packet = event.getPacket();
                            if (packet.getStringArrays().size() > 0) {
                                String[] suggestions = packet.getStringArrays().read(0);
                                List<String> filtered = new ArrayList<>();
                                boolean changed = false;
                                Set<String> vanishedNames = ProtocolLibManager.this.getVanishedNames();

                                for (String s : suggestions) {
                                    if (s == null)
                                        continue;
                                    if (vanishedNames.contains(s))
                                        changed = true;
                                    else
                                        filtered.add(s);
                                }
                                if (changed)
                                    packet.getStringArrays().write(0, filtered.toArray(new String[0]));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });

        // 2. Comprehensive Reveal/Block for Metadata, Updates, and Movement
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
                if (event.isCancelled())
                    return;
                Player observer = event.getPlayer();
                boolean canSee = ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer,
                        "vanishpp.see");

                try {
                    PacketContainer packet = event.getPacket();
                    PacketType type = event.getPacketType();

                    // Handle packets where index 0 is the primary entity
                    int entityId = packet.getIntegers().read(0);

                    if (isVanishedEntity(entityId)) {
                        if (!canSee) {
                            event.setCancelled(true);
                            return;
                        }

                        // Staff-only modifications (remove invisibility flag)
                        if (type == PacketType.Play.Server.ENTITY_METADATA) {
                            modifyMetadataForStaff(packet);
                        }
                    }

                    // Extra checks for specific packet types
                    if (!canSee) {
                        if (type == PacketType.Play.Server.COLLECT) {
                            // Index 1 is the collector ID
                            int collectorId = packet.getIntegers().read(1);
                            if (isVanishedEntity(collectorId)) {
                                event.setCancelled(true);
                            }
                        } else if (type == PacketType.Play.Server.MOUNT) {
                            // Int array modifier contains passenger IDs
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
                } catch (Exception ignored) {
                }
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
                            // Strip invisibility flag (0x20)
                            b = (byte) (b & ~0x20);
                            // Add glowing outline flag (0x40) so staff see a clear visual indicator
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
                } catch (Exception ignored) {
                }
            }
        });

        // 3. Tab List & Info Filtering logic removed because native hidePlayer()
        // handles it correctly.

        // 4. Ghost-Proof Spawning (Hiding SPAWN_ENTITY — NAMED_ENTITY_SPAWN removed in 1.21)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.SPAWN_ENTITY) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled())
                    return;
                if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(),
                        "vanishpp.see"))
                    return;

                try {
                    int entityId = event.getPacket().getIntegers().read(0);

                    if (isVanishedEntity(entityId)) {
                        event.setCancelled(true);
                    }
                } catch (Exception ignored) {
                }
            }
        });

        // 5. Final Stealth Fix: Scrub SCOREBOARD_TEAM packets
        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SCOREBOARD_TEAM) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (event.isCancelled())
                            return;
                        Player observer = event.getPlayer();
                        if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer,
                                "vanishpp.see")) {
                            // Staff see prefixes (handled by the other listener below)
                            return;
                        }

                        try {
                            PacketContainer packet = event.getPacket();
                            int action = packet.getIntegers().read(0);

                            // Actions: 0 (Create), 3 (Add Members), 4 (Remove Members)
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
                                        if (scrubbed.isEmpty() && action != 0) {
                                            event.setCancelled(true);
                                        } else {
                                            packet.getSpecificModifier(Collection.class).write(0, scrubbed);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });

        // 6. Server List Ping
        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Status.Server.SERVER_INFO) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (!ProtocolLibManager.this.plugin.getConfigManager().adjustServerListCount)
                            return;

                        try {
                            WrappedServerPing ping = event.getPacket().getServerPings().read(0);
                            if (ping == null)
                                return;

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
                        } catch (Exception e) {
                            // Ignore
                        }
                    }
                });

        // Native vanishTeam.prefix() handles the nametag prefix for staff already.
    }

    private void registerSilentChestListeners() {
        // Suppress BLOCK_ACTION (chest open/close animation, shulker open/close animation)
        // for non-seers when the block is in the silently-opened set
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.BLOCK_ACTION) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                if (ProtocolLibManager.this.plugin.silentlyOpenedBlocks.isEmpty()) return;
                Player observer = event.getPlayer();
                // Suppress chest lid animation for ALL non-openers (both staff and non-staff)
                // The vanished player who opened it will see it, everyone else won't
                if (ProtocolLibManager.this.plugin.isVanished(observer))
                    return; // Don't suppress for the vanished opener
                try {
                    PacketContainer packet = event.getPacket();
                    BlockPosition pos = packet.getBlockPositionModifier().read(0);
                    String blockKey = pos.getX() + "," + pos.getY() + "," + pos.getZ();
                    if (ProtocolLibManager.this.plugin.silentlyOpenedBlocks.contains(blockKey))
                        event.setCancelled(true);
                } catch (Exception ignored) {}
            }
        });

        // Suppress sound effects originating at silently-opened block positions.
        // Vanilla block sounds (barrel, chest, shulker) use SOUND_EFFECT (registered sound ID).
        // Custom sounds use NAMED_SOUND_EFFECT. We listen to both.
        // All sound packets in 1.21+ store coordinates as fixed-point ints (actual_coord * 8).
        // Integer field layout varies by packet type — we try multiple index offsets to be robust.
        PacketAdapter soundListener = new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.NAMED_SOUND_EFFECT,
                PacketType.Play.Server.CUSTOM_SOUND_EFFECT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                if (ProtocolLibManager.this.plugin.silentlyOpenedBlocks.isEmpty()) return;
                Player observer = event.getPlayer();
                // Suppress sound for everyone except the vanished opener
                if (ProtocolLibManager.this.plugin.isVanished(observer))
                    return;
                if (matchesSilentBlock(event.getPacket()))
                    event.setCancelled(true);
            }

            /** Try index offsets 0..3 for X/Y/Z, always treating as fixed-point (* 8). */
            private boolean matchesSilentBlock(PacketContainer packet) {
                Set<String> silentBlocks = ProtocolLibManager.this.plugin.silentlyOpenedBlocks;
                // Try integer-based coordinates (fixed-point) at multiple offsets
                for (int offset = 0; offset <= 3; offset++) {
                    try {
                        int bx = packet.getIntegers().read(offset) >> 3;
                        int by = packet.getIntegers().read(offset + 1) >> 3;
                        int bz = packet.getIntegers().read(offset + 2) >> 3;
                        if (silentBlocks.contains(bx + "," + by + "," + bz))
                            return true;
                    } catch (Exception ignored) {}
                }
                return false;
            }
        };
        protocolManager.addPacketListener(soundListener);
    }

    /**
     * Send an ENTITY_METADATA packet with the glow flag to all staff observers.
     * This forces the client to render the glow outline without waiting for a natural
     * metadata change (like sneaking).
     */
    public void sendGlowMetadata(Player vanished) {
        if (!plugin.getConfigManager().staffGlowEnabled) return;
        try {
            for (Player observer : Bukkit.getOnlinePlayers()) {
                if (observer.equals(vanished)) continue;
                if (!plugin.getPermissionManager().canSee(observer, vanished)) continue;
                if (!observer.canSee(vanished)) continue; // not yet shown
                PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
                packet.getIntegers().write(0, vanished.getEntityId());
                List<WrappedDataValue> values = new ArrayList<>();
                // Entity flags byte: 0x40 = glowing (invisibility 0x20 stripped by our interceptor)
                values.add(new WrappedDataValue(0,
                        WrappedDataWatcher.Registry.get(Byte.class), (byte) 0x40));
                packet.getDataValueCollectionModifier().write(0, values);
                protocolManager.sendServerPacket(observer, packet);
            }
        } catch (Throwable ignored) {}
    }

    private Set<String> getVanishedNames() {
        Set<String> names = new java.util.HashSet<>();
        for (UUID uuid : ProtocolLibManager.this.plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.getName() != null)
                names.add(p.getName());
        }
        return names;
    }
}