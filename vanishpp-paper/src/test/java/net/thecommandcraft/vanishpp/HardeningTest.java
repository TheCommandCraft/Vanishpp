package net.thecommandcraft.vanishpp;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.thecommandcraft.vanishpp.api.events.*;
import net.thecommandcraft.vanishpp.commands.VanishHistoryCommand;
import net.thecommandcraft.vanishpp.config.RuleManager;
import net.thecommandcraft.vanishpp.gui.*;
import net.thecommandcraft.vanishpp.hooks.WebhookManager;
import net.thecommandcraft.vanishpp.hooks.WorldGuardHook;
import net.thecommandcraft.vanishpp.listeners.*;
import net.thecommandcraft.vanishpp.storage.*;
import net.thecommandcraft.vanishpp.utils.*;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HardeningTest {
    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock player;
    private ControlledScheduler scheduler;
    private final Map<Inventory, Component> titles = new IdentityHashMap<>();
    private boolean countScoreboardAccesses;
    private int scoreboardAccesses;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock(new ServerMock() {
            @Override public be.seeseemelk.mockbukkit.scoreboard.ScoreboardManagerMock getScoreboardManager() {
                if (countScoreboardAccesses) scoreboardAccesses++;
                return super.getScoreboardManager();
            }
            @Override public be.seeseemelk.mockbukkit.inventory.InventoryMock createInventory(InventoryHolder holder, int size, Component title) {
                var inventory = super.createInventory(holder, size, PlainTextComponentSerializer.plainText().serialize(title));
                titles.put(inventory, title);
                return inventory;
            }
        });
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        plugin.getConfigManager().enableFly = false;
        player = new PlayerMock(server, "Viewer") {
            @Override public InventoryView openInventory(Inventory inventory) {
                var view = new be.seeseemelk.mockbukkit.inventory.PlayerInventoryViewMock(this, inventory) {
                    @Override public Component title() { return titles.getOrDefault(inventory, Component.text("Inventory")); }
                    @Override public ItemStack getItem(int rawSlot) {
                        Inventory target = getInventory(rawSlot);
                        return target == null ? null : target.getItem(convertSlot(rawSlot));
                    }
                    @Override public void setItem(int rawSlot, ItemStack item) {
                        Inventory target = getInventory(rawSlot);
                        if (target != null) target.setItem(convertSlot(rawSlot), item);
                    }
                };
                super.openInventory(view);
                return getOpenInventory();
            }
        };
        server.addPlayer(player);
        server.getScheduler().cancelTasks(plugin);
        scheduler = new ControlledScheduler();
        scheduler.owner = player;
        field(plugin, "vanishScheduler", scheduler);
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    @Test
    void readOnlyInvseeCancelsEveryCrossInventoryAction() {
        PlayerMock target = server.addPlayer();
        target.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 32));
        player.openInventory(target.getInventory());
        plugin.invseeTargets.put(player.getUniqueId(), target);
        plugin.invseeViewOnly.add(player.getUniqueId());
        var listener = new PlayerListener(plugin);
        var view = player.getOpenInventory();
        List<InventoryClickEvent> attempts = List.of(
                new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL),
                new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY),
                new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 1),
                new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, view.getTopInventory().getSize(),
                        ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR),
                new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, view.getTopInventory().getSize(),
                        ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY));
        attempts.forEach(listener::onInvseeClickLock);
        InventoryDragEvent drag = new InventoryDragEvent(view, new ItemStack(Material.DIAMOND),
                new ItemStack(Material.DIAMOND, 2), false, Map.of(0, new ItemStack(Material.DIAMOND, 33)));
        listener.onInvseeDragLock(drag);
        assertAll(
                () -> assertTrue(attempts.stream().allMatch(InventoryClickEvent::isCancelled),
                        "read-only invsee must cancel bottom-origin collection as well as takes, shift-clicks and hotbar swaps"),
                () -> assertTrue(drag.isCancelled(), "read-only invsee must cancel a drag into the target"),
                () -> assertEquals(32, target.getInventory().getItem(0).getAmount()));
        plugin.invseeViewOnly.clear();
        InventoryClickEvent allowed = click(0);
        listener.onInvseeClickLock(allowed);
        assertFalse(allowed.isCancelled(), "editable invsee must remain editable");
    }

    @Test
    void rulesGuiRechecksOthersPermissionOnEveryEdit() {
        PlayerMock target = server.addPlayer();
        player.addAttachment(plugin, "vanishpp.rules", true);
        var permission = player.addAttachment(plugin, "vanishpp.rules.others", true);
        RulesGUI gui = new RulesGUI(plugin);
        gui.open(player, target);
        String rule = plugin.getRuleManager().getAvailableRules().stream().sorted().findFirst().orElseThrow();
        boolean original = plugin.getRuleManager().getRule(target, rule);
        permission.setPermission("vanishpp.rules.others", false);
        gui.onClick(click(0));
        boolean afterDenied = plugin.getRuleManager().getRule(target, rule);
        permission.setPermission("vanishpp.rules.others", true);
        gui.onClick(click(0));
        assertAll(
                () -> assertEquals(original, afterDenied, "revoking others permission must prevent the next GUI edit"),
                () -> assertNotEquals(afterDenied, plugin.getRuleManager().getRule(target, rule), "authorized GUI edits must still work"));
    }

    @Test
    void deniedFlightPermissionSurvivesVanishDelayedEnforcementAndResync() {
        plugin.getConfigManager().enableFly = true;
        player.setGameMode(GameMode.SURVIVAL);
        var permission = player.addAttachment(plugin, "vanishpp.fly", false);
        player.setAllowFlight(false);
        plugin.vanishPlayer(player, player);
        boolean immediate = player.getAllowFlight();
        player.setAllowFlight(false);
        scheduler.delayedPass();
        boolean delayed = player.getAllowFlight();
        player.setAllowFlight(false);
        plugin.resyncVanishEffects(player);
        boolean refreshed = player.getAllowFlight();
        player.setAllowFlight(false);
        new PlayerListener(plugin).onGameModeChange(new PlayerGameModeChangeEvent(player, GameMode.SURVIVAL));
        scheduler.delayedPass();
        boolean gamemode = player.getAllowFlight();
        permission.setPermission("vanishpp.fly", true);
        plugin.resyncVanishEffects(player);
        boolean permitted = player.getAllowFlight();
        permission.setPermission("vanishpp.fly", false);
        plugin.resyncVanishEffects(player);
        boolean revoked = player.getAllowFlight();
        PlayerMock existing = server.addPlayer();
        existing.addAttachment(plugin, "vanishpp.fly", false);
        existing.setAllowFlight(true);
        plugin.vanishPlayer(existing, existing); plugin.unvanishPlayer(existing, existing);
        assertAll(
                () -> assertFalse(immediate || delayed || refreshed || gamemode, "every vanish flight grant must require vanishpp.fly"),
                () -> assertTrue(permitted, "a permitted player must still receive flight"),
                () -> assertFalse(revoked, "permission revocation must restore the flight state Vanish++ changed"),
                () -> assertTrue(existing.getAllowFlight(), "denied vanish flight must leave another provider's existing flight intact"));
    }

    @Test
    void worldGuardDenyFlagsGateBothTransitionsBeforeSideEffects() throws Exception {
        WorldGuardHook hook = mock(WorldGuardHook.class);
        field(plugin, "worldGuardHook", hook);
        when(hook.isDenyVanish(player)).thenReturn(true);
        plugin.vanishPlayer(player, player, "denied");
        boolean deniedVanish = plugin.isVanished(player);
        String deniedReason = plugin.getVanishReason(player.getUniqueId());
        state().clear();
        plugin.setVanishReason(player.getUniqueId(), null);
        when(hook.isDenyVanish(player)).thenReturn(false);
        plugin.vanishPlayer(player, player);
        assertTrue(plugin.isVanished(player), "the non-denied control transition must succeed");
        when(hook.isDenyUnvanish(player)).thenReturn(true);
        plugin.unvanishPlayer(player, player);
        assertAll(
                () -> assertFalse(deniedVanish, "WorldGuard deny-vanish must prevent the transition"),
                () -> assertNull(deniedReason, "a denied transition must not save its reason"),
                () -> assertTrue(plugin.isVanished(player), "WorldGuard deny-unvanish must preserve vanished state"));
    }

    @Test
    void customPermissionsAuthorizeExecutorAndDescriptorGatedCommands() {
        player.setOp(false);
        assertFalse(player.hasPermission("vanishpp.vanish"));
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.vanish");
        plugin.getPermissionManager().addPermission(player.getUniqueId(), "vanishpp.wand");
        player.performCommand("vanish");
        player.performCommand("vwand");
        assertAll(
                () -> assertTrue(plugin.isVanished(player), "custom vanish permission must authorize the vanish executor"),
                () -> assertTrue(player.getInventory().contains(Material.valueOf(plugin.getConfigManager().wandMaterial)),
                        "custom permissions must also authorize descriptor-gated commands"));
    }

    @Test
    void pluginHidingPreservesTheServersPluginListPermission() {
        player.setOp(false);
        var attachment = player.addAttachment(plugin, "bukkit.command.plugins", false);
        PluginHider hider = new PluginHider(plugin);
        clearMessages();
        hider.onCommandPreprocess(new PlayerCommandPreprocessEvent(player, "/plugins"));
        String denied = messages();
        attachment.setPermission("bukkit.command.plugins", true);
        hider.onCommandPreprocess(new PlayerCommandPreprocessEvent(player, "/bukkit:plugins"));
        String allowed = messages();
        assertAll(
                () -> assertFalse(denied.contains("Plugins ("), "denied plugin-list permission must not disclose a filtered list"),
                () -> assertTrue(allowed.contains("Plugins ("), "authorized plugin listing must still work"));
    }

    @Test
    void effectRefreshDispatchesPlayerMutationsToTheirOwner() throws Exception {
        var violations = new ArrayList<String>();
        PlayerMock subject = new PlayerMock(server, "RegionStaff") {
            @Override public void setInvisible(boolean value) {
                if (scheduler != null && !scheduler.context.equals("entity")) violations.add(scheduler.context);
                super.setInvisible(value);
            }
        };
        server.addPlayer(subject);
        state().add(subject.getUniqueId());
        scheduler.run("global", () -> plugin.resyncVanishEffects(subject));
        assertTrue(subject.isInvisible(), "the dispatched refresh must actually apply effects");
        assertTrue(violations.isEmpty(), "global callbacks must dispatch player effects to the owning entity scheduler: " + violations);
    }

    @Test
    void visibilityMutationsRunOnEachObserversOwner() {
        List<String> violations = new ArrayList<>();
        PlayerMock observer = new PlayerMock(server, "OtherRegion") {
            @Override public void hidePlayer(org.bukkit.plugin.Plugin source, org.bukkit.entity.Player subject) {
                if (scheduler.owner != this) violations.add("hide");
                super.hidePlayer(source, subject);
            }
            @Override public void showPlayer(org.bukkit.plugin.Plugin source, org.bukkit.entity.Player subject) {
                if (scheduler.owner != this) violations.add("show");
                super.showPlayer(source, subject);
            }
        };
        server.addPlayer(observer); violations.clear();
        state().add(player.getUniqueId());
        scheduler.run("global", () -> plugin.updateVanishVisibilityFolia(player));
        assertFalse(observer.canSee(player), "ownership dispatch must still hide the subject");
        assertTrue(violations.isEmpty(), "visibility operations must run on each observer's owner: " + violations);
    }

    @Test
    void followingReadsTargetPositionOnItsOwner() throws Exception {
        List<String> violations = new ArrayList<>();
        var checking = new java.util.concurrent.atomic.AtomicBoolean();
        PlayerMock target = new PlayerMock(server, "FollowTarget") {
            @Override public Location getLocation() {
                if (checking.get() && scheduler.owner != this) violations.add("location");
                return super.getLocation();
            }
        };
        PlayerMock follower = new PlayerMock(server, "FollowingStaff") {
            @Override public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(Location location) {
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            }
        };
        server.addPlayer(target); server.addPlayer(follower);
        follower.setOp(true); state().add(follower.getUniqueId());
        var command = new net.thecommandcraft.vanishpp.commands.VanishFollowCommand(plugin);
        checking.set(true);
        scheduler.runEntity(follower, () -> command.onCommand(follower, plugin.getCommand("vfollow"), "vfollow",
                new String[]{target.getName()}), null);
        assertEquals(target.getUniqueId(), plugin.spectateFollowTargets.get(follower.getUniqueId()), "follow must start");
        assertTrue(violations.isEmpty(), "follow must capture target location on its owner before teleporting the follower");
    }

    @Test
    void foliaDoesNotUseTheUnsupportedScoreboardApi() throws Exception {
        field(plugin, "vanishScheduler", new FoliaSchedulerBridge(plugin) {
            public boolean owns(Entity entity) { return true; }
            @Override public void runEntityNow(Entity entity, Runnable task) { task.run(); }
            @Override public void runEntity(Entity entity, Runnable task, Runnable retired) { task.run(); }
            @Override public void runGlobal(Runnable task) { task.run(); }
            @Override public void runTimerGlobal(Runnable task, long delay, long period) {}
            @Override public void runAsync(Runnable task) { scheduler.async.add(task); }
        });
        countScoreboardAccesses = true;
        try { plugin.getVanishScoreboard().show(player); }
        catch (RuntimeException ignored) { /* The assertion concerns entering the unsupported API, not MockBukkit's renderer. */ }
        assertEquals(0, scoreboardAccesses, "Folia must avoid unsupported scoreboard API calls");
        invoke(plugin, "setupTeams");
        plugin.viewerTeams.update(player, true);
        plugin.viewerTeams.refresh();
        assertEquals(0, scoreboardAccesses, "Folia startup and private overlays must also avoid scoreboard API");
    }

    @Test
    void gameplayRuleReadsUsePreparedSnapshotsWithoutStorageQueries() throws Exception {
        SqlStorage storage = mock(SqlStorage.class);
        field(plugin, "storageProvider", storage);
        var stored = new AtomicReference<Map<String, Object>>(Map.of(RuleManager.CAN_CHAT, true));
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        when(storage.getRules(player.getUniqueId())).thenAnswer(call -> unavailable.get() ? Map.of() : stored.get());
        when(storage.getRecoveryRules(player.getUniqueId())).thenAnswer(call -> {
            if (unavailable.get()) throw new IllegalStateException("settings unavailable");
            return stored.get();
        });
        doAnswer(call -> { stored.set(Map.of(RuleManager.CAN_CHAT, call.getArgument(2))); return null; })
                .when(storage).setRule(eq(player.getUniqueId()), eq(RuleManager.CAN_CHAT), any());
        var listener = new PlayerListener(plugin);
        scheduler.run("async", () -> listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(
                player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId())));
        scheduler.drainAsync();
        clearInvocations(storage);
        for (int i = 0; i < 40; i++) assertTrue(plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT));
        long reads = mockingDetails(storage).getInvocations().stream().filter(call -> call.getMethod().getName().equals("getRules")).count();
        unavailable.set(true);
        scheduler.run("async", () -> listener.onAsyncPreLogin(new AsyncPlayerPreLoginEvent(
                player.getName(), InetAddress.getLoopbackAddress(), player.getUniqueId())));
        boolean retained = plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT);
        unavailable.set(false);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_CHAT, false); scheduler.drainAsync();
        boolean written = plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT);
        assertAll(
                () -> assertEquals(0, reads, "gameplay rule reads must not issue synchronous storage queries"),
                () -> assertTrue(retained, "failed refreshes must preserve the last complete rule snapshot"),
                () -> assertFalse(written, "a completed local write must update the cached rule before releasing its pending decision"));
    }

    @Test
    void repeatedRefreshTicksDoNotQueueOverlappingSqlScans() throws Exception {
        SqlStorage storage = mock(SqlStorage.class);
        when(storage.getRecoveryRules(player.getUniqueId())).thenReturn(Map.of());
        field(plugin, "storageProvider", storage);
        invoke(plugin, "startSyncTask");
        scheduler.fireTimers(); scheduler.fireTimers(); scheduler.drainAsync();
        long reads = mockingDetails(storage).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("getRecoveryRules")).count();
        assertEquals(1, reads, "slow SQL refreshes must coalesce subsequent timer ticks instead of accumulating scans");
    }

    @Test
    void mobSafetyDoesNotScheduleAPollForEveryMobEveryTick() throws Exception {
        field(plugin, "hasPaperApi", false);
        new MobAiManager(plugin).register();
        assertTrue(scheduler.periods.stream().noneMatch(period -> period == 1L),
                "mob safety must not install an every-tick player-by-mob sweep");
        state().add(player.getUniqueId());
        var target = new EntityTargetEvent(mock(Mob.class), player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        new PlayerListener(plugin).onMobTarget(target);
        assertTrue(target.isCancelled(), "event-based target prevention must remain active");
    }

    @Test
    void disabledHistoryDoesNotRecordTransitions() {
        plugin.getConfigManager().getConfig().set("vanish-history.enabled", false);
        plugin.vanishPlayer(player, player);
        scheduler.drainAsync();
        assertTrue(plugin.getStorageProvider().getAllHistory(1, 100).isEmpty(), "disabled history must not append a transition");
        plugin.getConfigManager().getConfig().set("vanish-history.enabled", true);
        plugin.unvanishPlayer(player, player); scheduler.drainAsync();
        assertEquals(1, plugin.getStorageProvider().getAllHistory(1, 100).size(), "enabled history must still record transitions");
    }

    @Test
    void historyMaintenancePrunesExpiredEntriesAndKeepsFreshEntries() throws Exception {
        plugin.getConfigManager().getConfig().set("vanish-history.retention-days", 1);
        var storage = plugin.getStorageProvider();
        storage.addHistoryEntry(new VanishHistoryEntry(player.getUniqueId(), player.getName(), VanishHistoryEntry.Action.VANISH,
                Instant.now().minusSeconds(3 * 86400L), "server", "expired", 0));
        storage.addHistoryEntry(VanishHistoryEntry.vanish(player.getUniqueId(), player.getName(), "server", "fresh"));
        invoke(plugin, "startSyncTask");
        scheduler.fireTimers(); scheduler.drainAsync();
        assertEquals(List.of("fresh"), storage.getAllHistory(1, 100).stream().map(VanishHistoryEntry::getReason).toList(),
                "scheduled history retention must remove expired records and preserve fresh records");
    }

    @Test
    void repeatedWandRuleViewsReuseOneRegisteredListener() {
        giveWand(); player.setSneaking(true);
        var listener = new VanishWandListener(plugin);
        listener.onInteract(wandClick());
        long first = ruleListeners();
        for (int i = 0; i < 8; i++) { player.closeInventory(); listener.onInteract(wandClick()); }
        assertEquals(first, ruleListeners(), "reopening the rules GUI must not register another listener");
        assertEquals(1, first, "one rules listener must handle the first opening");
    }

    @Test
    void cancellableEventsProtectBothTransitionsAndPostEventsAreExactlyOnce() {
        TransitionListener listener = new TransitionListener();
        server.getPluginManager().registerEvents(listener, plugin);
        listener.cancelVanish = true;
        plugin.vanishPlayer(player, player, "cancelled");
        boolean cancelledVanishChangedState = plugin.isVanished(player);
        String cancelledReason = plugin.getVanishReason(player.getUniqueId());
        state().clear(); plugin.setVanishReason(player.getUniqueId(), null);
        listener.cancelVanish = false;
        plugin.vanishPlayer(player, player);
        listener.cancelUnvanish = true;
        plugin.unvanishPlayer(player, player);
        boolean cancelledUnvanishPreservedState = plugin.isVanished(player);
        listener.cancelUnvanish = false;
        plugin.unvanishPlayer(player, player);
        assertAll(
                () -> assertEquals(2, listener.vanishCalls, "VanishEvent must fire before both attempted transitions"),
                () -> assertEquals(2, listener.unvanishCalls, "UnvanishEvent must fire before both attempted transitions"),
                () -> assertFalse(cancelledVanishChangedState, "cancelled vanish must leave state visible"),
                () -> assertNull(cancelledReason, "cancelled vanish must leave reason unchanged"),
                () -> assertTrue(cancelledUnvanishPreservedState, "cancelled unvanish must preserve vanished state"),
                () -> assertEquals(List.of(true, false), listener.posts, "post events must fire exactly once per accepted transition"));
    }

    @Test
    void authoritativeChangesAreNotVetoedAndDuplicateRestorationHasNoPostEvent() {
        TransitionListener listener = new TransitionListener();
        listener.cancelVanish = true; listener.cancelUnvanish = true;
        server.getPluginManager().registerEvents(listener, plugin);
        plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        assertTrue(plugin.isVanished(player), "authoritative vanish must apply even when a local listener would veto it");
        plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        plugin.handleNetworkVanishSync(player.getUniqueId(), false);
        assertAll(
                () -> assertFalse(plugin.isVanished(player), "authoritative unvanish must apply"),
                () -> assertEquals(0, listener.vanishCalls + listener.unvanishCalls, "authoritative restoration is not a local cancellable request"),
                () -> assertEquals(List.of(true, false), listener.posts, "only actual authoritative changes must emit post events"));
    }

    @Test
    void mobTargetingMasterSwitchActuallyAllowsTargetingWhenDisabled() {
        state().add(player.getUniqueId());
        plugin.getConfigManager().disableMobTarget = false;
        var event = new EntityTargetEvent(mock(Mob.class), player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        new PlayerListener(plugin).onMobTarget(event);
        assertFalse(event.isCancelled(), "disable-mob-targeting false must disable targeting prevention");
        plugin.getConfigManager().disableMobTarget = true;
        new PlayerListener(plugin).onMobTarget(event);
        assertTrue(event.isCancelled(), "enabled mob protection must still reject targets");
    }

    @Test
    void chatMasterSwitchDisablesAccidentalChatBlocking() {
        state().add(player.getUniqueId());
        plugin.getConfigManager().preventAccidentalChat = false;
        Set<net.kyori.adventure.audience.Audience> viewers = new HashSet<>(List.of(player));
        var event = new io.papermc.paper.event.player.AsyncChatEvent(false, player, viewers,
                io.papermc.paper.chat.ChatRenderer.defaultRenderer(), Component.text("probe"), Component.text("probe"), null);
        new PlayerListener(plugin).onChat(event);
        assertFalse(event.isCancelled(), "prevent-accidental-chat false must disable chat confirmation blocking");
        plugin.getConfigManager().preventAccidentalChat = true;
        new PlayerListener(plugin).onChat(event);
        assertTrue(event.isCancelled(), "enabled accidental-chat protection must still block the first attempt");
    }

    @Test
    void entityInteractionMasterSwitchAppliesEvenWhenPersonalRuleAllowsInteraction() {
        state().add(player.getUniqueId());
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, true);
        plugin.getConfigManager().preventEntityInteract = true;
        var event = new PlayerInteractEntityEvent(player, mock(org.bukkit.entity.Cow.class), EquipmentSlot.HAND);
        new PlayerListener(plugin).onEntityInteract(event);
        assertTrue(event.isCancelled(), "prevent-entity-interact must apply even when can_interact is true");
    }

    @Test
    void advancementAnnouncementSwitchIsHonored() {
        state().add(player.getUniqueId());
        plugin.getConfigManager().hideAdvancements = false;
        var event = mock(PlayerAdvancementDoneEvent.class);
        when(event.getPlayer()).thenReturn(player);
        var suppressed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { suppressed.set(true); return null; }).when(event).message(null);
        new PlayerListener(plugin).onAdvancement(event);
        assertFalse(suppressed.get(), "hide-announcements.advancements false must preserve the announcement");
        plugin.getConfigManager().hideAdvancements = true;
        new PlayerListener(plugin).onAdvancement(event);
        assertTrue(suppressed.get(), "enabled advancement hiding must still suppress the announcement");
    }

    @Test
    void serverListSamplesAreFilteredIndependentlyOfCountAdjustment() {
        state().add(player.getUniqueId());
        plugin.getConfigManager().adjustServerListCount = false;
        plugin.getConfigManager().hideFromServerList = true;
        var event = mock(com.destroystokyo.paper.event.server.PaperServerListPingEvent.class);
        var visible = new com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo("Visible", UUID.randomUUID());
        var entries = new ArrayList<>(List.of(visible,
                new com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo(player.getName(), player.getUniqueId())));
        when(event.getListedPlayers()).thenReturn(entries);
        new PlayerListener(plugin).onServerListPing(event);
        assertEquals(List.of(visible), entries, "hide-from-server-list must filter samples even when count adjustment is disabled");
        verify(event, never()).setNumPlayers(anyInt());
    }

    @Test
    void unsupportedControlsAreNotAdvertisedAsWorkingSettings() {
        var config = plugin.getConfigManager().getConfig();
        var unsupported = List.of("anti-combat-vanish", "vanish-rate-limit", "timed-vanish", "afk-auto-vanish", "world-rules", "staff-sounds");
        assertEquals(List.of(), unsupported.stream().filter(config::contains).toList(),
                "unimplemented controls must be removed from the shipped configuration");
    }

    @Test
    void webhookUsesTheDocumentedAuthorizationHeader() throws Exception {
        var captured = new AtomicReference<String>();
        HttpServer endpoint = endpoint(captured, null);
        try {
            var config = plugin.getConfigManager().getConfig();
            config.set("webhook.enabled", true);
            config.set("webhook.urls", List.of("http://127.0.0.1:" + endpoint.getAddress().getPort() + "/"));
            config.set("webhook.auth-header", "test-authorization");
            invoke(plugin.getConfigManager(), "loadValues");
            new WebhookManager(plugin).send(player, "VANISH", "test"); scheduler.drainAsync();
            assertEquals("test-authorization", captured.get(), "webhook.auth-header must reach the HTTP Authorization header");
        } finally { endpoint.stop(0); }
    }

    @Test
    void webhookEscapesEverySubstitutedJsonFieldWithoutRecursiveReplacement() throws Exception {
        var payload = new AtomicReference<String>();
        HttpServer endpoint = endpoint(null, payload);
        String reason = "quote \" slash \\ newline\n tab\t {server}";
        String serverName = "server\"\\\n";
        try {
            plugin.getConfigManager().webhookEnabled = true;
            plugin.getConfigManager().webhookUrls = List.of("http://127.0.0.1:" + endpoint.getAddress().getPort() + "/");
            plugin.getConfigManager().webhookPayloadTemplate = "{\"player\":\"{player}\",\"action\":\"{action}\",\"reason\":\"{reason}\",\"server\":\"{server}\"}";
            plugin.getConfigManager().getConfig().set("proxy.server-name", serverName);
            new WebhookManager(plugin).send(player, "VANISH\"", reason); scheduler.drainAsync();
            var json = assertDoesNotThrow(() -> JsonParser.parseString(payload.get()).getAsJsonObject(),
                    "valid text in any webhook field must produce valid JSON");
            assertEquals(reason, json.get("reason").getAsString());
            assertEquals(serverName, json.get("server").getAsString());
            assertEquals("VANISH\"", json.get("action").getAsString());
        } finally { endpoint.stop(0); }
    }

    @Test
    void configGuiKeepsItsSessionAcrossReplacementCloses() {
        player.setOp(true);
        ConfigGUI gui = new ConfigGUI(plugin);
        gui.open(player);
        gui.onClick(click(1));
        InventoryClickEvent second = click(18);
        boolean before = plugin.getConfigManager().getConfig().getBoolean("vanish-gamemodes.enabled");
        gui.onClick(second);
        assertAll(
                () -> assertTrue(second.isCancelled(), "replacement inventory clicks must remain protected"),
                () -> assertEquals(!before, plugin.getConfigManager().getConfig().getBoolean("vanish-gamemodes.enabled"),
                        "a second click after redraw must still change the selected setting"));
    }

    @Test
    void configGuiRecognizesTheBundledChineseTitle() {
        player.setOp(true);
        plugin.getConfigManager().getConfig().set("language", "zh-cn");
        plugin.getLanguageManager().load();
        ConfigGUI gui = new ConfigGUI(plugin); gui.open(player);
        InventoryClickEvent click = click(18); gui.onClick(click);
        assertTrue(click.isCancelled(), "GUI identity must not depend on English words in its translated title");
    }

    @Test
    void configRendererShowsCurrentBooleanAndNumericValues() {
        plugin.getConfigManager().getConfig().set("vanish-appearance.action-bar.enabled", false);
        plugin.getConfigManager().getConfig().set("permissions.default-see-level", 7);
        var renderer = new ConfigRenderer(plugin);
        Inventory general = (Inventory) renderer.buildCategoryInventory("GENERAL", 0)[0];
        Inventory permissions = (Inventory) renderer.buildCategoryInventory("PERMISSIONS", 0)[0];
        String lore = permissions.getItem(19).getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).reduce("", (a, b) -> a + b);
        assertAll(
                () -> assertEquals(Material.RED_CONCRETE, general.getItem(18).getType(), "boolean color must reflect the current setting"),
                () -> assertTrue(lore.contains("7"), "numeric lore must show the current value rather than its default"));
    }

    @Test
    void configGuiDisplaysAndEditsTheEffectiveProxyValue() {
        player.setOp(true);
        plugin.getConfigManager().getConfig().set("permissions.default-see-level", 5);
        var snapshot = new net.thecommandcraft.vanishpp.common.config.ProxyConfigSnapshot();
        snapshot.defaultSeeLevel = 9;
        plugin.getProxyConfigCache().update(snapshot.toJson(), "backend");
        ConfigGUI gui = new ConfigGUI(plugin); gui.open(player); gui.onClick(click(4));
        String current = PlainTextComponentSerializer.plainText().serialize(player.getOpenInventory().getTopInventory()
                .getItem(19).getItemMeta().lore().get(2));
        gui.onClick(new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                19, ClickType.RIGHT, InventoryAction.PICKUP_HALF));
        assertAll(
                () -> assertTrue(current.endsWith("9"), "GUI must display the effective proxy value, not the local fallback"),
                () -> assertEquals(10, plugin.getConfigManager().getConfig().getInt("permissions.default-see-level"),
                        "GUI edits must start from the value actually displayed"));
    }

    @Test
    void vanishWandWorksWhileGameplayInteractionIsRestricted() {
        giveWand();
        plugin.vanishPlayer(player, player);
        plugin.getRuleManager().setRule(player, RuleManager.CAN_INTERACT, false);
        server.getPluginManager().callEvent(wandClick());
        assertFalse(plugin.isVanished(player), "interaction restrictions must not disable the authorized vanish wand");
    }

    @Test
    void historyResolvesNamesFromPersistedRecordsAfterDisconnect() {
        UUID offline = UUID.randomUUID();
        plugin.getStorageProvider().addHistoryEntry(VanishHistoryEntry.vanish(offline, "OfflineStaff", "server", "offline-evidence"));
        plugin.playerNameCache.clear();
        player.setOp(true); clearMessages();
        new VanishHistoryCommand(plugin).onCommand(player, plugin.getCommand("vhistory"), "vhistory", new String[]{"OfflineStaff"});
        scheduler.drainAsync();
        assertTrue(messages().contains("offline-evidence"), "history must resolve an offline identity without the transient player-name cache");
    }

    @Test
    void queuedDuplicateNetworkUpdatesApplyEffectsAndPostExactlyOnce() {
        TransitionListener listener = new TransitionListener();
        server.getPluginManager().registerEvents(listener, plugin);
        scheduler.queueOffOwner = true;
        scheduler.run("global", () -> {
            plugin.handleNetworkVanishSync(player.getUniqueId(), true);
            plugin.handleNetworkVanishSync(player.getUniqueId(), true);
        });
        assertFalse(scheduler.entities.isEmpty(), "off-owner effects must actually be queued");
        scheduler.drainEntities();
        boolean invisible = player.isInvisible();
        scheduler.run("global", () -> {
            plugin.handleNetworkVanishSync(player.getUniqueId(), false);
            plugin.handleNetworkVanishSync(player.getUniqueId(), false);
        });
        scheduler.drainEntities();
        assertAll(
                () -> assertTrue(invisible, "duplicate queued vanish must apply invisibility"),
                () -> assertFalse(player.isInvisible(), "duplicate queued unvanish must remove invisibility"),
                () -> assertFalse(player.hasMetadata("vanished"), "unvanish must clear applied metadata"),
                () -> assertEquals(List.of(true, false), listener.posts, "queued duplicates must emit exactly one post per change"));
    }

    @Test
    void cancelledCommandsDoNotEnterSpectatorOrReportAcceptedTargets() {
        player.setOp(true);
        PlayerMock target = server.addPlayer("CommandTarget");
        TransitionListener listener = new TransitionListener(); listener.cancelVanish = true;
        server.getPluginManager().registerEvents(listener, plugin);
        clearMessages();
        player.performCommand("vspec " + target.getName());
        boolean spectator = player.getGameMode() == GameMode.SPECTATOR;
        boolean follow = plugin.spectateFollowTargets.containsKey(player.getUniqueId());
        String specMessages = messages();
        plugin.getConfigManager().vanishedOtherMessage = "accepted-target";
        player.performCommand("vanish " + target.getName());
        String targetMessages = messages();
        player.performCommand("vanish all");
        String bulkMessages = messages();
        String zero = PlainTextComponentSerializer.plainText().serialize(plugin.getMessageManager().parse(
                plugin.getLanguageManager().getMessage("bulk.vanished-all").replace("%count%", "0"), player));
        assertAll(
                () -> assertFalse(spectator || follow || plugin.spectateOrigins.containsKey(player.getUniqueId()), "vetoed spectate must not change mode, origin or follow state"),
                () -> assertFalse(specMessages.contains(target.getName()), "vetoed spectate must not report success"),
                () -> assertFalse(targetMessages.contains("accepted-target"), "vetoed target command must not report success"),
                () -> assertTrue(bulkMessages.contains(zero), "bulk count must include only accepted transitions"));
    }

    @Test
    void followContinuesAfterUnvanishOnlyForSpectatorBypass() {
        var teleports = new java.util.concurrent.atomic.AtomicInteger();
        PlayerMock follower = new PlayerMock(server, "BypassFollower") {
            @Override public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(Location location) {
                teleports.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(true);
            }
        };
        server.addPlayer(follower); follower.setOp(true);
        var bypass = follower.addAttachment(plugin, "vanishpp.spectator.bypass", true);
        var follow = new net.thecommandcraft.vanishpp.commands.VanishFollowCommand(plugin);
        scheduler.runEntityNow(follower, () -> {
            plugin.vanishPlayer(follower, follower);
            follow.onCommand(follower, plugin.getCommand("vfollow"), "vfollow", new String[]{player.getName()});
            plugin.unvanishPlayer(follower, follower);
        });
        int before = teleports.get(); scheduler.fireTimers();
        boolean continued = teleports.get() > before;
        bypass.setPermission("vanishpp.spectator.bypass", false);
        scheduler.runEntityNow(follower, () -> {
            plugin.vanishPlayer(follower, follower);
            plugin.unvanishPlayer(follower, follower);
        });
        int stopped = teleports.get(); scheduler.fireTimers();
        assertAll(
                () -> assertTrue(continued, "spectator bypass must preserve following after unvanish"),
                () -> assertEquals(stopped, teleports.get(), "ordinary unvanish must stop following"),
                () -> assertFalse(plugin.spectateFollowTargets.containsKey(follower.getUniqueId())));
    }

    @Test
    void mobCleanupIsBoundedAndEventuallyDrainsRepeatedRequests() {
        MobAiManager manager = new MobAiManager(plugin); manager.register();
        var cleared = new java.util.concurrent.atomic.AtomicInteger();
        List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Mob mob = mock(Mob.class, RETURNS_DEEP_STUBS); mobs.add(mob);
            when(mob.getUniqueId()).thenReturn(UUID.randomUUID()); when(mob.isValid()).thenReturn(true);
            var target = new AtomicReference<org.bukkit.entity.LivingEntity>(player);
            when(mob.getTarget()).thenAnswer(call -> target.get());
            doAnswer(call -> { if (target.getAndSet(null) != null) cleared.incrementAndGet(); return null; }).when(mob).setTarget(null);
            manager.onTarget(new EntityTargetEvent(mob, player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER));
        }
        state().add(player.getUniqueId());
        for (int i = 0; i < 20; i++) manager.clearTargets(player);
        int immediate = cleared.get();
        for (int i = 0; i < 30; i++) {
            int before = cleared.get(); scheduler.fireTimers();
            assertTrue(cleared.get() - before <= 64, "mob cleanup must process at most its 64-entity tick budget");
        }
        assertAll(
                () -> assertTrue(immediate <= 64, "transition requests must not synchronously scan all 300 mobs"),
                () -> assertEquals(300, cleared.get(), "bounded cleanup must eventually clear every tracked target"));
    }

    @Test
    void removedMobsLeaveNoTargetIndexWithoutDeathOrChunkUnload() throws Exception {
        MobAiManager manager = new MobAiManager(plugin);
        server.getPluginManager().registerEvents(manager, plugin);
        Mob mob = mock(Mob.class); when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
        manager.onTarget(new EntityTargetEvent(mob, player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER));
        var index = MobAiManager.class.getDeclaredField("mobTargets"); index.setAccessible(true);
        assertEquals(1, ((Map<?, ?>) index.get(manager)).size(), "target control must enter the index");
        server.getPluginManager().callEvent(new com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent(mob, player.getWorld()));
        server.getPluginManager().callEvent(new com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent(mob, player.getWorld()));
        assertTrue(((Map<?, ?>) index.get(manager)).isEmpty(), "removal without death must idempotently retire the target index");
    }

    @Test
    void olderSqlLoadCannotOverwriteANewerPublishedRestriction() throws Exception {
        SqlStorage storage = mock(SqlStorage.class); field(plugin, "storageProvider", storage);
        var rules = new AtomicReference<Map<String, Object>>(Map.of(RuleManager.CAN_CHAT, true));
        when(storage.getRecoveryRules(player.getUniqueId())).thenAnswer(call -> rules.get());
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(storage.getRecoveryVanishLevel(player.getUniqueId())).thenAnswer(call -> {
            if (first.getAndSet(false)) {
                rules.set(Map.of(RuleManager.CAN_CHAT, false));
                plugin.getPlayerData().load(player.getUniqueId());
            }
            return 1;
        });
        scheduler.run("async", () -> plugin.getPlayerData().load(player.getUniqueId()));
        assertFalse(plugin.getRuleManager().getRule(player, RuleManager.CAN_CHAT), "an older SQL completion must not overwrite a newer restriction");
    }

    @Test
    void proxyTransientSendFailureBacksOffAndRecoversInOrder() throws Exception {
        proxyFailure(false);
    }

    @Test
    void proxyPermanentSendFailureStopsRetryingAndReportsCause() throws Exception {
        proxyFailure(true);
    }

    private void proxyFailure(boolean permanent) throws Exception {
        player.disconnect();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var delivered = new ArrayList<String>();
        var logs = new ArrayList<String>();
        PlayerMock carrier = new PlayerMock(server, "FailureCarrier") {
            @Override public void sendPluginMessage(org.bukkit.plugin.Plugin source, String channel, byte[] message) {
                int attempt = attempts.incrementAndGet();
                if (permanent) throw new IllegalArgumentException("invalid-packet-probe");
                if (attempt == 1) throw new IllegalStateException("temporary-carrier-probe");
                delivered.add(net.thecommandcraft.vanishpp.common.protocol.VppPacket.decode(message).jsonPayload());
            }
        };
        server.addPlayer(carrier);
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            public void flush() {}
            public void close() {}
        };
        plugin.getLogger().addHandler(handler);
        try {
            plugin.getConfigManager().getConfig().set("proxy.enabled", true);
            var bridge = new net.thecommandcraft.vanishpp.proxy.ProxyBridge(plugin); bridge.init(); scheduler.later.clear();
            bridge.sendPacket(net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_QUERY, "first"));
            int afterFailure = attempts.get();
            bridge.sendPacket(net.thecommandcraft.vanishpp.common.protocol.VppPacket.encode(net.thecommandcraft.vanishpp.common.protocol.VppMessage.STATE_QUERY, "second"));
            int beforeRetry = attempts.get();
            for (int i = 0; i < 12; i++) scheduler.delayedPass();
            assertAll(
                    () -> assertEquals(afterFailure, beforeRetry, "new packets must not bypass a failed-send backoff or suspension"),
                    () -> assertTrue(logs.stream().anyMatch(message -> message.contains(permanent ? "invalid-packet-probe" : "temporary-carrier-probe")), "proxy send failures must report their cause"),
                    () -> { if (permanent) assertEquals(1, attempts.get(), "permanent failure must not retry forever");
                        else assertEquals(List.of("first", "second"), delivered, "transient recovery must preserve packet order"); });
        } finally { plugin.getLogger().removeHandler(handler); }
    }

    private InventoryClickEvent click(int slot) {
        return new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private void giveWand() {
        player.setOp(true);
        player.performCommand("vwand");
        player.getInventory().setHeldItemSlot(0);
    }

    private PlayerInteractEvent wandClick() {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                player.getWorld().getBlockAt(0, 64, 0), org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
    }

    private long ruleListeners() {
        return HandlerList.getRegisteredListeners(plugin).stream().map(org.bukkit.plugin.RegisteredListener::getListener)
                .filter(listener -> listener instanceof RulesGUI).distinct().count();
    }

    private void clearMessages() { while (player.nextMessage() != null) {} }
    private String messages() {
        StringBuilder result = new StringBuilder(); String next;
        while ((next = player.nextMessage()) != null) result.append(next);
        return result.toString();
    }

    private HttpServer endpoint(AtomicReference<String> authorization, AtomicReference<String> payload) throws Exception {
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", exchange -> {
            if (authorization != null) authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (payload != null) payload.set(body);
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        endpoint.start(); return endpoint;
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return;
            } catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> state() {
        try {
            Field field = Vanishpp.class.getDeclaredField("vanishedPlayers"); field.setAccessible(true);
            return (Set<UUID>) field.get(plugin);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void invoke(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var method = type.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); return;
            } catch (NoSuchMethodException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }

    private static class TransitionListener implements Listener {
        boolean cancelVanish, cancelUnvanish;
        int vanishCalls, unvanishCalls;
        final List<Boolean> posts = new ArrayList<>();
        @EventHandler public void vanish(VanishEvent event) { vanishCalls++; event.setCancelled(cancelVanish); }
        @EventHandler public void unvanish(UnvanishEvent event) { unvanishCalls++; event.setCancelled(cancelUnvanish); }
        @EventHandler public void changed(VanishStateChangeEvent event) { posts.add(event.isVanished()); }
    }

    private static class ControlledScheduler implements VanishScheduler {
        String context = "entity";
        Entity owner;
        boolean queueOffOwner;
        final List<Runnable> entities = new ArrayList<>();
        final List<Runnable> async = new ArrayList<>(), later = new ArrayList<>(), timers = new ArrayList<>();
        final List<Long> periods = new ArrayList<>();
        void run(String owner, Runnable task) { String old = context; context = owner; try { task.run(); } finally { context = old; } }
        public void runGlobal(Runnable task) { run("global", task); }
        public void runLaterGlobal(Runnable task, long ticks) { later.add(task); }
        public void runTimerGlobal(Runnable task, long delay, long period) { timers.add(task); periods.add(period); }
        public void runEntity(Entity entity, Runnable task, Runnable retired) {
            Entity previous = owner; owner = entity;
            try { run("entity", task); } finally { owner = previous; }
        }
        public boolean owns(Entity entity) { return context.equals("entity") && owner == entity; }
        public void runEntityNow(Entity entity, Runnable task) {
            if (queueOffOwner && !owns(entity)) entities.add(() -> runEntity(entity, task, null));
            else runEntity(entity, task, null);
        }
        void drainEntities() { while (!entities.isEmpty()) entities.removeFirst().run(); }
        public void runAsync(Runnable task) { async.add(task); }
        public void cancelAllTasks() { later.clear(); timers.clear(); async.clear(); }
        void drainAsync() { while (!async.isEmpty()) run("async", async.removeFirst()); }
        void delayedPass() { List<Runnable> due = List.copyOf(later); later.clear(); due.forEach(task -> run("global", task)); }
        void fireTimers() { List.copyOf(timers).forEach(task -> run("global", task)); }
    }
}
