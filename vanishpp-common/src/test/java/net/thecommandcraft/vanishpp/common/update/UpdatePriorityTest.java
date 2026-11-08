package net.thecommandcraft.vanishpp.common.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdatePriorityTest {

    @Test
    void priorityLineIsReadInEveryCommonSpelling() {
        assertEquals(UpdatePriority.SECURITY, UpdatePriority.fromChangelog("## [1.2.0]\n\n**Priority:** security\n\n### Fixed"));
        assertEquals(UpdatePriority.CRITICAL, UpdatePriority.fromChangelog("Priority: Critical"));
        assertEquals(UpdatePriority.SECURITY, UpdatePriority.fromChangelog("> **Priority: security**"));
        assertEquals(UpdatePriority.FEATURE, UpdatePriority.fromChangelog("- priority : feature"));
    }

    @Test
    void changelogsWithoutAPriorityLineAreFeatureUpdates() {
        assertEquals(UpdatePriority.FEATURE, UpdatePriority.fromChangelog(null));
        assertEquals(UpdatePriority.FEATURE, UpdatePriority.fromChangelog("## [1.1.11]\n\n### Changed\n- Mobs look at players."));
        // Only a line that starts with the label counts, not the word in running text.
        assertEquals(UpdatePriority.FEATURE, UpdatePriority.fromChangelog("- The update priority: security fixes are listed first."));
    }

    @Test
    void unknownConfigValuesStillAnnounceEveryUpdate() {
        assertEquals(UpdatePriority.FEATURE, UpdatePriority.fromConfig(null));
        assertEquals(UpdatePriority.FEATURE, UpdatePriority.fromConfig("everything"));
        assertEquals(UpdatePriority.SECURITY, UpdatePriority.fromConfig(" Security "));
        assertTrue(UpdatePriority.CRITICAL.atLeast(UpdatePriority.SECURITY));
        assertFalse(UpdatePriority.FEATURE.atLeast(UpdatePriority.SECURITY));
    }

    @Test
    void theHighestPriorityAmongAllNewerVersionsCounts() {
        JsonArray versions = new JsonArray();
        versions.add(version("1.2.1", "release", "### Added\n- Something new."));
        versions.add(version("1.2.0", "release", "**Priority:** security\n- Fixes a leak."));
        versions.add(version("1.1.11", "release", "**Priority:** critical"));
        versions.add(version("1.3.0-beta", "beta", "**Priority:** critical"));

        // 1.1.11 is older than the running version, and betas are filtered out here.
        assertEquals(UpdatePriority.SECURITY, UpdateSources.highestNewerPriority(versions, "1.1.11",
                v -> v.get("version_type").getAsString().equals("release")));
        // A server already on 1.2.0 only has the feature release left.
        assertEquals(UpdatePriority.FEATURE, UpdateSources.highestNewerPriority(versions, "1.2.0",
                v -> v.get("version_type").getAsString().equals("release")));
        // With betas included, the critical beta counts.
        assertEquals(UpdatePriority.CRITICAL, UpdateSources.highestNewerPriority(versions, "1.2.0", v -> true));
    }

    private static JsonObject version(String number, String type, String changelog) {
        JsonObject v = new JsonObject();
        v.addProperty("version_number", number);
        v.addProperty("version_type", type);
        v.addProperty("changelog", changelog);
        return v;
    }
}
