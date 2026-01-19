package net.thecommandcraft.vanishpp;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedServerPing;
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
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) return;

        this.protocolManager = ProtocolLibrary.getProtocolManager();
        plugin.getLogger().info("Hooked into ProtocolLib.");

        // 1. Spectator in Tab
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                if (!ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(), "vanishpp.see")) return;

                try {
                    PacketContainer packet = event.getPacket();
                    Set<EnumWrappers.PlayerInfoAction> actions = packet.getPlayerInfoActions().read(0);

                    if (actions.contains(EnumWrappers.PlayerInfoAction.ADD_PLAYER) || actions.contains(EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE)) {

                        List<PlayerInfoData> originalList = packet.getPlayerInfoDataLists().read(0);
                        List<PlayerInfoData> newList = new ArrayList<>();
                        boolean modified = false;

                        for (PlayerInfoData data : originalList) {
                            if (data == null || data.getProfile() == null || data.getProfile().getUUID() == null) {
                                newList.add(data);
                                continue;
                            }

                            if (ProtocolLibManager.this.isVanished(data.getProfile().getUUID())) {
                                PlayerInfoData newData = new PlayerInfoData(
                                        data.getProfile(),
                                        data.getLatency(),
                                        EnumWrappers.NativeGameMode.SPECTATOR,
                                        data.getDisplayName(),
                                        null
                                );
                                newList.add(newData);
                                modified = true;
                            } else {
                                newList.add(data);
                            }
                        }

                        if (modified) {
                            packet.getPlayerInfoDataLists().write(0, newList);
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        });

        // 2. Tab Scrubbing
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.TAB_COMPLETE) {
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
                        Set<String> vanishedNames = ProtocolLibManager.this.getVanishedNames();

                        for (String s : suggestions) {
                            if (s == null) continue;
                            if (vanishedNames.contains(s)) changed = true;
                            else filtered.add(s);
                        }
                        if (changed) packet.getStringArrays().write(0, filtered.toArray(new String[0]));
                    }
                } catch (Exception ignored) {}
            }
        });

        // 3. Reveal Invisible/Silent to Staff
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                if (!ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(), "vanishpp.see")) return;

                try {
                    Entity entity = event.getPacket().getEntityModifier(event).read(0);
                    if (entity instanceof Player target && ProtocolLibManager.this.isVanished(target.getUniqueId())) {
                        List<WrappedDataValue> values = new ArrayList<>(event.getPacket().getDataValueCollectionModifier().read(0));
                        boolean modified = false;

                        for (int i = 0; i < values.size(); i++) {
                            WrappedDataValue value = values.get(i);
                            if (value.getIndex() == 0) {
                                byte b = (byte) value.getValue();
                                if ((b & 0x20) != 0) {
                                    values.set(i, new WrappedDataValue(value.getIndex(), value.getSerializer(), (byte) (b & ~0x20)));
                                    modified = true;
                                }
                            }
                            else if (value.getIndex() == 4) {
                                if ((boolean) value.getValue()) {
                                    values.set(i, new WrappedDataValue(value.getIndex(), value.getSerializer(), false));
                                    modified = true;
                                }
                            }
                        }

                        if (modified) {
                            event.getPacket().getDataValueCollectionModifier().write(0, values);
                        }
                    }
                } catch (Exception ignored) {}
            }
        });

        // 4. Server List Ping (Locator Bar Fix)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Status.Server.SERVER_INFO) {
            @Override
            public void onPacketSending(PacketEvent event) {
                // EXPLICIT CAST FIX: Use ProtocolLibManager.this.plugin to avoid shadowing
                if (!ProtocolLibManager.this.plugin.getConfigManager().adjustServerListCount) return;

                try {
                    WrappedServerPing ping = event.getPacket().getServerPings().read(0);
                    if (ping == null) return;

                    List<WrappedGameProfile> players = new ArrayList<>();

                    // Filter the sample list
                    for (WrappedGameProfile profile : ping.getPlayers()) {
                        if (!ProtocolLibManager.this.isVanished(profile.getUUID())) {
                            players.add(profile);
                        }
                    }

                    ping.setPlayers(players);

                    // Adjust count
                    int online = Bukkit.getOnlinePlayers().size();
                    int vanished = ProtocolLibManager.this.plugin.getRawVanishedPlayers().size();
                    ping.setPlayersOnline(Math.max(0, online - vanished));
                } catch (Exception e) {
                    // Ignore
                }
            }
        });
    }

    private boolean isVanished(UUID uuid) { return plugin.getRawVanishedPlayers().contains(uuid); }
    private Set<String> getVanishedNames() {
        Set<String> names = new java.util.HashSet<>();
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) names.add(p.getName());
        }
        return names;
    }
}