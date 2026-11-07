package net.thecommandcraft.vanishpp.velocity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigPersistenceTest {
    @TempDir Path directory;

    @Test
    void acceptedNetworkPatchSurvivesReloadAndRestart() {
        var logger = LoggerFactory.getLogger(getClass());
        VelocityConfigManager config = new VelocityConfigManager(directory, logger);
        config.load();
        boolean changed = !config.getSnapshot().godMode;
        config.applyRuntimePatch(Map.of("invisibility-features.god-mode", Boolean.toString(changed)));
        assertEquals(changed, config.getSnapshot().godMode, "patch control must change runtime state");
        config.reload();
        VelocityConfigManager restarted = new VelocityConfigManager(directory, logger);
        restarted.load();
        assertAll(
                () -> assertEquals(changed, config.getSnapshot().godMode, "accepted network patch must survive proxy reload"),
                () -> assertEquals(changed, restarted.getSnapshot().godMode, "accepted network patch must survive proxy restart"));
    }
}
