package com.zuluindustries.agenten.cli;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.util.Environment;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code agenten agent_builder ...} und {@code agenten agent_creator_agent ...} ohne externe Modellaufrufe. */
class AgentBuilderCommandTest {

    static final String PROMPT = "1. Rolle und Ziel\nDu prüfst Lieferanten.";
    static final String GENERATED = """
            id: lieferanten_pruefer
            enabled: false
            description: |
              1. Rolle und Ziel
              Du prüfst Lieferanten.
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
                "agents/definitions/agent_creator_agent.yaml", "agents/definitions/prompt_agent.yaml"}) {
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
    void agentBuilderSavesCanonicalDefinition() throws IOException {
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED));

        assertEquals(0, run("agent_builder", "Ein Agent, der Lieferanten prüft."));

        Path saved = home.resolve("agents/definitions/lieferanten_pruefer.yaml");
        assertTrue(out().contains("Definition gespeichert: " + saved), out());
        assertTrue(out().contains("Gesamt: 30"), out());
        // Vollständige Definition mit Standardwerten.
        assertTrue(Files.readString(saved).contains("max_output_tokens: 4096"));
    }

    @Test
    void agentBuilderFailsWithExitCodeWhenNothingIsSaved() {
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED.replace("enabled: false",
                "enabled: true")));

        assertEquals(1, run("agent_builder", "Ein Agent, der Lieferanten prüft."));
        assertTrue(out().contains("Definition konnte nicht gespeichert werden: Eine erzeugte Agentendefinition muss "
                + "deaktiviert sein."), out());
    }

    @Test
    void agentCreatorSavesGeneratedYamlAsIs() throws IOException {
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED));

        assertEquals(0, run("agent_creator_agent", "Ein Agent, der Lieferanten prüft."));

        Path saved = home.resolve("agents/definitions/lieferanten_pruefer.yaml");
        assertTrue(out().contains("Definition gespeichert: " + saved), out());
        assertEquals(GENERATED, Files.readString(saved));

        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED));
        assertEquals(1, run("agent_creator_agent", "Noch einmal."));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("wird nicht überschrieben"));
    }
}
