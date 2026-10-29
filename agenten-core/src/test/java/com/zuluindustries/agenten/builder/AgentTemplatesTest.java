package com.zuluindustries.agenten.builder;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.AgentDefinitionLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTemplatesTest {

    static final String PROMPT = "1. Rolle und Ziel\nDu prüfst Lieferanten.";

    static final String VALID = """
            id: lieferanten_pruefer
            name: Lieferantenprüfer
            version: 1
            enabled: false
            description: |
              1. Rolle und Ziel
              Du prüfst Lieferanten.
            """;

    @TempDir
    Path directory;

    @Test
    void acceptsValidTemplateUnchanged() {
        AgentTemplates.Validated validated = AgentTemplates.validate("```yaml\n" + VALID + "```", PROMPT);

        assertEquals(VALID.strip(), validated.content());
        assertEquals("lieferanten_pruefer", validated.definition().id());
        assertFalse(validated.definition().enabled());
    }

    @Test
    void repairsTypicalModelMistakes() {
        String text = """
                id: lieferanten_pruefer
                enabled: false
                description: |
                  <GENERATED_PROMPT>
                  1. Rolle und Ziel
                  Du prüfst Lieferanten.
                  </GENERATED_PROMPT>
                model:
                  profile: openai-mini
                  fallback: {name: claude-haiku, temperature: 0.1}
                guardrails:
                  - Keine Preise erfinden: nur gelieferte Daten verwenden
                context: [Einkauf, Lieferanten]
                execution: {retries: 3, output: json}
                output: {type: yaml}
                """;

        AgentTemplates.Validated validated = AgentTemplates.validate(text, PROMPT);
        AgentDefinition definition = validated.definition();

        assertEquals(PROMPT, definition.description());
        assertEquals("claude-haiku", definition.model().fallbackProfile());
        assertEquals(List.of("Keine Preise erfinden: nur gelieferte Daten verwenden"),
                definition.guardrails().instructions());
        assertEquals("Einkauf\nLieferanten", definition.context().systemContext());
        assertEquals("session", definition.context().memory().scope());
        assertEquals(3, definition.execution().maxRetries());
        // execution.output wird übernommen; output.type entfällt daneben.
        assertEquals("json", definition.output().format());
        assertFalse(validated.content().contains("fallback:"));
        assertFalse(validated.content().contains("retries: 3\n  output"));
    }

    @Test
    void repairsMemoryFlagsAndScopes() {
        AgentDefinition flag = AgentTemplates.validate(VALID + "context: {memory: true}\n", PROMPT).definition();
        assertTrue(flag.context().memory().enabled());
        assertEquals("session", flag.context().memory().scope());

        AgentDefinition none = AgentTemplates.validate(VALID + "context: {memory: {enabled: false, scope: none}}\n",
                PROMPT).definition();
        assertEquals("session", none.context().memory().scope());

        AgentDefinition format = AgentTemplates.validate(VALID + "execution: {output_format: json}\n", PROMPT)
                .definition();
        assertEquals("json", format.output().format());

        // YAML, Markdown oder XML sind Textausgaben.
        assertEquals("text", AgentTemplates.validate(VALID + "output: {type: yaml}\n", PROMPT)
                .definition().output().format());
    }

    @Test
    void rejectsInvalidTemplates() {
        assertMessage("muss deaktiviert sein", VALID.replace("enabled: false", "enabled: true"));
        assertMessage("ASCII-snake_case", VALID.replace("lieferanten_pruefer", "Lieferanten-Prüfer"));
        assertMessage("stimmt nicht unverändert", VALID.replace("Du prüfst", "Du bewertest"));
        assertMessage("muss ein YAML-Objekt", "- nur\n- eine Liste\n");
        assertMessage("ist ungültig", "id: [offen\n");
        assertMessage("eindeutigen Profilnamen",
                VALID + "model: {fallback_profile: {name: claude-haiku, profile: openai-mini}}\n");
    }

    @Test
    void savesWithoutOverwriting() throws Exception {
        Path saved = AgentTemplates.save(VALID, PROMPT, directory);

        assertEquals(directory.resolve("lieferanten_pruefer.yaml"), saved);
        assertEquals(VALID.strip() + "\n", Files.readString(saved));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AgentTemplates.save(VALID, PROMPT, directory));
        assertTrue(error.getMessage().contains("wird nicht überschrieben"), error.getMessage());
    }

    @Test
    void canonicalFormRoundTrips() throws Exception {
        AgentDefinition definition = AgentTemplates.validate(VALID, PROMPT).definition();
        Path saved = AgentTemplates.save(AgentTemplates.canonical(definition), PROMPT, directory);

        String content = Files.readString(saved);
        assertTrue(content.contains("description: |"), content);
        assertTrue(content.contains("max_iterations: 8"), content);
        AgentDefinition loaded = AgentDefinitionLoader.load(saved);
        assertEquals(PROMPT, loaded.description().strip());
        assertEquals(definition.model(), loaded.model());
    }

    private static void assertMessage(String expected, String yaml) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AgentTemplates.validate(yaml, PROMPT));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }
}
