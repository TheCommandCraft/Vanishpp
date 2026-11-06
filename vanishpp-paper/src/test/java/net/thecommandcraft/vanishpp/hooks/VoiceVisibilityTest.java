package net.thecommandcraft.vanishpp.hooks;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.*;
import net.thecommandcraft.vanishpp.Vanishpp;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;

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

    private static class GuardedPlayer extends PlayerMock {
        private final Thread owner = Thread.currentThread();
        GuardedPlayer(ServerMock server, String name) { super(server, name); }
        @Override
        public boolean hasPermission(String permission) {
            if (owner != null && Thread.currentThread() != owner)
                throw new AssertionError("live permission query from audio thread");
            return super.hasPermission(permission);
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
