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
}
