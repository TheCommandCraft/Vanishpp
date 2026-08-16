package net.thecommandcraft.vanishpp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boundary tests for the supported Minecraft version range: 1.20.6+, 1.21.x, 26.x.x.
 */
class VanishppVersionSupportTest {

    @ParameterizedTest
    @CsvSource({
            "1.20.5, false",
            "1.20.6, true",
            "1.20.11, true",
            "1.21, true",
            "1.21.0, true",
            "1.21.11, true",
            "26.0.0, true",
            "26.1.2, true",
            "26.2, true",
            "26.2.0, true",
            "1.19.4, false",
            "1.20, false",
            "not-a-version, false",
    })
    void isVersionSupportedMatchesExpectedRange(String mcVersion, boolean expected) {
        assertEquals(expected, Vanishpp.isVersionSupported(mcVersion));
    }

    /**
     * Regression test for issue #22: {@code Bukkit.getBukkitVersion()} does not always
     * report a clean {@code major.minor.patch} string. Verified against a real Paper
     * server: Paper 26.2 (a patch-less release) reports {@code "26.2.build.112-R0.1-SNAPSHOT"},
     * so after stripping the {@code -R0.1-SNAPSHOT} suffix the version string handed to
     * {@code isVersionSupported} is {@code "26.2.build.112"} — a non-numeric third segment.
     * Paper 26.1.2 (which does have a patch number) reports {@code "26.1.2.build.4-R0.1-SNAPSHOT"}
     * instead, so its third segment parses fine. The idealized strings above ("26.2", "26.2.0")
     * never actually exercised this path — only these real-world-shaped strings do.
     */
    @ParameterizedTest
    @CsvSource({
            "26.2.build.112, true",
            "26.1.2.build.4, true",
            "26.0.build.1, true",
            "1.21.11.build.5, true",
    })
    void isVersionSupportedHandlesNonNumericBuildSuffix(String mcVersion, boolean expected) {
        assertEquals(expected, Vanishpp.isVersionSupported(mcVersion));
    }
}
