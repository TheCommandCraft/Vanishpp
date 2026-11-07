package net.thecommandcraft.vanishpp.hooks;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.*;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.thecommandcraft.vanishpp.utils.VanishScheduler;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceVisibilityTest {
    private ServerMock server;
    private Vanishpp plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    @Test
    void outgoingAudioIsBlockedOnlyForUnauthorizedListeners() {
        PlayerMock speaker = server.addPlayer("Speaker");
        PlayerMock listener = server.addPlayer("Listener");
        plugin.applyVanishEffects(speaker);
        plugin.getConfigManager().voiceChatIsolate = true;
        Map<Class<?>, Consumer<Object>> handlers = register();

        LocationalSoundPacketEvent hidden = packet(speaker, listener);
        dispatch(handlers, hidden);
        assertTrue(mockingDetails(hidden).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("cancel")),
                "voice packets from vanished speakers must be cancelled for unauthorized listeners");
        listener.setOp(true);
        plugin.updateVanishVisibility(speaker);
        LocationalSoundPacketEvent staff = packet(speaker, listener);
        dispatch(handlers, staff);
        verify(staff, never()).cancel();
        listener.setOp(false);
        plugin.removeVanishEffects(speaker);
        LocationalSoundPacketEvent visible = packet(speaker, listener);
        dispatch(handlers, visible);
        verify(visible, never()).cancel();
    }

    @Test
    void audioThreadUsesPublishedAudienceAndPermissionRevocationInvalidatesIt() throws Exception {
        GuardedPlayer speaker = new GuardedPlayer(server, "Speaker");
        GuardedPlayer listener = new GuardedPlayer(server, "Listener");
        server.addPlayer(speaker);
        server.addPlayer(listener);
        plugin.applyVanishEffects(speaker);
        plugin.getConfigManager().voiceChatIsolate = true;
        Map<Class<?>, Consumer<Object>> handlers = register();
        plugin.updateVanishVisibility(speaker);
        LocationalSoundPacketEvent hidden = packet(speaker, listener);
        audioThread(handlers, hidden);
        verify(hidden).cancel();
        plugin.getPermissionManager().addPermission(listener.getUniqueId(), "vanishpp.see");
        plugin.updateVanishVisibility(speaker);
        LocationalSoundPacketEvent allowed = packet(speaker, listener);
        audioThread(handlers, allowed);
        verify(allowed, never()).cancel();
        plugin.getPermissionManager().removePermission(listener.getUniqueId(), "vanishpp.see");
        LocationalSoundPacketEvent revoked = packet(speaker, listener);
        audioThread(handlers, revoked);
        verify(revoked).cancel();
        plugin.getPermissionManager().addPermission(listener.getUniqueId(), "vanishpp.see");
        plugin.updateVanishVisibility(speaker);
        new net.thecommandcraft.vanishpp.listeners.PlayerListener(plugin).onQuit(
                new org.bukkit.event.player.PlayerQuitEvent(listener, net.kyori.adventure.text.Component.empty()));
        plugin.updateVanishVisibility(speaker);
        LocationalSoundPacketEvent departed = packet(speaker, listener);
        audioThread(handlers, departed);
        verify(departed).cancel();
    }

    private void audioThread(Map<Class<?>, Consumer<Object>> handlers, Object event) throws Exception {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { dispatch(handlers, event); }
            catch (Throwable error) { failure.set(error); }
        });
        thread.start();
        thread.join(5000);
        assertFalse(thread.isAlive());
        assertNull(failure.get(), "audio callbacks must not query live Bukkit permissions off-thread");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void captureStartedBeforeRevocationOrQuitCannotPublishAfterIt(boolean quit) throws Exception {
        PlayerMock speaker = server.addPlayer("Speaker");
        GuardedPlayer listener = new GuardedPlayer(server, "Listener");
        server.addPlayer(listener);
        listener.setOp(true);
        plugin.applyVanishEffects(speaker);
        plugin.getConfigManager().voiceChatIsolate = true;
        plugin.getConfigManager().layeredPermsEnabled = false;
        Map<Class<?>, Consumer<Object>> handlers = register();
        List<Runnable> queued = new ArrayList<>();
        VanishScheduler scheduler = mock(VanishScheduler.class);
        doAnswer(call -> { queued.add(call.getArgument(1)); return null; })
                .when(scheduler).runEntityNow(any(), any());
        var field = Vanishpp.class.getDeclaredField("vanishScheduler");
        field.setAccessible(true);
        Object original = field.get(plugin);
        field.set(plugin, scheduler);
        try {
            plugin.voiceAudience.refresh();
            queued.forEach(Runnable::run);
            queued.clear();
            assertTrue(plugin.voiceAudience.canHear(listener.getUniqueId(), speaker.getUniqueId()));
            plugin.voiceAudience.refresh();
            assertFalse(queued.isEmpty(), "capture must be delayed at the entity scheduler boundary");
            // The old permission result has already been read when invalidation arrives.
            listener.afterSee = () -> {
                listener.setOp(false);
                if (quit) plugin.voiceAudience.retire(listener.getUniqueId());
                else plugin.voiceAudience.invalidate(listener.getUniqueId());
            };
            queued.forEach(Runnable::run);
            queued.clear();
            LocationalSoundPacketEvent stale = packet(speaker, listener);
            audioThread(handlers, stale);
            assertTrue(mockingDetails(stale).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("cancel")),
                    "a capture invalidated during permission evaluation must not republish an audible listener");
            plugin.voiceAudience.refresh();
            queued.forEach(Runnable::run);
            queued.clear();
            assertFalse(plugin.voiceAudience.canHear(listener.getUniqueId(), speaker.getUniqueId()));
            listener.setOp(true);
            plugin.voiceAudience.joined(listener.getUniqueId());
            plugin.voiceAudience.refresh();
            queued.forEach(Runnable::run);
            LocationalSoundPacketEvent fresh = packet(speaker, listener);
            audioThread(handlers, fresh);
            verify(fresh, never()).cancel();
        } finally { field.set(plugin, original); }
    }

    @Test
    void luckPermsRecalculationInvalidatesPublishedFactsAndUnloadClosesSubscription() {
        PlayerMock speaker = server.addPlayer("Speaker");
        PlayerMock listener = server.addPlayer("Listener");
        listener.setOp(true);
        plugin.applyVanishEffects(speaker);
        plugin.getConfigManager().voiceChatIsolate = true;
        plugin.voiceAudience.refresh();
        assertTrue(plugin.voiceAudience.canHear(listener.getUniqueId(), speaker.getUniqueId()));
        LuckPerms api = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
        EventSubscription<UserDataRecalculateEvent> subscription = mock(EventSubscription.class);
        var callback = new java.util.concurrent.atomic.AtomicReference<Consumer<UserDataRecalculateEvent>>();
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(api.getEventBus().subscribe(eq(plugin), eq(UserDataRecalculateEvent.class), any())).thenAnswer(call -> {
            callback.set(call.getArgument(2));
            return subscription;
        });
        doAnswer(call -> { active.set(false); return null; }).when(subscription).close();
        server.getServicesManager().register(LuckPerms.class, api, plugin, ServicePriority.Normal);
        LuckPermsHook hook = new LuckPermsHook(plugin);
        hook.load();
        assertNotNull(callback.get(), "LuckPerms recalculation must have a registered audience invalidation callback");
        UserDataRecalculateEvent event = mock(UserDataRecalculateEvent.class, RETURNS_DEEP_STUBS);
        when(event.getUser().getUniqueId()).thenReturn(listener.getUniqueId());
        callback.get().accept(event);
        assertFalse(plugin.voiceAudience.canHear(listener.getUniqueId(), speaker.getUniqueId()),
                "LuckPerms recalculation must retire the previously published permission");
        plugin.voiceAudience.refresh();
        assertTrue(plugin.voiceAudience.canHear(listener.getUniqueId(), speaker.getUniqueId()));
        hook.unload();
        hook.unload();
        verify(subscription, times(1)).close();
        if (active.get()) callback.get().accept(event);
        assertTrue(plugin.voiceAudience.canHear(listener.getUniqueId(), speaker.getUniqueId()),
                "an unloaded subscription must not keep invalidating fresh captures");
    }

    private static class GuardedPlayer extends PlayerMock {
        private final Thread owner = Thread.currentThread();
        Runnable afterSee;
        GuardedPlayer(ServerMock server, String name) { super(server, name); }
        @Override
        public boolean hasPermission(String permission) {
            if (owner != null && Thread.currentThread() != owner)
                throw new AssertionError("live permission query from audio thread");
            boolean result = super.hasPermission(permission);
            if (permission.equals("vanishpp.see") && afterSee != null) {
                Runnable action = afterSee;
                afterSee = null;
                action.run();
            }
            return result;
        }
    }

    private Map<Class<?>, Consumer<Object>> register() {
        Map<Class<?>, Consumer<Object>> handlers = new HashMap<>();
        EventRegistration registration = (EventRegistration) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{EventRegistration.class}, (proxy, method, args) -> {
                    if (method.getName().equals("registerEvent")) {
                        for (Object arg : args) if (arg instanceof Consumer<?> callback)
                            handlers.put((Class<?>) args[0], (Consumer<Object>) callback);
                    }
                    return null;
                });
        BukkitVoicechatService service = mock(BukkitVoicechatService.class);
        doAnswer(call -> {
            ((VoicechatPlugin) call.getArgument(0)).registerEvents(registration);
            return null;
        }).when(service).registerPlugin(any());
        server.getServicesManager().register(BukkitVoicechatService.class, service, plugin, ServicePriority.Normal);
        new VoiceChatHook(plugin);
        return handlers;

    }

    private LocationalSoundPacketEvent packet(PlayerMock speaker, PlayerMock listener) {
        LocationalSoundPacketEvent event = mock(LocationalSoundPacketEvent.class);
        VoicechatConnection from = mock(VoicechatConnection.class, RETURNS_DEEP_STUBS);
        VoicechatConnection to = mock(VoicechatConnection.class, RETURNS_DEEP_STUBS);
        when(from.getPlayer().getUuid()).thenReturn(speaker.getUniqueId());
        when(to.getPlayer().getUuid()).thenReturn(listener.getUniqueId());
        when(event.getSenderConnection()).thenReturn(from);
        when(event.getReceiverConnection()).thenReturn(to);
        return event;
    }

    private void dispatch(Map<Class<?>, Consumer<Object>> handlers, Object event) {
        handlers.forEach((type, handler) -> { if (type.isInstance(event)) handler.accept(event); });
    }
}
