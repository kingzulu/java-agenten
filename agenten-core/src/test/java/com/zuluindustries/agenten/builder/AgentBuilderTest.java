package com.zuluindustries.agenten.builder;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.AgentDefinitionLoader;
import com.zuluindustries.agenten.agent.PermissionConfig;
import com.zuluindustries.agenten.core.AgentPermissionException;
import com.zuluindustries.agenten.core.AgentResult;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.util.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent Builder mit den mitgelieferten Definitionen in einem temporären Projektverzeichnis. */
class AgentBuilderTest {

    static final String PROMPT = "1. Rolle und Ziel\nDu prüfst Lieferanten.";

    static final String GENERATED = """
            id: lieferanten_pruefer
            name: Lieferantenprüfer
            version: 1
            enabled: false
            description: |
              1. Rolle und Ziel
              Du prüfst Lieferanten.
            model: {profile: openai-mini, fallback: claude-haiku}
            execution: {retries: 1}
            """;

    @TempDir
    Path home;

    final FakeProvider openai = new FakeProvider("openai");
    Platform platform;

    @BeforeEach
    void setUp() throws IOException {
        Path repository = Path.of("").toAbsolutePath();
        for (String file : new String[] {"config/models.yaml", "agents/definitions/agent_builder.yaml",
                "agents/definitions/agent_creator_agent.yaml", "agents/definitions/prompt_agent.yaml"}) {
            Fixtures.write(home.resolve(file), Files.readString(repository.resolve(file)));
        }
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", home.toString()));
        platform = Platform.builder(Settings.load(environment), environment)
                .providers(new ProviderRegistry().register(openai))
                .build();
    }

    private AgentDefinition builder() {
        return platform.agents().get(AgentBuilder.AGENT_ID, null);
    }

    @Test
    void buildsAndSavesDisabledDefinition() {
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED));

        AgentResult result = platform.runAgent(builder(), "Ein Agent, der Lieferanten prüft.", null);

        Path saved = home.resolve("agents/definitions/lieferanten_pruefer.yaml");
        assertNull(result.error(), result.text());
        assertTrue(result.text().startsWith("Definition gespeichert: " + saved), result.text());
        AgentDefinition definition = AgentDefinitionLoader.load(saved);
        assertFalse(definition.enabled());
        assertEquals(PROMPT, definition.description().strip());
        assertEquals("claude-haiku", definition.model().fallbackProfile());
        // Beide Modellaufrufe zählen für Tokens und Kosten.
        assertEquals(2, result.modelCalls().size());
        assertEquals(30, result.totalTokens());

        // Verschachtelte Agenten laufen ohne Werkzeuge; der Creator nutzt das Modell des Builders.
        assertTrue(openai.requests.stream().allMatch(request -> request.tools().isEmpty()));
        assertEquals(0.2, openai.requests.get(0).modelConfig().temperature());
        assertEquals(builder().model(), openai.requests.get(1).modelConfig());
        String creatorInput = openai.requests.get(1).input().toString();
        assertTrue(creatorInput.contains("<GENERATED_PROMPT>\n" + PROMPT + "\n</GENERATED_PROMPT>"), creatorInput);
    }

    @Test
    void reportsDefinitionsThatCannotBeSaved() {
        openai.then(FakeProvider.text(PROMPT))
                .then(FakeProvider.text(GENERATED + "tools: {allowed: [gibt_es_nicht]}\n"));

        AgentResult result = platform.runAgent(builder(), "Ein Agent, der Lieferanten prüft.", null);

        assertTrue(result.failed());
        assertTrue(result.text().startsWith("Definition konnte nicht gespeichert werden: "), result.text());
        assertFalse(Files.exists(home.resolve("agents/definitions/lieferanten_pruefer.yaml")));
    }

    @Test
    void neverOverwritesExistingDefinitions() {
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED.replace("lieferanten_pruefer",
                "prompt_agent")));

        AgentResult result = platform.runAgent(builder(), "Ein Agent, der Lieferanten prüft.", null);

        assertTrue(result.text().contains("existiert bereits"), result.text());
    }

    @Test
    void requiresWritePermissionUnlessDraftOnly() {
        AgentDefinition readOnly = withPermissions(new PermissionConfig(true, false, false, false, null));
        assertThrows(AgentPermissionException.class, () -> platform.runAgent(readOnly, "x", null));

        AgentDefinition confirm = withPermissions(new PermissionConfig(true, false, true, false, List.of("write")));
        AgentPermissionException error = assertThrows(AgentPermissionException.class,
                () -> platform.runAgent(confirm, "x", null));
        assertTrue(error.getMessage().contains("Bestätigung"), error.getMessage());

        assertThrows(IllegalArgumentException.class, () -> platform.runAgent(builder(), "  ", null));

        List<String> inputs = new ArrayList<>();
        AgentBuilder.Result draft = platform.agentBuilder().run(readOnly, "Ein Agent, der Lieferanten prüft.",
                (agent, input) -> {
                    inputs.add(agent.id());
                    return text(inputs.size() == 1 ? PROMPT : GENERATED);
                }, true);
        assertEquals(List.of("prompt_agent", "agent_creator_agent"), inputs);
        assertEquals(GENERATED, draft.draft());
        assertNull(draft.savedPath());
        assertFalse(Files.exists(home.resolve("agents/definitions/lieferanten_pruefer.yaml")));
    }

    @Test
    void stopsWhenPromptAgentFails() {
        List<String> calls = new ArrayList<>();
        AgentBuilder.Result result = platform.agentBuilder().run(builder(), "x", (agent, input) -> {
            calls.add(agent.id());
            return new AgentResult(null, "Agentenfehler: kaputt", null, 0, 0, 0, "kaputt", null, null, null);
        }, false);

        assertEquals(List.of("prompt_agent"), calls);
        assertEquals("Prompt-Erstellung fehlgeschlagen: Agentenfehler: kaputt", result.result().text());
        assertNull(result.generatedPrompt());
    }

    private AgentDefinition withPermissions(PermissionConfig permissions) {
        AgentDefinition agent = builder();
        return new AgentDefinition(agent.id(), agent.name(), agent.version(), agent.enabled(), agent.description(),
                agent.model(), agent.capabilities(), agent.skills(), agent.tools(), agent.execution(), permissions,
                agent.guardrails(), agent.context(), agent.output());
    }

    private static AgentResult text(String text) {
        return new AgentResult(null, text, null, 0, 0, 0, null, null, null, null);
    }
}
