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

public class ProtocolLibManager {

    private final Vanishpp plugin;
    private ProtocolManager protocolManager;

    public ProtocolLibManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null)
            return;

        this.protocolManager = ProtocolLibrary.getProtocolManager();
        plugin.getLogger().info("Hooked into ProtocolLib.");

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

        // 2. Comprehensive Reveal/Block for Metadata and Updates
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_METADATA,
                PacketType.Play.Server.ENTITY_EQUIPMENT,
                PacketType.Play.Server.ANIMATION,
                PacketType.Play.Server.ENTITY_EFFECT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled())
                    return;
                Player observer = event.getPlayer();

                try {
                    int entityId = event.getPacket().getIntegers().read(0);
                    Entity entity = protocolManager.getEntityFromID(observer.getWorld(), entityId);

                    if (entity instanceof Player target
                            && ProtocolLibManager.this.plugin.isVanished(target.getUniqueId())) {
                        boolean canSee = ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer,
                                "vanishpp.see");

                        if (!canSee) {
                            event.setCancelled(true);
                            return;
                        }

                        // For staff, we might want to modify metadata (remove invisibility flag for
                        // ghost view)
                        if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                            List<WrappedDataValue> values = new ArrayList<>(
                                    event.getPacket().getDataValueCollectionModifier().read(0));
                            boolean modified = false;

                            for (int i = 0; i < values.size(); i++) {
                                WrappedDataValue value = values.get(i);
                                if (value.getIndex() == 0) {
                                    byte b = (byte) value.getValue();
                                    if ((b & 0x20) != 0) {
                                        values.set(i, new WrappedDataValue(value.getIndex(), value.getSerializer(),
                                                (byte) (b & ~0x20)));
                                        modified = true;
                                    }
                                } else if (value.getIndex() == 4) {
                                    if ((boolean) value.getValue()) {
                                        values.set(i,
                                                new WrappedDataValue(value.getIndex(), value.getSerializer(), false));
                                        modified = true;
                                    }
                                }
                            }
                            if (modified)
                                event.getPacket().getDataValueCollectionModifier().write(0, values);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });

        // 3. Tab List & Info Filtering (Hiding vanished players from PLAYER_INFO)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.PLAYER_INFO,
                PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled())
                    return;
                if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(),
                        "vanishpp.see"))
                    return;

                try {
                    PacketContainer packet = event.getPacket();
                    if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO) {
                        List<PlayerInfoData> dataList = packet.getPlayerInfoDataLists().read(0);
                        List<PlayerInfoData> newData = new ArrayList<>();
                        boolean changed = false;

                        for (PlayerInfoData data : dataList) {
                            if (ProtocolLibManager.this.plugin.isVanished(data.getProfile().getUUID())) {
                                changed = true;
                            } else {
                                newData.add(data);
                            }
                        }
                        if (changed) {
                            if (newData.isEmpty())
                                event.setCancelled(true);
                            else
                                packet.getPlayerInfoDataLists().write(0, newData);
                        }
                    } else { // PLAYER_INFO_UPDATE (1.19.3+)
                        EnumSet<PlayerInfoData.PlayerInfoAction> actions = packet.getPlayerInfoActions().read(0);
                        List<PlayerInfoData> dataList = packet.getPlayerInfoDataLists().read(0);
                        List<PlayerInfoData> newData = new ArrayList<>();
                        boolean changed = false;

                        for (PlayerInfoData data : dataList) {
                            if (ProtocolLibManager.this.plugin.isVanished(data.getProfile().getUUID())) {
                                changed = true;
                            } else {
                                newData.add(data);
                            }
                        }
                        if (changed) {
                            if (newData.isEmpty())
                                event.setCancelled(true);
                            else
                                packet.getPlayerInfoDataLists().write(0, newData);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });

        // 4. Ghost-Proof Spawning (Hiding SPAWN_ENTITY / NAMED_ENTITY_SPAWN)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.NAMED_ENTITY_SPAWN,
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
                    Entity entity = protocolManager.getEntityFromID(event.getPlayer().getWorld(), entityId);

                    if (entity instanceof Player target
                            && ProtocolLibManager.this.plugin.isVanished(target.getUniqueId())) {
                        event.setCancelled(true);
                    }
                } catch (Exception ignored) {
                }
            }
        });

        // 5. Final Stealth Fix: Scrub SCOREBOARD_TEAM packets
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SCOREBOARD_TEAM) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                Player observer = event.getPlayer();
                if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer, "vanishpp.see")) {
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
                } catch (Exception ignored) {}
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

        // 4. THE FIX: Intercept Team packets (Renamed to SCOREBOARD_TEAM in 5.3.0)
        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.SCOREBOARD_TEAM) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        Player observer = event.getPlayer();

                        if (!ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer,
                                "vanishpp.see")) {
                            return;
                        }

                        PacketContainer packet = event.getPacket();
                        String teamName = packet.getStrings().read(0);

                        if (teamName != null && teamName.equals("Vanishpp_Vanished")) {
                            try {
                                int action = packet.getIntegers().read(0);
                                if (action == 0 || action == 2) {
                                    String prefix = ProtocolLibManager.this.plugin
                                            .getConfigManager().vanishNametagPrefix;
                                    if (prefix != null && !prefix.isEmpty()) {
                                        packet.getChatComponents().write(1,
                                                WrappedChatComponent.fromLegacyText(prefix));
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore version mismatch
                            }
                        }
                    }
                });
    }

    private Set<String> getVanishedNames() {
        Set<String> names = new java.util.HashSet<>();
        for (UUID uuid : ProtocolLibManager.this.plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null)
                names.add(p.getName());
        }
        return names;
    }
}