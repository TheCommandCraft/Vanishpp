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

    // RFer: every setting changed in game wiped the help comments from the proxy's config.yml.
    @Test
    void networkPatchKeepsHelpCommentsOrderAndQuoting() throws Exception {
        var logger = LoggerFactory.getLogger(getClass());
        VelocityConfigManager config = new VelocityConfigManager(directory, logger);
        config.load();
        Path file = directory.resolve("config.yml");
        String before = java.nio.file.Files.readString(file);
        java.util.List<String> comments = before.lines().filter(l -> l.trim().startsWith("#")).toList();
        assertTrue(comments.size() > 10, "control: the default config has help comments");

        config.applyRuntimePatch(Map.of("scoreboard.auto-show-on-vanish", "false", "brand-new.section.key", "7"));
        String after = java.nio.file.Files.readString(file);
        java.util.List<String> kept = after.lines().filter(l -> l.trim().startsWith("#")).toList();
        assertAll(
                () -> assertEquals(comments, kept, "every help comment must stay, in order"),
                () -> assertTrue(after.contains("  auto-show-on-vanish: false"), "the changed value must be written in place:\n" + after),
                () -> assertTrue(after.contains("brand-new:\n  section:\n    key: 7"), "a new key must be added:\n" + after),
                () -> assertEquals(before.lines().filter(l -> !l.contains("auto-show-on-vanish")).toList(),
                        after.lines().filter(l -> !l.contains("auto-show-on-vanish") && !l.startsWith("brand-new") && !l.startsWith("  section") && !l.startsWith("    key: 7")).toList(),
                        "every other line must stay exactly as written"));
        VelocityConfigManager restarted = new VelocityConfigManager(directory, logger);
        restarted.load();
        assertFalse(restarted.getSnapshot().scoreboardAutoShow, "the change must load again after a restart");
    }
}
