package com.zuluindustries.agenten.monitor;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.util.Environment;
import com.zuluindustries.agenten.workflow.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Das Monitor-Modul wird über den ServiceLoader gefunden und bringt Agent, Workflow, Aktion und Befehl mit. */
class MonitorModuleTest {

    @TempDir
    Path home;

    private static Platform platform(Path root) {
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", root.toString()));
        return Platform.builder(Settings.load(environment), environment).build();
    }

    @Test
    void shippedModuleIsDiscoveredAndComplete() {
        Platform platform = platform(Path.of("").toAbsolutePath());

        MonitorExtension extension = platform.extension(MonitorExtension.class).orElseThrow();
        assertNotNull(extension.monitor(), "config/monitor.yaml im Projektverzeichnis");
        assertTrue(platform.agents().contains(MonitorExtension.REPORT_AGENT));
        assertTrue(platform.workflows().definitions().containsKey(AvailabilityMonitor.WORKFLOW));
        assertTrue(platform.commands().stream().anyMatch(command -> "--monitor".equals(command.option())));
        platform.workflows().definitions().get(AvailabilityMonitor.WORKFLOW).steps().values().stream()
                .filter(step -> step.agent() != null)
                .forEach(step -> assertTrue(platform.agents().contains(step.agent()), step.agent()));
        var report = platform.agents().get(MonitorExtension.REPORT_AGENT, null);
        assertTrue(platform.profiles().contains(report.model().profile()));
        platform.runner().runtime().policy().validateResources(report);
    }

    @Test
    void withoutConfigurationTheWorkflowFailsClearly() {
        com.zuluindustries.agenten.support.Fixtures.write(home.resolve("config/models.yaml"),
                readRepositoryFile("config/models.yaml"));
        Platform platform = platform(home);

        assertNull(platform.extension(MonitorExtension.class).orElseThrow().monitor());
        WorkflowRun run = platform.workflows().start(AvailabilityMonitor.WORKFLOW, Map.of());
        assertEquals("failed", run.status.key());
        assertTrue(run.errorMessage.contains(MonitorConfig.RELATIVE_PATH), run.errorMessage);
    }

    private static String readRepositoryFile(String file) {
        try {
            return java.nio.file.Files.readString(Path.of("").toAbsolutePath().resolve(file));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
