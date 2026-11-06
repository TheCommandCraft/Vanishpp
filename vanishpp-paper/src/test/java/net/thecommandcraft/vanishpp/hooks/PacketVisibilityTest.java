package net.thecommandcraft.vanishpp.hooks;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.comphenix.protocol.*;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.injector.packet.PacketRegistry;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.*;
import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.*;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.listeners.PlayerListener;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketVisibilityTest {
    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock subject, viewer;
    private final List<PacketListener> listeners = new ArrayList<>();
    private MockedStatic<ProtocolLibrary> library;
    private MockedStatic<PacketRegistry> registry;
    private MockedStatic<MinecraftReflection> reflection;

    public static class Profile {}
    public static class Human { public Profile profile; }
    public static class NativePlayer extends Human {}

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        subject = server.addPlayer("HiddenStaff");
        viewer = server.addPlayer("Observer");
        plugin.applyVanishEffects(subject);
        reflection = mockStatic(MinecraftReflection.class);
        reflection.when(MinecraftReflection::getGameProfileClass).thenReturn(Profile.class);
        reflection.when(MinecraftReflection::getEntityPlayerClass).thenReturn(NativePlayer.class);
        reflection.when(() -> MinecraftReflection.getCraftBukkitClass("util.CraftMagicNumbers")).thenReturn(Object.class);
        reflection.when(MinecraftReflection::getIBlockDataClass).thenReturn(Object.class);
        registry = mockStatic(PacketRegistry.class);
        registry.when(() -> PacketRegistry.isSupported(any())).thenReturn(true);
        library = mockStatic(ProtocolLibrary.class);
        ProtocolManager manager = mock(ProtocolManager.class);
        library.when(ProtocolLibrary::getProtocolManager).thenReturn(manager);
        doAnswer(call -> { listeners.add(call.getArgument(0)); return null; }).when(manager).addPacketListener(any());
        MockBukkit.createMockPlugin("ProtocolLib");
        new ProtocolLibManager(plugin).load();
    }

    @AfterEach
    void tearDown() {
        if (library != null) library.close();
        if (registry != null) registry.close();
        if (reflection != null) reflection.close();
        MockBukkit.unmock();
    }

    @Test
    void modernSuggestionsKeepRangesAndTooltipsWithoutHiddenNames() {
        PacketContainer packet = mock(PacketContainer.class);
        StructureModifier<String[]> legacy = mock(StructureModifier.class);
        when(packet.getStringArrays()).thenReturn(legacy);
        StringRange range = StringRange.between(5, 8);
        Suggestion visible = new Suggestion(range, viewer.getName(), () -> "visible tooltip");
        Suggestions suggestions = new Suggestions(range, List.of(new Suggestion(range, subject.getName()), visible));
        Data data = new Data();
        data.suggestions = suggestions;
        StructureModifier<Object> modifier = new StructureModifier<>(Data.class).withTarget(data);
        when(packet.getModifier()).thenReturn(modifier);
        when(packet.getSpecificModifier(Suggestions.class)).thenReturn(modifier.withType(Suggestions.class));
        dispatch(PacketType.Play.Server.TAB_COMPLETE, packet);
        assertEquals(List.of(visible), data.suggestions.getList(), "Brigadier suggestions must exclude hidden names");
        assertEquals(range, data.suggestions.getRange());
        assertEquals("visible tooltip", data.suggestions.getList().get(0).getTooltip().getString());
    }

    @Test
    void nativeSuggestionEntriesPreserveVisibleTooltipAndRangeFields() {
        PacketContainer packet = mock(PacketContainer.class);
        StructureModifier<String[]> legacy = mock(StructureModifier.class);
        when(packet.getStringArrays()).thenReturn(legacy);
        NativeSuggestions data = new NativeSuggestions();
        Object tooltip = new Object();
        SuggestionEntry visible = new SuggestionEntry(viewer.getName(), Optional.of(tooltip));
        data.suggestions = new ArrayList<>(List.of(new SuggestionEntry(subject.getName(), Optional.empty()), visible));
        StructureModifier<Object> modifier = new StructureModifier<>(NativeSuggestions.class).withTarget(data);
        when(packet.getModifier()).thenReturn(modifier);
        when(packet.getSpecificModifier(Suggestions.class)).thenReturn(modifier.withType(Suggestions.class));
        dispatch(PacketType.Play.Server.TAB_COMPLETE, packet);
        assertEquals(List.of(visible), data.suggestions, "native suggestion entries must exclude hidden names");
        assertEquals(5, data.start);
        assertEquals(3, data.length);
        assertSame(tooltip, ((SuggestionEntry) data.suggestions.get(0)).tooltip().orElseThrow());
    }

    @Test
    void playerInfoDoesNotBypassLayeredVisibility() {
        plugin.getConfigManager().layeredPermsEnabled = true;
        viewer.addAttachment(plugin, "vanishpp.see", true);
        viewer.addAttachment(plugin, "vanishpp.see.level.1", true);
        subject.addAttachment(plugin, "vanishpp.vanish.level.10", true);
        PacketContainer packet = mock(PacketContainer.class);
        Data data = new Data();
        Entry visible = new Entry(viewer.getUniqueId());
        data.entries = new ArrayList<>(List.of(new Entry(subject.getUniqueId()), visible));
        when(packet.getModifier()).thenReturn(new StructureModifier<>(Data.class).withTarget(data));
        dispatch(PacketType.Play.Server.PLAYER_INFO, packet);
        assertEquals(List.of(visible), data.entries, "player-info must filter targets above the observer's level");
    }

    @Test
    void teamFilteringUsesTargetLevelAndRetainsRemovalKnowledge() {
        plugin.getConfigManager().layeredPermsEnabled = true;
        viewer.addAttachment(plugin, "vanishpp.see", true);
        viewer.addAttachment(plugin, "vanishpp.see.level.1", true);
        subject.addAttachment(plugin, "vanishpp.vanish.level.10", true);
        TeamData hidden = new TeamData(3, subject.getName());
        PacketEvent denied = dispatch(PacketType.Play.Server.SCOREBOARD_TEAM, teamPacket(hidden));
        assertTrue(denied.isCancelled() || hidden.names.isEmpty(), "team membership must respect target visibility levels");
        viewer.setOp(true);
        TeamData shown = new TeamData(3, subject.getName());
        assertFalse(dispatch(PacketType.Play.Server.SCOREBOARD_TEAM, teamPacket(shown)).isCancelled());
        assertTrue(shown.names.contains(subject.getName()));
        viewer.setOp(false);
        TeamData removal = new TeamData(4, subject.getName());
        assertFalse(dispatch(PacketType.Play.Server.SCOREBOARD_TEAM, teamPacket(removal)).isCancelled(),
                "demoted viewer must receive removal of a name their client knows");
        TeamData duplicate = new TeamData(4, subject.getName());
        assertTrue(dispatch(PacketType.Play.Server.SCOREBOARD_TEAM, teamPacket(duplicate)).isCancelled(),
                "client must never receive removal of an unknown membership");
    }

    private PacketContainer teamPacket(TeamData data) {
        PacketContainer packet = mock(PacketContainer.class);
        StructureModifier<Object> modifier = new StructureModifier<>(TeamData.class).withTarget(data);
        when(packet.getStrings()).thenReturn(modifier.withType(String.class));
        when(packet.getIntegers()).thenReturn(modifier.withType(int.class));
        when(packet.getSpecificModifier(Collection.class)).thenReturn(modifier.withType(Collection.class));
        return packet;
    }

    @Test
    void offlineVanishStateDoesNotReducePacketPingCount() {
        plugin.handleNetworkVanishSync(UUID.randomUUID(), true);
        plugin.getConfigManager().adjustServerListCount = true;
        PacketContainer packet = mock(PacketContainer.class);
        WrappedServerPing ping = mock(WrappedServerPing.class);
        when(ping.getPlayers()).thenReturn(ImmutableList.of());
        AtomicInteger count = new AtomicInteger(2);
        doAnswer(call -> { count.set(call.getArgument(0)); return null; }).when(ping).setPlayersOnline(anyInt());
        StructureModifier<WrappedServerPing> pings = mock(StructureModifier.class);
        when(pings.read(0)).thenReturn(ping);
        when(packet.getServerPings()).thenReturn(pings);
        dispatch(PacketType.Status.Server.SERVER_INFO, packet);
        assertEquals(1, count.get(), "packet ping must subtract online vanished players only");
    }

    @Test
    void silentBarrelOpenStateDoesNotReachObserver() throws Exception {
        subject.setOp(true);
        plugin.getRuleManager().setRule(subject, RuleManager.CAN_INTERACT, true);
        var field = Vanishpp.class.getDeclaredField("hasProtocolLib");
        field.setAccessible(true);
        field.set(plugin, true);
        var block = spy(subject.getWorld().getBlockAt(3, 64, 7));
        block.setType(Material.BARREL);
        org.bukkit.block.data.Openable barrel = mock(org.bukkit.block.data.Openable.class);
        when(barrel.clone()).thenReturn(barrel);
        when(block.getBlockData()).thenReturn(barrel);
        new PlayerListener(plugin).onInteract(new PlayerInteractEvent(subject, Action.RIGHT_CLICK_BLOCK, null,
                block, BlockFace.UP, EquipmentSlot.HAND));
        PacketContainer packet = mock(PacketContainer.class);
        StructureModifier<BlockPosition> positions = mock(StructureModifier.class);
        when(positions.read(0)).thenReturn(new BlockPosition(3, 64, 7));
        when(packet.getBlockPositionModifier()).thenReturn(positions);
        WrappedBlockData open = mock(WrappedBlockData.class);
        WrappedBlockData closed = mock(WrappedBlockData.class);
        when(open.getType()).thenReturn(Material.BARREL);
        AtomicReference<WrappedBlockData> sent = new AtomicReference<>(open);
        StructureModifier<WrappedBlockData> data = mock(StructureModifier.class);
        when(packet.getBlockData()).thenReturn(data);
        when(data.read(0)).thenAnswer(call -> sent.get());
        doAnswer(call -> { sent.set(call.getArgument(1)); return data; }).when(data).write(eq(0), any());
        try (var wrapped = mockStatic(WrappedBlockData.class)) {
            wrapped.when(() -> WrappedBlockData.createData(any(Object.class))).thenAnswer(call -> {
                Object state = call.getArgument(0);
                assertTrue(state instanceof org.bukkit.block.data.Openable);
                assertFalse(((org.bukkit.block.data.Openable) state).isOpen());
                return closed;
            });
            PacketEvent event = dispatch(PacketType.Play.Server.BLOCK_CHANGE, packet);
            assertFalse(event.isCancelled(), "barrel filtering should preserve the rest of the block update");
            assertSame(closed, sent.get(), "silent barrel block update must preserve the observer's closed state");
        }
    }

    private PacketEvent dispatch(PacketType type, PacketContainer packet) {
        when(packet.shallowClone()).thenReturn(packet);
        PacketEvent event = mock(PacketEvent.class);
        when(event.getPlayer()).thenReturn(viewer);
        when(event.getPacket()).thenReturn(packet);
        when(event.getPacketType()).thenReturn(type);
        boolean[] cancelled = {false};
        doAnswer(call -> { cancelled[0] = call.getArgument(0); return null; }).when(event).setCancelled(anyBoolean());
        when(event.isCancelled()).thenAnswer(call -> cancelled[0]);
        for (PacketListener listener : listeners)
            if (listener.getSendingWhitelist().getTypes().contains(type)) listener.onPacketSending(event);
        return event;
    }

    public static class Data {
        public Suggestions suggestions;
        public List<Object> entries;
    }
    public record Entry(UUID profileId) {}
    public record SuggestionEntry(String text, Optional<Object> tooltip) {}
    public static class TeamData {
        public String team = "Vanishpp_Vanished";
        public int action;
        public Collection<String> names;
        TeamData(int action, String name) { this.action = action; this.names = List.of(name); }
    }
    public static class NativeSuggestions {
        public int id = 1, start = 5, length = 3;
        public List<Object> suggestions;
    }
}
