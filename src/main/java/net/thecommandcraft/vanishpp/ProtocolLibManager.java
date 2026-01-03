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
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;

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

        // 1. Intercept PLAYER_INFO packets (Spectator Effect in Tab)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(), "vanishpp.see")) return;

                PacketContainer packet = event.getPacket();

                Set<EnumWrappers.PlayerInfoAction> actions;
                try {
                    actions = packet.getPlayerInfoActions().read(0);
                } catch (Exception e) {
                    return;
                }

                if (actions.contains(EnumWrappers.PlayerInfoAction.ADD_PLAYER) || actions.contains(EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE)) {
                    List<PlayerInfoData> originalList = packet.getPlayerInfoDataLists().read(0);
                    List<PlayerInfoData> newList = new ArrayList<>();
                    boolean modified = false;

                    for (PlayerInfoData data : originalList) {
                        if (ProtocolLibManager.this.isVanished(data.getProfile().getUUID())) {
                            // FIX: Pass null for RemoteChatSession to avoid compilation error
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
            }
        });

        // 2. Intercept TAB_COMPLETE packets (Hide from Tab)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.TAB_COMPLETE) {
            @Override
            public void onPacketSending(PacketEvent event) {
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
                            if (vanishedNames.contains(s)) {
                                changed = true;
                            } else {
                                filtered.add(s);
                            }
                        }

                        if (changed) {
                            packet.getStringArrays().write(0, filtered.toArray(new String[0]));
                        }
                    }
                } catch (Exception e) {
                    // Fail silently
                }
            }
        });

        // 3. Intercept ENTITY_METADATA (Fix Invisibility for Staff)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(event.getPlayer(), "vanishpp.see")) return;

                Entity entity = event.getPacket().getEntityModifier(event).read(0);
                if (entity instanceof Player target && ProtocolLibManager.this.isVanished(target.getUniqueId())) {
                    try {
                        List<WrappedDataValue> values = new ArrayList<>(event.getPacket().getDataValueCollectionModifier().read(0));
                        boolean modified = false;

                        for (int i = 0; i < values.size(); i++) {
                            WrappedDataValue value = values.get(i);
                            // Strip Invisible Bit (Index 0, 0x20)
                            if (value.getIndex() == 0) {
                                byte b = (byte) value.getValue();
                                if ((b & 0x20) != 0) {
                                    byte newByte = (byte) (b & ~0x20);
                                    values.set(i, new WrappedDataValue(value.getIndex(), value.getSerializer(), newByte));
                                    modified = true;
                                }
                            }
                            // Strip Silent (Index 4)
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
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        });
    }

    private boolean isVanished(UUID uuid) {
        return plugin.getRawVanishedPlayers().contains(uuid);
    }

    private Set<String> getVanishedNames() {
        Set<String> names = new java.util.HashSet<>();
        for (UUID uuid : plugin.getRawVanishedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) names.add(p.getName());
        }
        return names;
    }
}