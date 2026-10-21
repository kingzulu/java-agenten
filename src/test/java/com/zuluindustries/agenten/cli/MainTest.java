package com.zuluindustries.agenten.cli;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.util.Environment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft die CLI gegen die Definitionen dieses Repositorys, ohne externe Modellaufrufe. */
class MainTest {

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final FakeProvider openai = new FakeProvider("openai");

    private int run(String... args) {
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", Path.of("").toAbsolutePath().toString()));
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
    void printsUsageWithAgents() {
        assertEquals(1, run());
        assertTrue(out().contains("Verfügbare Agenten: assistant, aufgaben, code_entwickler, code_planer, code_reviewer, "
                + "code_testautor, workflow_worker"));
    }

    @Test
    void showsConfig() {
        assertEquals(0, run("--show-config", "workflow_worker"));
        assertTrue(out().contains("id: workflow_worker"));
        assertTrue(out().contains("strategy: manual"));
        assertTrue(out().contains("max_output_tokens: 2048"));

        assertEquals(1, run("--show-config", "fehlt"));
    }

    @Test
    void runsAgentOnceAndPrintsCosts() {
        openai.then(FakeProvider.text("Antwort"));

        assertEquals(0, run("Frage ohne Agent"));

        String output = out();
        assertTrue(output.contains("Provider: openai"));
        assertTrue(output.contains("Antwort"));
        assertTrue(output.contains("Gesamt: 15"));
        assertTrue(output.contains("Gesamt: 0.00003000 USD"));
        assertTrue(output.contains("Preisstand: "));
    }

    @Test
    void runsWorkflowFromCommandLine() {
        assertEquals(0, run("--workflow", "priorisierung", "{\"vorgang_id\": 1, \"nachricht\": \"x\", \"prioritaet\": \"hoch\"}"));
        assertTrue(out().contains("\"status\" : \"waiting_for_approval\""));

        assertEquals(1, run("--workflow", "priorisierung", "kein json"));
    }
}
