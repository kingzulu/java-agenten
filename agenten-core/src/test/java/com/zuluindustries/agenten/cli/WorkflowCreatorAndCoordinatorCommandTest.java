package com.zuluindustries.agenten.cli;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.util.Environment;
import com.zuluindustries.agenten.workflow.WorkflowLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code agenten workflow_creator_agent ...} und {@code agenten coordinator ...} ohne externe Modellaufrufe. */
class WorkflowCreatorAndCoordinatorCommandTest {

    static final String TEMPLATE = """
            name: Bundesliga auswerten
            version: 1
            enabled: false
            initial_step: ergebnisse_lesen
            steps:
              ergebnisse_lesen:
                kind: action
                action: todo_ergebnisse_lesen
                next: ergebnisse_gruppieren
              ergebnisse_gruppieren:
                kind: action
                action: todo_ergebnisse_gruppieren
            """;

    @TempDir
    Path home;

    final FakeProvider openai = new FakeProvider("openai");
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void copyDefinitions() throws IOException {
        Path repository = Path.of("").toAbsolutePath();
        for (String file : new String[] {"config/models.yaml", "agents/definitions/agent_builder.yaml",
                "agents/definitions/agent_creator_agent.yaml", "agents/definitions/prompt_agent.yaml",
                "agents/definitions/workflow_creator_agent.yaml", "agents/definitions/coordinator.yaml"}) {
            Fixtures.write(home.resolve(file), Files.readString(repository.resolve(file)));
        }
    }

    private int run(String... args) {
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", home.toString()));
        Platform platform = Platform.builder(Settings.load(environment), environment)
                .providers(new ProviderRegistry().register(openai))
                .build();
        return new Main().run(platform, args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void workflowCreatorSavesDisabledTemplate() {
        openai.then(FakeProvider.text("```yaml\n" + TEMPLATE + "```"));

        assertEquals(0, run("workflow_creator_agent", "Ergebnisse lesen, dann gruppieren."));

        Path saved = home.resolve("workflows/definitions/bundesliga_auswerten.yaml");
        assertTrue(out().contains("Workflow-Gerüst gespeichert: " + saved), out());
        assertTrue(out().contains("Gesamt: 15"), out());
        assertFalse(WorkflowLoader.load(saved).enabled());
        assertEquals("complete", WorkflowLoader.load(saved).step("ergebnisse_gruppieren").next());
        // Der Agent läuft ohne Werkzeuge.
        assertTrue(openai.requests.getFirst().tools().isEmpty());
        // Ein gespeichertes Gerüst verhindert den nächsten Plattformstart nicht.
        openai.then(FakeProvider.text("x"));
        assertEquals(0, run("x"));
    }

    @Test
    void workflowCreatorFailsWhenTemplateIsInvalid() {
        openai.then(FakeProvider.text(TEMPLATE.replace("enabled: false", "enabled: true")));

        assertEquals(1, run("workflow_creator_agent", "Ergebnisse lesen, dann gruppieren."));

        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Workflow-Gerüst konnte nicht gespeichert werden"));
        assertFalse(Files.exists(home.resolve("workflows/definitions/bundesliga_auswerten.yaml")));
    }

    @Test
    void coordinatorShowsDraftAndDigest() {
        openai.then(FakeProvider.text("1. Rolle\nDu fasst zusammen."))
                .then(FakeProvider.text("""
                        id: zusammenfasser
                        enabled: false
                        description: |
                          1. Rolle
                          Du fasst zusammen.
                        capabilities: [summarize]
                        """));

        assertEquals(0, run("coordinator", "{\"task\": \"Fasse zusammen\", \"capabilities\": [\"summarize\"]}"));

        assertTrue(out().contains("Status: waiting_for_approval"), out());
        assertTrue(out().contains("id: zusammenfasser"), out());
        assertTrue(out().matches("(?s).*Freigabe-Digest \\(SHA-256\\): [0-9a-f]{64}.*"), out());
        assertTrue(out().contains("Gesamt: 30"), out());
        assertFalse(Files.exists(home.resolve("agents/definitions/zusammenfasser.yaml")));
    }

    @Test
    void coordinatorFailsWithExitCode() {
        assertEquals(1, run("coordinator", "kein json"));

        assertTrue(out().contains("Status: failed"), out());
    }
}
