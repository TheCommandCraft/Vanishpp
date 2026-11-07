package net.thecommandcraft.vanishpp.commands;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import net.thecommandcraft.vanishpp.Vanishpp;
import net.thecommandcraft.vanishpp.proxy.ProxyBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSecurityTest {

    private ServerMock server;
    private Vanishpp plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Vanishpp.class);
        plugin.getConfigManager().disableBlockTriggering = false;
        plugin.getConfigManager().preventSleeping = false;
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void applyProxy_requiresConfigPermissionWithoutRestrictingAcknowledgements() throws Exception {
        List<Map<String, String>> sent = new ArrayList<>();
        ProxyBridge bridge = new ProxyBridge(plugin) {
            @Override
            public boolean isProxyDetected() { return true; }

            @Override
            public void sendConfigSync(Map<String, String> entries) { sent.add(Map.copyOf(entries)); }
        };
        Field field = Vanishpp.class.getDeclaredField("proxyBridge");
        field.setAccessible(true);
        field.set(plugin, bridge);
        plugin.getConfigManager().getConfig().set("permissions.default-see-level", 7);
        player.setOp(false);
        assertFalse(player.hasPermission("vanishpp.config"));
        player.performCommand("vack apply_proxy");
        awaitStorageWrites();
        int unauthorizedPatches = sent.size();
        String acknowledgement = "proxy_config_v" + plugin.getConfigManager().getLatestVersion();
        boolean unauthorizedAck = plugin.getStorageProvider().hasAcknowledged(player.getUniqueId(), acknowledgement);

        sent.clear();
        player.addAttachment(plugin, "vanishpp.config", true);
        player.performCommand("vack apply_proxy");
        awaitStorageWrites();
        assertEquals(1, sent.size(), "a permitted player must still send a network patch");
        assertEquals("7", sent.getFirst().get("permissions.default-see-level"));
        assertTrue(plugin.getStorageProvider().hasAcknowledged(player.getUniqueId(), acknowledgement));

        PlayerMock ordinary = server.addPlayer();
        ordinary.performCommand("vack proxy_config");
        awaitStorageWrites();
        assertTrue(plugin.getStorageProvider().hasAcknowledged(ordinary.getUniqueId(), acknowledgement));
        assertAll(
                () -> assertEquals(0, unauthorizedPatches, "apply_proxy must not send a patch without vanishpp.config"),
                () -> assertFalse(unauthorizedAck, "denied apply_proxy must not dismiss the warning"));
    }

    @Test
    void configCompletion_requiresPermissionAndNeverSuggestsSecrets() {
        var command = plugin.getCommand("vanishconfig");
        var completer = (org.bukkit.command.TabCompleter) command.getExecutor();
        var config = plugin.getConfigManager().getConfig();
        List<String> paths = List.of("storage.mysql.password", "storage.postgresql.password",
                "storage.redis.password", "webhook.auth-header", "webhook.authorization", "webhook.urls");
        for (String path : paths) config.set(path, "test-only-value");
        config.set("permissions.default-see-level", 7);
        player.setOp(false);
        List<String> unprivilegedKeys = completer.onTabComplete(player, command, "vconfig", new String[]{""});
        List<String> unprivilegedValues = completer.onTabComplete(player, command, "vconfig",
                new String[]{"permissions.default-see-level", ""});
        List<String> leakedPaths = new ArrayList<>();
        for (boolean permitted : List.of(false, true)) {
            player.addAttachment(plugin, "vanishpp.config", permitted);
            for (String path : paths) {
                if (!completer.onTabComplete(player, command, "vconfig", new String[]{path, ""}).isEmpty()) {
                    leakedPaths.add(path + " (permission=" + permitted + ")");
                }
            }
        }
        assertEquals(List.of("7"), completer.onTabComplete(player, command, "vconfig",
                new String[]{"permissions.default-see-level", ""}), "safe values remain available to config editors");
        assertAll(
                () -> assertTrue(unprivilegedKeys.isEmpty(), "config keys require vanishpp.config"),
                () -> assertTrue(unprivilegedValues.isEmpty(), "config values require vanishpp.config"),
                () -> assertTrue(leakedPaths.isEmpty(), "secret values must never be suggested: " + leakedPaths));
    }
    private void awaitStorageWrites() {
        var completed = new java.util.concurrent.CountDownLatch(1);
        plugin.submitStorageWork(completed::countDown);
        assertDoesNotThrow(() -> assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS), "accepted storage writes must finish"));
    }

}
