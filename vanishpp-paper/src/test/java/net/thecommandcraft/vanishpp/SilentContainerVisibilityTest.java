package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.listeners.PlayerListener;
import net.thecommandcraft.vanishpp.utils.VanishScheduler;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SilentContainerVisibilityTest {
    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock player;
    private PlayerListener listener;
    private final List<Runnable> closes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        player = server.addPlayer();
        player.setOp(true);
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);
        set("hasProtocolLib", true);
        VanishScheduler scheduler = mock(VanishScheduler.class);
        when(scheduler.owns(any())).thenReturn(true);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(scheduler).runEntityNow(any(), any());
        doAnswer(call -> { closes.add(call.getArgument(0)); return null; }).when(scheduler).runLaterGlobal(any(), eq(60L));
        set("vanishScheduler", scheduler);
        listener = new PlayerListener(plugin);
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    @Test
    void oldCloseCannotRetireReopenedContainer() {
        Block chest = server.addSimpleWorld("containers").getBlockAt(3, 64, 7);
        chest.setType(Material.CHEST);
        open(chest);
        assertFalse(plugin.silentlyOpenedBlocks.isEmpty(), "open session must suppress its container");
        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));
        assertEquals(1, closes.size());
        open(chest);
        closes.get(0).run();
        assertFalse(plugin.silentlyOpenedBlocks.isEmpty(), "old close timer must not retire a reopened container");
        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));
        closes.get(1).run();
        assertTrue(plugin.silentlyOpenedBlocks.isEmpty(), "last session must retire after its own close window");
    }

    @Test
    void closingOneViewerDoesNotRetireAnotherViewer() {
        Block chest = server.addSimpleWorld("shared").getBlockAt(3, 64, 7);
        chest.setType(Material.CHEST);
        open(chest);
        PlayerMock first = player;
        player = server.addPlayer();
        player.setOp(true);
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);
        open(chest);
        listener.onClose(new InventoryCloseEvent(first.getOpenInventory()));
        closes.get(0).run();
        assertFalse(plugin.silentlyOpenedBlocks.isEmpty(), "another viewer still owns silent-container suppression");
    }

    @Test
    void equalCoordinatesInDifferentWorldsHaveIndependentSessions() {
        Block first = server.addSimpleWorld("first").getBlockAt(3, 64, 7);
        Block second = server.addSimpleWorld("second").getBlockAt(3, 64, 7);
        first.setType(Material.CHEST);
        second.setType(Material.CHEST);
        open(first);
        player = server.addPlayer();
        player.setOp(true);
        plugin.applyVanishEffects(player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);
        open(second);
        assertEquals(2, plugin.silentlyOpenedBlocks.size(), "silent container positions must include the world");
    }

    private void open(Block chest) {
        listener.onInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, chest,
                BlockFace.UP, EquipmentSlot.HAND));
    }

    private void set(String name, Object value) throws Exception {
        var field = Vanishpp.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
