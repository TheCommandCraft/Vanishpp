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

    /**
     * Per-observer tracking of which {@code Vanishpp_Vanished} team member names that
     * observer's client has actually been sent (via CREATE/ADD). See
     * {@link VanishTeamPacketPolicy} for why this exists instead of trusting the
     * observer's current permission at REMOVE time. Cleared on quit from
     * {@link #clearObserverKnowledge(UUID)}, called by {@code PlayerListener#onQuit}.
     */
    private final Map<UUID, Set<String>> knownVanishTeamMembers = new ConcurrentHashMap<>();

    public ProtocolLibManager(Vanishpp plugin) {
        this.plugin = plugin;
    }

    /** Drops a quitting observer's tracked team knowledge so the map doesn't grow unbounded. */
    public void clearObserverKnowledge(UUID observerId) {
        knownVanishTeamMembers.remove(observerId);
    }

    public void load() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null)
            return;

        this.protocolManager = ProtocolLibrary.getProtocolManager();
        plugin.getLogger().info("Hooked into ProtocolLib.");
        registerSilentChestListeners();
        registerSleepStatusListener();

        // Each listener below is registered in its own try-catch: a single unsupported
        // PacketType on a given server/ProtocolLib version must not silently take down
        // every other listener registered after it (they used to share one unguarded
        // method body, so one bad registration disabled all remaining protections,
        // including the crash-preventing team-scrub listener below).

        // 1. Tab Scrubbing (Hiding vanished players from non-staff)
        try {
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register tab-complete scrubbing listener: " + e.getMessage());
        }

        // 2. Comprehensive Reveal/Block for Metadata, Updates, and Movement
        try {
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
                    Entity entity = protocolManager.getEntityFromID(observer.getWorld(), entityId);

                    if (entity instanceof Player target
                            && ProtocolLibManager.this.plugin.isVanished(target.getUniqueId())) {
                        // Never block packets about the player themselves — this includes
                        // ENTITY_STATUS (OP level updates needed for F3+F4 game mode switcher)
                        // and other self-referencing packets that the client needs.
                        if (observer.getEntityId() == entityId) {
                            return;
                        }
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
                            Entity collector = protocolManager.getEntityFromID(observer.getWorld(), collectorId);
                            if (collector instanceof Player p
                                    && ProtocolLibManager.this.plugin.isVanished(p.getUniqueId())) {
                                event.setCancelled(true);
                            }
                        } else if (type == PacketType.Play.Server.MOUNT) {
                            // Int array modifier contains passenger IDs
                            int[] passengers = packet.getIntegerArrays().read(0);
                            if (passengers != null) {
                                boolean hasVanished = false;
                                List<Integer> filtered = new ArrayList<>();
                                for (int id : passengers) {
                                    Entity e = protocolManager.getEntityFromID(observer.getWorld(), id);
                                    if (e instanceof Player p
                                            && ProtocolLibManager.this.plugin.isVanished(p.getUniqueId())) {
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register entity reveal/block listener: " + e.getMessage());
        }

        // 3. PLAYER_INFO backstop: hidePlayer() only sends a one-shot PLAYER_INFO_REMOVE at
        // the moment visibility changes - it does not stop another plugin (a TAB plugin,
        // a third-party team plugin re-syncing its own tab entries, even a routine gamemode
        // change broadcast) from causing a later PLAYER_INFO packet that re-includes the
        // vanished player. This listener strips vanished-player entries out of every such
        // packet before it reaches a non-seer, regardless of what caused it to be sent.
        // (ProtocolLib 5.3.0's PLAYER_INFO covers both the legacy add/update packet and the
        // modern 1.19.3+ ClientboundPlayerInfoUpdatePacket under one name - PLAYER_INFO_REMOVE
        // is the separate, already-correctly-handled removal packet and is left alone here.)
        try {
        if (PacketType.Play.Server.PLAYER_INFO.isSupported()) {
        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.PLAYER_INFO) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (event.isCancelled())
                            return;
                        Player observer = event.getPlayer();
                        if (ProtocolLibManager.this.plugin.getPermissionManager().hasPermission(observer,
                                "vanishpp.see"))
                            return;

                        boolean identifiedVanished = false; // set true if we parsed and found a vanished entry
                        try {
                            PacketContainer packet = event.getPacket();
                            List<PlayerInfoData> entries = packet.getPlayerInfoDataLists().read(0);
                            if (entries == null || entries.isEmpty())
                                return;

                            VanishPlayerInfoPolicy.FilterResult<PlayerInfoData> result = VanishPlayerInfoPolicy.filter(
                                    entries, PlayerInfoData::getProfileId,
                                    ProtocolLibManager.this.plugin::isVanished);

                            if (!result.changed())
                                return;
                            // We now know at least one entry is vanished.
                            identifiedVanished = true;
                            if (result.cancel()) {
                                event.setCancelled(true);
                            } else {
                                packet.getPlayerInfoDataLists().write(0, result.kept());
                            }
                        } catch (Exception e) {
                            // Fail open here, unlike the SCOREBOARD_TEAM scrub. Cancelling the whole
                            // PLAYER_INFO packet destroys every player's skin/tab entry for every
                            // observer — on servers where ProtocolLib cannot parse the 1.21.x packet
                            // shape (e.g. Purpur 1.21.11, which ProtocolLib logs as untested) this
                            // throws on every player join, blanking all skins. Bukkit's own hidePlayer()
                            // remains the primary vanish-visibility layer, so leaving the packet alone
                            // on a parse error is far less harmful than blanking everyone's display.
                            // Log the full stack trace so we can see exactly where it throws (TCC
                            // review follow-up), and note if a vanished entry may have slipped through
                            // because the failure happened after we identified one (e.g. at write).
                            if (VanishPlayerInfoPolicy.failOpenOnParseFailure(identifiedVanished)) {
                                // Intentionally fall through and send the original packet untouched.
                                ProtocolLibManager.this.plugin.getLogger().warning(
                                        "PLAYER_INFO scrub skipped (parse error; fail-open). identifiedVanished="
                                                + identifiedVanished + " — " + e);
                            } else {
                                event.setCancelled(true);
                            }
                        }
                    }
                });
        }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register PLAYER_INFO scrubbing listener: " + e.getMessage());
        }

        // 4. Ghost-Proof Spawning (Hiding SPAWN_ENTITY — NAMED_ENTITY_SPAWN removed in 1.21)
        try {
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
                    Entity entity = protocolManager.getEntityFromID(event.getPlayer().getWorld(), entityId);

                    if (entity instanceof Player target
                            && ProtocolLibManager.this.plugin.isVanished(target.getUniqueId())) {
                        event.setCancelled(true);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register ghost-proof spawning listener: " + e.getMessage());
        }

        // 5. Final Stealth Fix: Scrub SCOREBOARD_TEAM packets
        //
        // Regression test: VanishTeamPacketPolicyTest. This fix originally scrubbed ADD/REMOVE
        // purely by the observer's CURRENT vanishpp.see permission, which crashed clients
        // ("Player is either on another team or not on any team") whenever an observer was
        // granted vanishpp.see live (e.g. via LuckPerms, no relog) between a vanish and the
        // matching unvanish — their client never received the ADD while non-staff, but the old
        // logic let the REMOVE through unfiltered once they became staff. See
        // VanishTeamPacketPolicy for the per-observer knowledge tracking that replaced it.
        try {
        protocolManager.addPacketListener(
                new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SCOREBOARD_TEAM) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        if (event.isCancelled())
                            return;

                        try {
                            PacketContainer packet = event.getPacket();
                            int action = packet.getIntegers().read(0);
                            String teamName = packet.getStrings().read(0);

                            if (!"Vanishpp_Vanished".equals(teamName)) {
                                // Not our team — leave any other plugin/vanilla team packets alone
                                // aside from the pre-existing generic per-name vanish scrub below.
                                if (ProtocolLibManager.this.plugin.getPermissionManager()
                                        .hasPermission(event.getPlayer(), "vanishpp.see")) {
                                    return;
                                }
                                if (action == 0 || action == 3 || action == 4) {
                                    scrubGenericTeamPacket(packet, action, event);
                                }
                                return;
                            }

                            if (action != VanishTeamPacketPolicy.ACTION_CREATE
                                    && action != VanishTeamPacketPolicy.ACTION_ADD_PLAYERS
                                    && action != VanishTeamPacketPolicy.ACTION_REMOVE_PLAYERS) {
                                return;
                            }

                            Player observer = event.getPlayer();
                            Collection<String> names = packet.getSpecificModifier(Collection.class).read(0);
                            if (names == null || names.isEmpty())
                                return;

                            boolean isStaff = ProtocolLibManager.this.plugin.getPermissionManager()
                                    .hasPermission(observer, "vanishpp.see");
                            Set<String> known = ProtocolLibManager.this.knownVanishTeamMembers
                                    .computeIfAbsent(observer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());

                            VanishTeamPacketPolicy.Decision decision = VanishTeamPacketPolicy.decide(
                                    action, isStaff, names, known,
                                    name -> {
                                        Player p = Bukkit.getPlayer(name);
                                        return p != null && ProtocolLibManager.this.plugin.isVanished(p.getUniqueId());
                                    });

                            if (decision.cancel()) {
                                event.setCancelled(true);
                            } else if (decision.names().size() != names.size()) {
                                packet.getSpecificModifier(Collection.class).write(0, decision.names());
                            }
                        } catch (Exception e) {
                            // Fail closed: if we can't verify a vanished name isn't in this
                            // packet, don't forward it unscrubbed. Losing a legitimate team UI
                            // update is an acceptable cost; leaking a vanished player's name
                            // (e.g. to a third-party team plugin's own packets, handled by
                            // scrubGenericTeamPacket below) is not.
                            event.setCancelled(true);
                            ProtocolLibManager.this.plugin.getLogger().warning(
                                    "Failed to scrub SCOREBOARD_TEAM packet, cancelling for safety: " + e.getMessage());
                        }
                    }

                    /** Pre-existing generic scrub for any OTHER (non-vanish) team's member list. */
                    private void scrubGenericTeamPacket(PacketContainer packet, int action, PacketEvent event) {
                        try {
                            Collection<String> players = packet.getSpecificModifier(Collection.class).read(0);
                            if (players == null) return;
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
                        } catch (Exception e) {
                            // Fail closed - same reasoning as the outer catch above. This is
                            // the path that actually applies to third-party team plugins
                            // (e.g. BetterTeams): a packet shape this code doesn't recognize
                            // must not be forwarded unscrubbed just because reading it failed.
                            event.setCancelled(true);
                            ProtocolLibManager.this.plugin.getLogger().warning(
                                    "Failed to scrub third-party SCOREBOARD_TEAM packet, cancelling for safety: " + e.getMessage());
                        }
                    }
                });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register scoreboard team scrubbing listener: " + e.getMessage());
        }

        // 6. Server List Ping
        try {
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register server-list-ping listener: " + e.getMessage());
        }

        // Native vanishTeam.prefix() handles the nametag prefix for staff already.
    }

    private void registerSilentChestListeners() {
        // Suppress BLOCK_ACTION (chest open/close animation, shulker open/close animation)
        // for non-seers when the block is in the silently-opened set
        try {
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register silent-chest animation listener: " + e.getMessage());
        }

        // Suppress sound effects originating at silently-opened block positions.
        // Vanilla block sounds use NAMED_SOUND_EFFECT; custom sounds use CUSTOM_SOUND_EFFECT.
        // CUSTOM_SOUND_EFFECT is absent on some MC versions — only register it when supported.
        // All sound packets in 1.21+ store coordinates as fixed-point ints (actual_coord * 8).
        try {
        List<PacketType> soundPacketTypes = new java.util.ArrayList<>();
        soundPacketTypes.add(PacketType.Play.Server.NAMED_SOUND_EFFECT);
        if (PacketType.Play.Server.CUSTOM_SOUND_EFFECT.isSupported())
            soundPacketTypes.add(PacketType.Play.Server.CUSTOM_SOUND_EFFECT);
        PacketAdapter soundListener = new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                soundPacketTypes) {
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register silent-chest sound listener: " + e.getMessage());
        }
    }

    /**
     * Intercepts the vanilla action-bar "X/Y players are sleeping" message
     * ({@code sleep.players_sleeping} TranslatableComponent) and corrects the displayed count so
     * vanished players do not appear in it.
     *
     * <p>Background (verified against Paper/Purpur {@code ServerLevel#announceSleepStatus()}): the
     * server broadcasts the same component to every player in the world, with args
     * {@code [amountSleeping, sleepersNeeded]}. {@code setSleepingIgnored(true)} marks a vanished
     * player as sleeping ({@code fauxSleeping}), so they are counted in BOTH the numerator and the
     * denominator — yielding "2/3" for a world with one vanished player plus one real sleeper among
     * three online, instead of the desired "1/2". Paper/Purpur offer no API to drop a player from
     * the {@code activePlayers} denominator (only spectator or a different dimension does), so the
     * cleanest plugin-side fix is to rewrite the broadcast component's args: subtract the count of
     * vanished players in the world from both the numerator and the denominator.
     *
     * <p>This only ever affects a cosmetic action-bar message, so it fails open on error — unlike the
     * leak-prevention listeners, leaving the original message untouched is always safe here.
     */
    private void registerSleepStatusListener() {
        try {
            if (PacketType.Play.Server.SYSTEM_CHAT.isSupported()) {
                protocolManager.addPacketListener(
                        new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SYSTEM_CHAT) {
                            @Override
                            public void onPacketSending(PacketEvent event) {
                                if (event.isCancelled())
                                    return;
                                try {
                                    PacketContainer packet = event.getPacket();
                                    if (packet.getChatComponents().size() == 0)
                                        return;
                                    WrappedChatComponent wrapped = packet.getChatComponents().read(0);
                                    net.kyori.adventure.text.Component component =
                                            com.comphenix.protocol.wrappers.AdventureComponentConverter.fromWrapper(wrapped);
                                    if (!(component instanceof net.kyori.adventure.text.TranslatableComponent tc))
                                        return;
                                    if (!"sleep.players_sleeping".equals(tc.key()))
                                        return;
                                    java.util.List<net.kyori.adventure.text.Component> args = tc.args();
                                    if (args.size() < 2)
                                        return;
                                    int numerator = 0;
                                    int denominator = 0;
                                    try {
                                        numerator = Integer.parseInt(adventurePlain(args.get(0)));
                                        denominator = Integer.parseInt(adventurePlain(args.get(1)));
                                    } catch (NumberFormatException nfe) {
                                        return;
                                    }
                                    int vanishedHere = ProtocolLibManager.this.vanishedCountIn(event.getPlayer().getWorld());
                                    int[] newArgs = ProtocolLibManager.rewriteSleepingArgs(numerator, denominator, vanishedHere);
                                    if (newArgs == null)
                                        return;

                                    net.kyori.adventure.text.Component fixed = net.kyori.adventure.text.Component.translatable(
                                            tc.key(), tc.style(), java.util.Arrays.asList(
                                                    net.kyori.adventure.text.Component.text(newArgs[0]),
                                                    net.kyori.adventure.text.Component.text(newArgs[1])));
                                    packet.getChatComponents().write(0,
                                            com.comphenix.protocol.wrappers.AdventureComponentConverter.fromComponent(fixed));
                                } catch (Exception e) {
                                    // Fail open: this is a cosmetic message, never block it.
                                    ProtocolLibManager.this.plugin.getLogger().fine(
                                            "Sleep-status rewrite skipped: " + e.getMessage());
                                }
                            }
                        });
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register sleep-status display listener: " + e.getMessage());
        }
    }

    /** Counts how many players in the given world are currently vanished (included in the sleep count). */
    private int vanishedCountIn(org.bukkit.World world) {
        if (world == null)
            return 0;
        int count = 0;
        for (UUID uuid : ProtocolLibManager.this.plugin.getRawVanishedPlayers()) {
            org.bukkit.entity.Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.getWorld() == world)
                count++;
        }
        return count;
    }

    /**
     * Pure decision logic for rewriting the {@code sleep.players_sleeping} action-bar component args,
     * extracted out of the packet listener so it can be unit-tested without ProtocolLib. A vanished
     * player counts in both the numerator (via {@code fauxSleeping}) and the denominator (via
     * {@code activePlayers}), so to show e.g. "1/2" instead of "2/3" we subtract the vanished count
     * from both. Never lets either number drop below 1, and returns null when nothing needs to change.
     */
    static int[] rewriteSleepingArgs(int numerator, int denominator, int vanishedCount) {
        if (vanishedCount <= 0)
            return null;
        int newNum = Math.max(1, numerator - vanishedCount);
        int newDen = Math.max(1, denominator - vanishedCount);
        if (newNum == numerator && newDen == denominator)
            return null;
        return new int[]{newNum, newDen};
    }

    /** Serializes a simple adventure component (text or translatable-with-text-args) to its plain text. */
    private static String adventurePlain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    /**
     * Send an ENTITY_METADATA packet with the glow flag to all staff observers.
     * This forces the client to render the glow outline without waiting for a natural
     * metadata change (like sneaking).
     */
    public void sendGlowMetadata(Player vanished) {
        if (!plugin.getConfigManager().staffGlowEnabled) return;
        try {
            for (Player observer : new java.util.ArrayList<>(Bukkit.getOnlinePlayers())) {
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