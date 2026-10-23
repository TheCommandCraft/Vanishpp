package net.thecommandcraft.vanishpp.common.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateSourcesTest {

    @Test
    void newerVersionsAreDetected() {
        assertTrue(UpdateSources.compareVersions("1.1.9", "1.1.9.1") < 0);
        assertTrue(UpdateSources.compareVersions("1.1.9", "1.1.10") < 0);
        assertTrue(UpdateSources.compareVersions("1.1.8", "1.1.9") < 0);
        assertTrue(UpdateSources.compareVersions("1.1.9.1", "1.2") < 0);
        assertTrue(UpdateSources.compareVersions("1.1.10", "1.1.9") > 0);
    }

    @Test
    void sameVersionInDifferentSpellingsIsEqual() {
        assertEquals(0, UpdateSources.compareVersions("1.1.9", "1.1.9"));
        assertEquals(0, UpdateSources.compareVersions("1.1.9", "v1.1.9"));
        assertEquals(0, UpdateSources.compareVersions("1.1.9", "1.1.9.0"));
        assertEquals(0, UpdateSources.compareVersions("1.1.9-beta", "1.1.9"));
    }

    @Test
    void garbageNeverThrows() {
        assertDoesNotThrow(() -> UpdateSources.compareVersions("unknown", "1.1.9"));
        assertTrue(UpdateSources.compareVersions("unknown", "1.1.9") < 0);
    }

    @Test
    void urlsUseTheProjectIdNotTheSlug() {
        // the slug was renamed once and silently broke every update check up to 1.1.8
        assertTrue(UpdateSources.modrinthVersionsUrl("paper")
                .startsWith("https://api.modrinth.com/v2/project/" + UpdateSources.MODRINTH_PROJECT_ID + "/version"));
        assertEquals(UpdateSources.MODRINTH_PAGE + "/version/abc", UpdateSources.downloadPage("abc"));
        assertEquals(UpdateSources.MODRINTH_PAGE, UpdateSources.downloadPage(null));
    }
}
