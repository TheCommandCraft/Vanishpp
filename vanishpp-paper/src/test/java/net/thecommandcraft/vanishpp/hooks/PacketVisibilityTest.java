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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        StringRange range = StringRange.between(5, 8);
        Suggestion visible = new Suggestion(range, viewer.getName(), () -> "visible tooltip");
        Suggestions suggestions = new Suggestions(range, List.of(new Suggestion(range, subject.getName()), visible));
        Data data = new Data();
        data.suggestions = suggestions;
        PacketEvent event = dispatch(PacketType.Play.Server.TAB_COMPLETE, packet(data));
        assertEquals(2, data.suggestions.getList().size(), "shared source packet must remain unchanged");
        data = (Data) event.getPacket().getHandle();
        assertEquals(List.of(visible), data.suggestions.getList(), "Brigadier suggestions must exclude hidden names");
        assertEquals(range, data.suggestions.getRange());
        assertEquals("visible tooltip", data.suggestions.getList().get(0).getTooltip().getString());
    }

    @Test
    void nativeSuggestionEntriesPreserveVisibleTooltipAndRangeFields() {
        NativeSuggestions data = new NativeSuggestions();
        Object tooltip = new Object();
        SuggestionEntry visible = new SuggestionEntry(viewer.getName(), Optional.of(tooltip));
        data.suggestions = new ArrayList<>(List.of(new SuggestionEntry(subject.getName(), Optional.empty()), visible));
        PacketEvent event = dispatch(PacketType.Play.Server.TAB_COMPLETE, packet(data));
        assertEquals(2, data.suggestions.size(), "shared native suggestion source must remain unchanged");
        data = (NativeSuggestions) event.getPacket().getHandle();
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
        Data data = new Data();
        Entry visible = new Entry(viewer.getUniqueId());
        data.entries = new ArrayList<>(List.of(new Entry(subject.getUniqueId()), visible));
        PacketEvent event = dispatch(PacketType.Play.Server.PLAYER_INFO, packet(data));
        assertEquals(2, data.entries.size(), "shared player-info source must remain unchanged");
        assertEquals(List.of(visible), ((Data) event.getPacket().getHandle()).entries, "player-info must filter targets above the observer's level");
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

    private PacketContainer teamPacket(TeamData data) { return packet(data); }

    @Test
    void sharedPlayerInfoIsIsolatedInBothViewerOrders() {
        for (boolean firstAuthorized : new boolean[]{false, true}) {
            Data source = new Data();
            source.entries = List.of(new Entry(subject.getUniqueId()), new Entry(viewer.getUniqueId()));
            PacketContainer shared = packet(source);
            for (boolean authorized : new boolean[]{firstAuthorized, !firstAuthorized}) {
                viewer.setOp(authorized);
                PacketEvent event = dispatch(PacketType.Play.Server.PLAYER_INFO, shared);
                Data sent = (Data) event.getPacket().getHandle();
                assertEquals(authorized ? 2 : 1, sent.entries.size());
                assertEquals(2, source.entries.size(), "filtering one viewer must not mutate another viewer's source packet");
                if (!authorized) assertNotSame(shared, event.getPacket());
            }
        }
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
        var original = openBarrel(3, 64, 7);
        WrappedBlockData open = blockData(Material.BARREL);
        WrappedBlockData closed = blockData(Material.BARREL);
        BlockDataPacket source = new BlockDataPacket();
        source.position = new BlockPosition(3, 64, 7);
        source.data = open;
        try (var wrapped = closedEncoder(closed)) {
            PacketEvent event = dispatch(PacketType.Play.Server.BLOCK_CHANGE, packet(source));
            assertFalse(event.isCancelled());
            assertSame(closed, ((BlockDataPacket) event.getPacket().getHandle()).data,
                    "silent barrel block update must preserve the observer's closed state");
            assertSame(open, source.data, "source packet must retain the actual open state");
            assertTrue(original.isOpen(), "closing the observer copy must not close the real barrel state");
        }
    }

    @ParameterizedTest
    @CsvSource({"-1,2", "2,-1"})
    void sectionUpdatesOnlyCloseTheSuppressedBarrel(int sectionX, int sectionZ) throws Exception {
        var original = openBarrel((sectionX << 4) + 3, 69, (sectionZ << 4) + 7);
        WrappedBlockData open = blockData(Material.BARREL), closed = blockData(Material.BARREL);
        WrappedBlockData stone = blockData(Material.STONE), otherBarrel = blockData(Material.BARREL);
        SectionDataPacket source = new SectionDataPacket();
        source.section = new BlockPosition(sectionX, 4, sectionZ);
        source.positions = new short[]{(short) ((3 << 8) | (7 << 4) | 5), (short) ((8 << 8) | (1 << 4) | 2), (short) ((6 << 8) | (9 << 4) | 3)};
        source.data = new WrappedBlockData[]{open, stone, otherBarrel};
        try (var wrapped = closedEncoder(closed)) {
            PacketEvent event = dispatch(PacketType.Play.Server.MULTI_BLOCK_CHANGE, packet(source));
            assertFalse(event.isCancelled());
            var sent = (SectionDataPacket) event.getPacket().getHandle();
            assertSame(closed, sent.data[0], "section decoding must close the suppressed barrel at its world position");
            assertSame(stone, sent.data[1], "ordinary section entries must be preserved");
            assertSame(otherBarrel, sent.data[2], "unrelated barrels must be preserved");
            assertSame(open, source.data[0], "section source array must remain unchanged");
            assertTrue(original.isOpen());
        }
    }

    @ParameterizedTest
    @CsvSource({"barrel", "suggestions"})
    void malformedPacketsFailClosedWithRateLimitedContext(String operation) throws Exception {
        openBarrel(3, 64, 7);
        PacketType type = operation.equals("barrel") ? PacketType.Play.Server.BLOCK_CHANGE : PacketType.Play.Server.TAB_COMPLETE;
        PacketContainer broken = mock(PacketContainer.class);
        IllegalStateException failure = new IllegalStateException("fixture decode failure");
        if (operation.equals("barrel")) when(broken.getBlockPositionModifier()).thenThrow(failure);
        else when(broken.getSpecificModifier(Suggestions.class)).thenThrow(failure);
        List<java.util.logging.LogRecord> records = new ArrayList<>();
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { if (record.getThrown() == failure) records.add(record); }
            public void flush() {}
            public void close() {}
        };
        plugin.getLogger().addHandler(handler);
        try {
            assertTrue(dispatch(type, broken).isCancelled());
            assertTrue(dispatch(type, broken).isCancelled());
            assertEquals(1, records.size(), "packet decode failures need one rate-limited contextual diagnostic");
            assertTrue(records.get(0).getMessage().contains(type.name()));
            assertTrue(records.get(0).getMessage().contains(operation));
            assertSame(failure, records.get(0).getThrown());
        } finally { plugin.getLogger().removeHandler(handler); }
    }

    private org.bukkit.block.data.Openable openBarrel(int x, int y, int z) throws Exception {
        subject.setOp(true);
        plugin.getRuleManager().setRule(subject, RuleManager.CAN_INTERACT, true);
        var field = Vanishpp.class.getDeclaredField("hasProtocolLib");
        field.setAccessible(true);
        field.set(plugin, true);
        var block = spy(subject.getWorld().getBlockAt(x, y, z));
        block.setType(Material.BARREL);
        var original = barrelState(true);
        when(block.getBlockData()).thenReturn(original);
        new PlayerListener(plugin).onInteract(new PlayerInteractEvent(subject, Action.RIGHT_CLICK_BLOCK, null,
                block, BlockFace.UP, EquipmentSlot.HAND));
        return original;
    }

    private org.bukkit.block.data.Openable barrelState(boolean initiallyOpen) {
        var state = new java.util.concurrent.atomic.AtomicBoolean(initiallyOpen);
        var data = mock(org.bukkit.block.data.Openable.class);
        when(data.isOpen()).thenAnswer(call -> state.get());
        doAnswer(call -> { state.set(call.getArgument(0)); return null; }).when(data).setOpen(anyBoolean());
        when(data.clone()).thenAnswer(call -> barrelState(state.get()));
        return data;
    }

    private WrappedBlockData blockData(Material material) {
        var data = mock(WrappedBlockData.class);
        when(data.getType()).thenReturn(material);
        return data;
    }

    private MockedStatic<WrappedBlockData> closedEncoder(WrappedBlockData closed) {
        var wrapped = mockStatic(WrappedBlockData.class);
        wrapped.when(() -> WrappedBlockData.createData(any(Object.class))).thenAnswer(call -> {
            Object state = call.getArgument(0);
            assertTrue(state instanceof org.bukkit.block.data.Openable);
            assertFalse(((org.bukkit.block.data.Openable) state).isOpen(), "observer barrel state must be explicitly closed");
            return closed;
        });
        return wrapped;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private PacketContainer packet(Object handle) {
        PacketContainer packet = mock(PacketContainer.class);
        StructureModifier<Object> modifier = new StructureModifier<>(handle.getClass()).withTarget(handle);
        when(packet.getHandle()).thenReturn(handle);
        when(packet.getModifier()).thenReturn(modifier);
        when(packet.getStrings()).thenReturn(modifier.withType(String.class));
        when(packet.getIntegers()).thenReturn(modifier.withType(int.class));
        when(packet.getStringArrays()).thenReturn(modifier.withType(String[].class));
        when(packet.getBlockPositionModifier()).thenReturn(modifier.withType(BlockPosition.class));
        when(packet.getSectionPositions()).thenReturn(modifier.withType(BlockPosition.class));
        when(packet.getBlockData()).thenReturn(modifier.withType(WrappedBlockData.class));
        when(packet.getBlockDataArrays()).thenReturn(modifier.withType(WrappedBlockData[].class));
        when(packet.getShortArrays()).thenReturn(modifier.withType(short[].class));
        when(packet.getSpecificModifier(any(Class.class))).thenAnswer(call -> modifier.withType((Class) call.getArgument(0)));
        when(packet.shallowClone()).thenAnswer(call -> {
            Object copy = handle.getClass().getDeclaredConstructor().newInstance();
            for (var field : handle.getClass().getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    field.set(copy, field.get(handle));
                }
            }
            return packet(copy);
        });
        return packet;
    }

    private PacketEvent dispatch(PacketType type, PacketContainer packet) {
        PacketEvent event = mock(PacketEvent.class);
        when(event.getPlayer()).thenReturn(viewer);
        AtomicReference<PacketContainer> current = new AtomicReference<>(packet);
        when(event.getPacket()).thenAnswer(call -> current.get());
        doAnswer(call -> { current.set(call.getArgument(0)); return null; }).when(event).setPacket(any());
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
        public TeamData() {}
        TeamData(int action, String name) { this.action = action; this.names = List.of(name); }
    }
    public static class BlockDataPacket {
        public BlockPosition position;
        public WrappedBlockData data;
    }
    public static class SectionDataPacket {
        public BlockPosition section;
        public short[] positions;
        public WrappedBlockData[] data;
    }
    public static class NativeSuggestions {
        public int id = 1, start = 5, length = 3;
        public List<Object> suggestions;
    }
}
