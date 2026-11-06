package net.thecommandcraft.vanishpp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrWorkflowTest {

    @Test
    void pullRequestsRunPaperTestsAndUploadTheirReports() {
        YamlConfiguration workflow = YamlConfiguration.loadConfiguration(
                Path.of("..", ".github", "workflows", "pr-check.yml").toFile());
        List<Map<?, ?>> steps = workflow.getMapList("jobs.check.steps");
        Map<?, ?> build = steps.stream().filter(step -> "Build and run tests".equals(step.get("name")))
                .findFirst().orElseThrow();
        Map<?, ?> reports = steps.stream().filter(step -> "actions/upload-artifact@v4".equals(step.get("uses")))
                .findFirst().orElseThrow();
        Map<?, ?> options = (Map<?, ?>) reports.get("with");

        // Paper's suite runs in Failsafe after packaging, so Maven's test phase skips it.
        assertAll(
                () -> assertEquals("mvn clean verify", build.get("run"),
                        "Pull requests must reach verify so Paper assertions can fail CI"),
                () -> assertEquals("always()", reports.get("if"),
                        "Test reports must be uploaded even when assertions fail"),
                () -> assertTrue(options.get("path").toString().contains("**/target/failsafe-reports/"),
                        "Paper Failsafe reports must be uploaded"),
                () -> assertTrue(options.get("path").toString().contains("**/target/surefire-reports/"),
                        "Surefire reports must still be uploaded"));
    }
}
