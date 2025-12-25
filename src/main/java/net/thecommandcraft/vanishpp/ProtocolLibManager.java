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
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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

        // 1. Intercept PLAYER_INFO packets (Spectator Effect)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player observer = event.getPlayer();

                // If permission to see is missing, Bukkit handles the hiding.
                // We only modify packets for those who CAN see to make them appear as spectators.
                if (!ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer, "vanishpp.see")) {
                    return;
                }

                PacketContainer packet = event.getPacket();
                EnumWrappers.PlayerInfoAction action = packet.getPlayerInfoAction().read(0);

                if (action == EnumWrappers.PlayerInfoAction.ADD_PLAYER || action == EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE) {
                    List<PlayerInfoData> originalList = packet.getPlayerInfoDataLists().read(0);
                    List<PlayerInfoData> newList = new ArrayList<>();
                    boolean modified = false;

                    for (PlayerInfoData data : originalList) {
                        UUID targetUUID = data.getProfile().getUUID();
                        if (ProtocolLibManager.this.isVanished(targetUUID)) {
                            PlayerInfoData newData = new PlayerInfoData(
                                    data.getProfile(),
                                    data.getLatency(),
                                    EnumWrappers.NativeGameMode.SPECTATOR,
                                    data.getDisplayName()
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
        // This is the client-bound response containing suggestions.
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.TAB_COMPLETE) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player observer = event.getPlayer();

                // If observer is staff, they are allowed to see names in tab
                if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer, "vanishpp.see")) {
                    return;
                }

                PacketContainer packet = event.getPacket();
                // "suggestions" is a String array or list depending on version,
                // but ProtocolLib abstracts it via specific modifiers usually.
                // In modern versions, it uses a specific structure, but often maps to index 0 (Suggestions/Matches)

                // Check suggestions/matches structure
                // Older/Newer versions vary (String[] vs WrappedChatComponent vs TabCompleteMatches)
                // We attempt to read the safest common structure: String Arrays or Lists

                // For modern MC, it's often a "Suggestions" object, abstracted by ProtocolLib via specific getters.
                // NOTE: ProtocolLib 5.x handles 1.20+ suggestions.

                try {
                    // Try simple string list (legacy compatible wrapper)
                    // Note: If using 'Paper', usually Mojang Brigadier suggestions are used which are complex.
                    // This handles legacy/simple string based completions (Chat, some plugins).
                    if (packet.getStringArrays().size() > 0) {
                        String[] suggestions = packet.getStringArrays().read(0);
                        List<String> filtered = new ArrayList<>();
                        boolean changed = false;

                        Set<String> vanishedNames = ProtocolLibManager.this.getVanishedNames();

                        for (String s : suggestions) {
                            if (s == null) continue;
                            if (vanishedNames.contains(s)) {
                                changed = true; // detected vanished name
                            } else {
                                filtered.add(s);
                            }
                        }

                        if (changed) {
                            packet.getStringArrays().write(0, filtered.toArray(new String[0]));
                        }
                    }
                    // Brigadier Suggestions (Modern Command Completions) often use different packet structures.
                    // However, Bukkit's "hidePlayer" usually prevents the server from even sending the entity to the Brigadier tree.
                    // This Listener catches the fallback packet.

                } catch (Exception e) {
                    // Fail silently on structure mismatch to avoid console spam during gameplay
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