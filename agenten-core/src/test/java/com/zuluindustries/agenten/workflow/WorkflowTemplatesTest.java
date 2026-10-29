package com.zuluindustries.agenten.workflow;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.AgentDefinitionLoader;
import com.zuluindustries.agenten.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gerüste des workflow_creator_agent; entspricht {@code tests/test_workflow_template_writer.py}. */
class WorkflowTemplatesTest {

    static final String VALID_TEMPLATE = """
            name: Eingang prüfen und Entwurf erstellen
            version: 1
            enabled: false
            initial_step: eingang_pruefen

            limits:
              max_steps: 3
              timeout_seconds: 300

            steps:
              eingang_pruefen:
                kind: action
                action: todo_eingang_pruefen
                next: entwurf_erstellen

              entwurf_erstellen:
                kind: action
                action: todo_entwurf_erstellen
                next: fertig

              fertig:
                kind: end
            """;

    @TempDir
    Path dir;

    @Test
    void workflowCreatorAgentDefinitionIsLoadable() {
        AgentDefinition definition = AgentDefinitionLoader.load(
                Path.of("agents/definitions/workflow_creator_agent.yaml"));

        assertEquals("workflow_creator_agent", definition.id());
        assertTrue(definition.enabled());
        assertFalse(definition.permissions().writeOperations());
        assertTrue(definition.tools().allowed().isEmpty());
    }

    @Test
    void templateIsValidatedAndSavedWithSafeName() throws IOException {
        Path path = WorkflowTemplates.save(VALID_TEMPLATE, dir);

        assertEquals("eingang_pruefen_und_entwurf_erstellen.yaml", path.getFileName().toString());
        assertEquals(VALID_TEMPLATE, Files.readString(path));
        assertFalse(WorkflowLoader.load(path).enabled());
    }

    @Test
    void existingTemplateIsNotOverwritten() {
        Path first = WorkflowTemplates.save(VALID_TEMPLATE, dir);
        Path second = WorkflowTemplates.save(VALID_TEMPLATE, dir);

        assertEquals("eingang_pruefen_und_entwurf_erstellen.yaml", first.getFileName().toString());
        assertEquals("eingang_pruefen_und_entwurf_erstellen_2.yaml", second.getFileName().toString());
    }

    @Test
    void markdownFenceIsToleratedButNotSaved() throws IOException {
        Path path = WorkflowTemplates.save("```yaml\n" + VALID_TEMPLATE + "```", dir);

        assertFalse(Files.readString(path).startsWith("```"));
    }

    @Test
    void enabledTemplateIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WorkflowTemplates.validate(VALID_TEMPLATE.replace("enabled: false", "enabled: true")));
        assertTrue(error.getMessage().contains("deaktiviert"), error.getMessage());
    }

    @Test
    void invalidTransitionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WorkflowTemplates.validate(VALID_TEMPLATE.replace("next: fertig", "next: fehlt")));
        assertTrue(error.getMessage().contains("unbekannten Schritt"), error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingTechnicalEndIsAddedAfterLastWorkStep() throws IOException {
        String incomplete = """
                name: Informationsworkflow
                version: 1
                enabled: false
                initial_step: informationen_einholen
                limits:
                  max_steps: 4
                  timeout_seconds: 300
                steps:
                  informationen_einholen:
                    kind: action
                    action: todo_informationen_einholen
                    next: informationen_analysieren
                  informationen_analysieren:
                    kind: action
                    action: todo_informationen_analysieren
                    next: uebersichtlich_aufbereiten
                  uebersichtlich_aufbereiten:
                    kind: action
                    action: todo_uebersichtlich_aufbereiten
                    next: zusammenfassung_erstellen
                  zusammenfassung_erstellen:
                    kind: action
                    action: todo_zusammenfassung_erstellen
                """;

        Path path = WorkflowTemplates.save(incomplete, dir);
        Map<String, Object> saved = Json.DEFINITIONS_YAML.readValue(path.toFile(), Map.class);
        Map<String, Map<String, Object>> steps = (Map<String, Map<String, Object>>) saved.get("steps");

        assertEquals("complete", steps.get("zusammenfassung_erstellen").get("next"));
        assertEquals(Map.of("kind", "end"), steps.get("complete"));
        assertEquals(5, ((Map<String, Object>) saved.get("limits")).get("max_steps"));
        assertEquals(5, WorkflowLoader.load(path).steps().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stepListWithIdsIsNormalizedToMapping() throws IOException {
        String listed = """
                name: Bundesliga Ergebnisse ausgeben
                version: 1
                enabled: false
                initial_step: bundesligaergebnisse_lesen
                limits:
                  max_steps: 4
                  timeout_seconds: 300
                steps:
                  - id: bundesligaergebnisse_lesen
                    kind: action
                    action: todo_bundesligaergebnisse_lesen
                    next: ergebnisse_gruppieren
                  - id: ergebnisse_gruppieren
                    kind: action
                    action: todo_ergebnisse_gruppieren
                    next: ergebnisse_ausgeben
                  - id: ergebnisse_ausgeben
                    kind: action
                    action: todo_ergebnisse_ausgeben
                    next: workflow_end
                  - id: workflow_end
                    kind: end
                """;

        Path path = WorkflowTemplates.save(listed, dir);
        Map<String, Object> saved = Json.DEFINITIONS_YAML.readValue(path.toFile(), Map.class);

        Map<String, Object> steps = assertInstanceOf(Map.class, saved.get("steps"));
        Map<String, Object> first = (Map<String, Object>) steps.get("bundesligaergebnisse_lesen");
        assertFalse(first.containsKey("id"));
        assertEquals(Map.of("kind", "end"), steps.get("workflow_end"));
    }

    @Test
    void duplicateStepIdsInListAreRejected() {
        String listed = """
                name: Doppelte Schritte
                version: 1
                enabled: false
                initial_step: doppelt
                steps:
                  - id: doppelt
                    kind: end
                  - id: doppelt
                    kind: end
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WorkflowTemplates.validate(listed));
        assertTrue(error.getMessage().contains("doppelt vorhanden"), error.getMessage());
    }

    @Test
    void safeKeyTransliteratesUmlautsAndAccents() {
        assertEquals("gruesse_aus_koeln_cafe", WorkflowTemplates.safeKey("Grüße aus Köln – Café!"));
        assertEquals("workflow_template", WorkflowTemplates.safeKey("€€€"));
        assertEquals(64, WorkflowTemplates.safeKey("a".repeat(100)).length());
    }
}
